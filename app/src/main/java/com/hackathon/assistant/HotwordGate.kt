package com.hackathon.assistant

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the "Jarvis" hotword may use Google's recognizer: enabled, no call, and no keyboard showing
 * (so Gboard voice typing always gets it).
 */
class HotwordGate(
    private val context: Context,
    private val enabled: StateFlow<Boolean>,
    private val keyboardVisible: MutableStateFlow<Boolean>,
    private val recheckKeyboard: () -> Unit,
) {
    /** Why the hotword is gated off, or "" when it may listen. */
    val reason = MutableStateFlow("")
    private val inCall = MutableStateFlow(false)

    fun allowed(scope: CoroutineScope): StateFlow<Boolean> {
        val audio = context.getSystemService(AudioManager::class.java)
        inCall.value = audio.mode != AudioManager.MODE_NORMAL
        // Mode-change callbacks exist from Android 12; before that the loop below polls the mode.
        val modeCallbacks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (modeCallbacks) audio.addOnModeChangedListener(context.mainExecutor) { inCall.value = it != AudioManager.MODE_NORMAL }
        // Window events can be missed; while the keyboard flag blocks us, re-check it every second.
        scope.launch {
            while (true) {
                delay(1_000)
                if (keyboardVisible.value) recheckKeyboard()
                if (!modeCallbacks) inCall.value = audio.mode != AudioManager.MODE_NORMAL
            }
        }
        return combine(enabled, inCall, keyboardVisible) { on, call, keyboard ->
            reason.value = when {
                !on -> "disabled"; call -> "in a call"; keyboard -> "keyboard open"; else -> ""
            }
            on && !call && !keyboard
        }.stateIn(scope, SharingStarted.Eagerly, false)
    }
}
