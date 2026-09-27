package com.hackathon.assistant.voice

import com.hackathon.assistant.core.TaskStep
import com.hackathon.assistant.core.VoiceIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the on-screen card reads, from whichever voice is active. */
interface AssistantVoice : VoiceIO {
    /** 0..1 mic loudness while listening. */
    val level: StateFlow<Float>
    val captions: StateFlow<Captions>
    /** Steps of the task in progress (empty when there is none), for the card's stepper. */
    val steps: StateFlow<List<TaskStep>>
    fun setThinking(thinking: Boolean)

    /** Shows [text] as the user's request (it may come from the hotword or be typed, not from [listen]). */
    fun showHeard(text: String)

    /**
     * Speaks a reply that arrives as text deltas (a streaming model), starting each sentence as
     * soon as it is complete. Returns the whole reply.
     */
    suspend fun speakStream(deltas: Flow<String>): String
}
