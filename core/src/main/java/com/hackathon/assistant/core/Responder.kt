package com.hackathon.assistant.core

import kotlinx.coroutines.flow.Flow

enum class Speaker { USER, ASSISTANT }

data class Turn(val speaker: Speaker, val text: String)

/** One request to the answer step of the Jarvis pipeline: what was heard, plus the recent conversation. */
data class ResponderRequest(
    val utterance: String,
    val history: List<Turn> = emptyList(),
    val locale: String = "en-IN",
)

/** What a [Responder] streams back: progress steps while it works, then the answer text. */
sealed interface ResponseEvent {
    /** A new step started ("Searching flights"); the previous one is done. */
    data class Step(val title: String, val detail: String = "") : ResponseEvent
    /** The current step failed. */
    data class StepFailed(val reason: String = "") : ResponseEvent
    /** A piece of the spoken answer (text deltas, in order). */
    data class Text(val delta: String) : ResponseEvent
}

/**
 * The answer step of the Jarvis pipeline (speech → text → [Responder] → text → speech).
 * Implementations stream [ResponseEvent]s: steps appear on the card as they happen, and the
 * answer is spoken sentence by sentence as it arrives. Swap implementations in `AssistantApp`.
 */
interface Responder {
    val id: String
    fun respond(request: ResponderRequest): Flow<ResponseEvent>
}
