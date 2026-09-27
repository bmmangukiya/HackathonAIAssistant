package com.hackathon.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hackathon.assistant.core.StepStatus
import com.hackathon.assistant.core.TaskStep
import com.hackathon.assistant.core.VoiceState
import com.hackathon.assistant.perception.AssistantAccessibilityService
import com.hackathon.assistant.voice.AssistantVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bottom card over any app. Drawn as an accessibility overlay (the service is already on, so
 * no "draw over apps" prompt), else as an app overlay if that permission was granted.
 * Shows while the assistant is active and hides [HIDE_AFTER_MS] after it goes idle.
 */
class AssistantOverlay(
    private val app: Context,
    private val voice: AssistantVoice,
    private val onMic: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val visible = MutableStateFlow(false)
    /** Whether the card is on screen (drives the camera-ring light). */
    val shown: kotlinx.coroutines.flow.StateFlow<Boolean> get() = visible
    private var view: ComposeView? = null
    private var host: Context? = null
    private var owner: OverlayOwner? = null
    /** Agent screen actions in flight; while > 0 the card lets touches through to the app. */
    private val acting = AtomicInteger(0)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A keyboard is up (typing a password, Gboard voice typing). */
    private val keyboardUp = AssistantAccessibilityService.keyboardVisible

    /**
     * The card steps out of the keyboard's way only when the USER types: no task running, or the
     * task is waiting for them (a password hand-off). A keyboard the agent opened (a search box)
     * must not hide the task's steps and Stop.
     */
    private fun stepAside(keyboard: Boolean, steps: List<TaskStep>) =
        keyboard && (steps.isEmpty() || steps.last().status == StepStatus.WAITING_FOR_USER)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main) { combine(keyboardUp, voice.steps) { _, _ -> }.collect { updateTouchable() } }
        scope.launch(Dispatchers.Main) {
            voice.state.collectLatest { state ->
                if (state != VoiceState.IDLE) {
                    attachIfNeeded()
                    visible.value = true
                } else {
                    // Let the last words or the notice be read, then close. A new turn cancels this.
                    delay(if (voice.captions.value.notice.isNotEmpty()) NOTICE_HIDE_MS else HIDE_AFTER_MS)
                    visible.value = false
                }
                updateTouchable()
            }
        }
    }

    /**
     * The accessibility service (re)connected, e.g. after vivo stripped it and the watchdog re-added
     * it: the old service's window is gone, so re-attach to the new one if the card is up.
     */
    fun refresh() {
        main.post {
            if (voice.state.value != VoiceState.IDLE) attachIfNeeded()
            updateTouchable()
        }
    }

    /** Make the card invisible for a moment (e.g. while the agent takes a screenshot) without closing it. */
    fun hideForCapture(hide: Boolean) {
        main.post { view?.visibility = if (hide) android.view.View.INVISIBLE else android.view.View.VISIBLE }
    }

    private fun dismiss() {
        // A touch the agent dispatched that still reached the card is not the user closing it.
        if (acting.get() > 0) return
        visible.value = false
        updateTouchable()
        onClose()
    }

    private fun attachIfNeeded() {
        val service = AssistantAccessibilityService.instance
        val target: Context = service ?: app.takeIf { Settings.canDrawOverlays(it) } ?: run {
            Log.w(TAG, "no accessibility service and no overlay permission; card not shown")
            return
        }
        if (view != null && host === target) return
        detach()
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        val lifecycle = OverlayOwner().also { it.start() }
        val compose = ComposeView(target).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                val wanted by visible.collectAsState()
                val keyboard by keyboardUp.collectAsState()
                val taskSteps by voice.steps.collectAsState()
                val state by voice.state.collectAsState()
                val captions by voice.captions.collectAsState()
                val steps by voice.steps.collectAsState()
                AssistantCardHost(
                    visible = wanted && !stepAside(keyboard, taskSteps),
                    state = state,
                    captions = captions,
                    level = voice.level,
                    onMic = { if (acting.get() == 0) onMic() },
                    onClose = ::dismiss,
                    steps = steps,
                )
            }
        }
        runCatching { target.getSystemService(WindowManager::class.java).addView(compose, params) }
            .onFailure { Log.e(TAG, "addView failed", it); lifecycle.destroy(); return }
        view = compose
        host = target
        owner = lifecycle
    }

    /**
     * Runs one agent screen action with the card letting touches through, so a gesture aimed at
     * the app underneath never lands on the card. The rest of the time the card stays tappable
     * (✕ / Stop work while a task runs).
     */
    suspend fun <T> letTouchesThrough(block: suspend () -> T): T {
        try {
            acting.incrementAndGet()
            // The new flags reach the input system a frame or two later: wait before touching.
            if (withContext(Dispatchers.Main) { updateTouchable() }) delay(FLAG_SETTLE_MS)
            return block()
        } finally {
            acting.decrementAndGet()
            withContext(NonCancellable + Dispatchers.Main) { updateTouchable() }
        }
    }

    /**
     * Touches pass through while hidden (or stepped aside for a keyboard), and while the agent is
     * touching the app underneath. Returns true if the flags changed.
     */
    private fun updateTouchable(): Boolean {
        val v = view ?: return false
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return false
        val passThrough = !visible.value || stepAside(keyboardUp.value, voice.steps.value) || acting.get() > 0
        val flags = if (passThrough) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        if (params.flags == flags) return false
        params.flags = flags
        return runCatching { host?.getSystemService(WindowManager::class.java)?.updateViewLayout(v, params) }.isSuccess
    }

    private fun detach() {
        val v = view ?: return
        runCatching { host?.getSystemService(WindowManager::class.java)?.removeView(v) }
        owner?.destroy()
        view = null
        host = null
        owner = null
    }

    /** Compose needs a lifecycle and saved-state owner; an overlay window has neither. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        const val TAG = "AssistantOverlay"
        const val HIDE_AFTER_MS = 1_200L
        const val NOTICE_HIDE_MS = 1_800L
        /** Time for a window-flag change to reach input dispatch (a couple of frames). */
        const val FLAG_SETTLE_MS = 64L
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
}
