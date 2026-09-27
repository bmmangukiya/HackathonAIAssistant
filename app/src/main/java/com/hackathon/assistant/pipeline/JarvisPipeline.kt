package com.hackathon.assistant.pipeline

import android.util.Log
import com.hackathon.assistant.core.ResponderRequest
import com.hackathon.assistant.core.ResponseEvent
import com.hackathon.assistant.core.Responder
import com.hackathon.assistant.core.Speaker
import com.hackathon.assistant.core.StepStatus
import com.hackathon.assistant.core.TaskProgress
import com.hackathon.assistant.core.Turn
import com.hackathon.assistant.voice.AssistantVoice
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * One Jarvis turn after speech-to-text: the heard [utterance] goes to the [Responder]. Its steps
 * go to [progress] (the card's stepper) as they arrive; its answer text is spoken (and shown)
 * sentence by sentence. Keeps the last few turns so follow-ups ("and tomorrow?") have context.
 */
class JarvisPipeline(
    private val voice: AssistantVoice,
    private val progress: TaskProgress,
    @Volatile var responder: Responder,
) {
    private val history = ArrayDeque<Turn>()

    suspend fun handle(utterance: String) {
        Log.i(TAG, "request [${responder.id}]: $utterance")
        voice.showHeard(utterance)
        voice.setThinking(true)
        progress.start(utterance)
        var current = 0
        var ok = false
        try {
            val request = ResponderRequest(utterance, synchronized(history) { history.toList() })
            val text = responder.respond(request)
                .onEach { e ->
                    when (e) {
                        is ResponseEvent.Step -> current = progress.step(e.title, e.detail)
                        is ResponseEvent.StepFailed -> if (current != 0) progress.update(current, StepStatus.FAILED, detail = e.reason.ifBlank { null })
                        is ResponseEvent.Text -> Unit
                    }
                }
                .filterIsInstance<ResponseEvent.Text>()
                .map { it.delta }
            val reply = voice.speakStream(text)
            ok = true
            Log.i(TAG, "reply [${responder.id}]: $reply")
            synchronized(history) {
                history += Turn(Speaker.USER, utterance)
                history += Turn(Speaker.ASSISTANT, reply)
                while (history.size > MAX_TURNS) history.removeFirst()
            }
        } finally {
            progress.finish(ok)
        }
    }

    fun forget() = synchronized(history) { history.clear() }

    private companion object {
        const val TAG = "Pipeline"
        const val MAX_TURNS = 12
    }
}
