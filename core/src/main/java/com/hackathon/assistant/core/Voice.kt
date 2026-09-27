package com.hackathon.assistant.core

import kotlinx.coroutines.flow.StateFlow

/** The only channel to the user: speech in, speech out. Implemented by :voice. */
interface VoiceIO {
    val state: StateFlow<VoiceState>

    /** Listens for one utterance. Null on silence/timeout/cancel. */
    suspend fun listen(timeoutMs: Long = 8_000): String?

    /** Speaks and suspends until playback finishes. */
    suspend fun speak(text: String)

    /** Speaks [question] then listens for the answer. */
    suspend fun ask(question: String): String? {
        speak(question)
        return listen()
    }


    /** Interrupts speaking or listening immediately (barge-in, "stop"). */
    fun stop()
}

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }
