package com.hackathon.assistant.voice

import com.hackathon.assistant.core.TaskStep
import com.hackathon.assistant.core.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the card shows, as one [AssistantVoice]: the Google voice, the "Jarvis" hotword
 * (LISTENING, level and live words from the instant "Jarvis" is heard) and the running task's
 * steps. While a task is running the state stays THINKING between Jarvis' sentences, so the
 * card stays up for the whole task instead of hiding whenever the voice goes quiet.
 */
class JarvisVoice(
    val google: AndroidVoiceIO,
    val hotword: GoogleHotword,
    val tasks: TaskTracker,
    scope: CoroutineScope,
) : AssistantVoice by google {
    override val state: StateFlow<VoiceState> = combine(google.state, hotword.awake, tasks.active) { s, awake, working ->
        when {
            s != VoiceState.IDLE -> s
            working -> VoiceState.THINKING
            awake -> VoiceState.LISTENING
            else -> VoiceState.IDLE
        }
    }.stateIn(scope, SharingStarted.Eagerly, VoiceState.IDLE)

    override val level: StateFlow<Float> = combine(google.level, hotword.level) { a, b -> maxOf(a, b) }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    // While the hotword captures the request, show its live words (not last turn's captions).
    override val captions: StateFlow<Captions> = combine(
        combine(google.captions, hotword.partial, hotword.pending) { c, p, pend -> Triple(c, p, pend) },
        google.state, hotword.awake, tasks.active,
    ) { (c, p, pend), s, awake, working ->
        if (awake && s == VoiceState.IDLE && !working) Captions(heard = p, pending = pend, userSpeaking = p.isNotEmpty() || pend.isNotEmpty()) else c
    }.stateIn(scope, SharingStarted.Eagerly, Captions())

    override val steps: StateFlow<List<TaskStep>> = tasks.steps
}
