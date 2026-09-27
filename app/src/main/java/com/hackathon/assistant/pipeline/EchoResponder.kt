package com.hackathon.assistant.pipeline

import com.hackathon.assistant.core.Responder
import com.hackathon.assistant.core.ResponderRequest
import com.hackathon.assistant.core.ResponseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Placeholder answer step: repeats the request back, streamed word by word like a real model. */
class EchoResponder(private val wordDelayMs: Long = 35) : Responder {
    override val id = "echo"

    override fun respond(request: ResponderRequest): Flow<ResponseEvent> = flow {
        for ((i, w) in request.utterance.trim().split(" ").withIndex()) {
            emit(ResponseEvent.Text(if (i == 0) w else " $w"))
            delay(wordDelayMs)
        }
    }
}
