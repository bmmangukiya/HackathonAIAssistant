package com.hackathon.assistant.pipeline

import com.hackathon.assistant.core.Responder
import com.hackathon.assistant.core.ResponderRequest
import com.hackathon.assistant.core.ResponseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stand-in for the processing pipeline, to exercise the card end to end: reports a few
 * realistic steps with pauses, then streams a short answer. Replace with the real backend's
 * [Responder] in `AssistantApp`.
 */
class DemoResponder(private val stepMs: Long = 900) : Responder {
    override val id = "demo"

    override fun respond(request: ResponderRequest): Flow<ResponseEvent> = flow {
        val ask = request.utterance.trim().trimEnd('.', '?', '!')
        emit(ResponseEvent.Step("Understanding your request", "“$ask”"))
        delay(stepMs)
        emit(ResponseEvent.Step("Finding the right app"))
        delay(stepMs)
        emit(ResponseEvent.Step("Working on it", "Preparing the result"))
        delay(stepMs)
        emit(ResponseEvent.Step("Checking the result"))
        delay(stepMs / 2)
        for ((i, w) in "All done. I worked on: $ask.".split(" ").withIndex()) {
            emit(ResponseEvent.Text(if (i == 0) w else " $w"))
            delay(35)
        }
    }
}
