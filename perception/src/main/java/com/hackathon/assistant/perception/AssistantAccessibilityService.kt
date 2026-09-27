package com.hackathon.assistant.perception

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** Entry point for screen reading and control. */
class AssistantAccessibilityService : AccessibilityService() {

    /** Uptime of the last event that changed the UI. */
    @Volatile
    var lastChangeAt = 0L
        private set

    /** Monotonic count of UI events; a marker taken before an action tells if the UI reacted. */
    @Volatile
    var eventCount = 0L
        private set

    override fun onServiceConnected() {
        instance = this
        onConnected?.invoke()
        // The system accessibility button (nav bar / floating) starts the assistant from any app.
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    onTrigger?.invoke()
                }
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkKeyboard()
        }
        // Our own overlay/app changing is not a reaction to the agent's action.
        if (event?.packageName == packageName) return
        lastChangeAt = SystemClock.uptimeMillis()
        eventCount++
    }

    /** Re-reads whether a keyboard window is on screen. */
    fun checkKeyboard() {
        keyboardVisible.value = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
    }

    override fun onInterrupt() = Unit

    /** Screenshot of the whole display as a Bitmap (Android 11+), or null on failure. */
    suspend fun screenshot(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resumeWith(Result.success(bmp))
                }
                override fun onFailure(errorCode: Int) {
                    android.util.Log.w("A11yService", "screenshot failed: $errorCode")
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            },
        )
    }

    override fun onDestroy() {
        instance = null
        keyboardVisible.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set

        /** Set by the app: called when the service (re)connects. */
        @Volatile
        var onConnected: (() -> Unit)? = null

        /** True while any on-screen keyboard is showing (the "Jarvis" hotword steps aside for Gboard voice typing). */
        val keyboardVisible = MutableStateFlow(false)

        /** Set by the app: what to do when the user presses the accessibility button. */
        @Volatile
        var onTrigger: (() -> Unit)? = null
    }
}
