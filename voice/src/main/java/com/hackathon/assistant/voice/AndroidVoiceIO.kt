package com.hackathon.assistant.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hackathon.assistant.core.TaskStep
import com.hackathon.assistant.core.VoiceState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Speech in/out on Google's on-device recognizer and offline Google TTS, through
 * [GoogleSpeechRecognizer] and [GoogleTextToSpeech]. Nothing leaves the phone. Publishes
 * [state], mic [level] and [captions] for the Jarvis card.
 */
class AndroidVoiceIO(
    context: Context,
    val stt: GoogleSpeechRecognizer = GoogleSpeechRecognizer(context),
    val tts: GoogleTextToSpeech = GoogleTextToSpeech(context),
) : AssistantVoice {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    override val state: StateFlow<VoiceState> = _state
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level
    private val _captions = MutableStateFlow(Captions())
    override val captions: StateFlow<Captions> = _captions
    override val steps: StateFlow<List<TaskStep>> = MutableStateFlow(emptyList())

    /** Words the recognizer should favour (contact and app names). */
    @Volatile var hints: List<String> = emptyList()

    /** Recognizer settings for requests; tuned for short spoken commands. */
    var listenOptions = ListenOptions(completeSilenceMs = 1_800, possiblyCompleteSilenceMs = 1_400)

    private var voiceChosen = false

    override fun setThinking(thinking: Boolean) {
        if (thinking) _state.value = VoiceState.THINKING
        else if (_state.value == VoiceState.THINKING) _state.value = VoiceState.IDLE
    }

    /** Shows a short status line on the card, e.g. "Didn't catch that". */
    fun showNotice(text: String) {
        _captions.value = Captions(notice = text)
    }

    override fun showHeard(text: String) {
        _captions.value = Captions(heard = text, heardFinal = true)
    }

    // ---- listening ------------------------------------------------------------------------

    /**
     * Listens for one request, until the user has finished speaking.
     *
     * Google's recognizer ends a session at every pause, so sessions run back to back and their
     * sentences are joined. The request ends when the user has been quiet for [REQUEST_DONE_MS]
     * after speaking, or when [timeoutMs] passes with nothing said (sessions are re-opened until
     * then, so someone who answers a beat late is still heard). Transient recognizer errors
     * (busy, disconnected) are retried. Timing runs on our own clock, not the recognizer's events.
     */
    override suspend fun listen(timeoutMs: Long): String? {
        val committed = StringBuilder()
        var live = Captions()
        val startedAt = SystemClock.uptimeMillis()
        var lastWordAt = startedAt
        var retries = 0
        _captions.value = Captions()
        fun said() = committed.isNotEmpty() || live.heard.isNotBlank() || live.pending.isNotBlank()
        fun publish(c: Captions) {
            live = c
            _captions.value = c.copy(heard = listOf(committed.toString(), c.heard).filter { it.isNotBlank() }.joinToString(" "))
        }
        fun commitLive() {
            val words = listOf(live.heard, live.pending).joinToString(" ").trim()
            if (words.isNotEmpty()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(words) }
            live = Captions()
        }
        try {
            session@ while (true) {
                var finished = false
                val ticks = flow { while (true) { delay(100); emit(Unit) } }
                merge(stt.listen(listenOptions.copy(biasingStrings = hints)), ticks).takeWhile { e ->
                    var more = true
                    when (e) {
                        Unit -> {
                            val now = SystemClock.uptimeMillis()
                            if (said() && now - lastWordAt > REQUEST_DONE_MS) { finished = true; more = false }
                            if (!said() && now - startedAt > timeoutMs) { finished = true; more = false }
                        }
                        SpeechEvent.Ready -> _state.value = VoiceState.LISTENING
                        SpeechEvent.SpeechStarted -> publish(live.copy(userSpeaking = true))
                        SpeechEvent.SpeechEnded -> publish(live.copy(userSpeaking = false))
                        is SpeechEvent.Level -> _level.value = e.value
                        is SpeechEvent.Partial -> {
                            lastWordAt = SystemClock.uptimeMillis()
                            publish(Captions(heard = e.stable, pending = e.unstable, userSpeaking = true))
                        }
                        is SpeechEvent.Final -> {
                            e.text.trim().takeIf { it.isNotEmpty() }?.let {
                                if (committed.isNotEmpty()) committed.append(' ')
                                committed.append(it)
                                lastWordAt = SystemClock.uptimeMillis()
                            }
                            publish(Captions())
                            more = false
                        }
                        is SpeechEvent.Error -> {
                            commitLive() // words shown but not finalised still count
                            publish(Captions())
                            when {
                                e.error == SpeechError.CANCELLED -> finished = true
                                e.error.isSilence || e.error.isLanguageMissing -> Unit // re-open until the timeout
                                e.error.isContention && retries++ < MAX_RETRIES -> Unit // busy / disconnected: retry
                                else -> { Log.w(TAG, "recognizer: ${e.error}"); finished = true }
                            }
                            more = false
                        }
                        else -> Unit
                    }
                    more
                }.collect {}
                if (finished) break
                // A session ended at a pause: done if they've been quiet long enough (or never spoke).
                val now = SystemClock.uptimeMillis()
                if (said() && now - lastWordAt > REQUEST_DONE_MS) break
                if (!said() && now - startedAt > timeoutMs) break
                if (retries > 0) delay(RETRY_DELAY_MS)
            }
        } finally {
            _level.value = 0f
            if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE
        }
        commitLive() // a sentence still being spoken when we stopped counts too
        val result = committed.toString().trim().takeIf { it.isNotEmpty() }
        _captions.value = if (result != null) Captions(heard = result, heardFinal = true) else Captions(notice = "Didn't catch that")
        Log.i(TAG, "heard [${stt.activeLanguage}]: $result")
        return result
    }

    // ---- speaking -------------------------------------------------------------------------

    override suspend fun speak(text: String) {
        if (text.isNotBlank()) speakStream(flowOf(text))
    }

    override suspend fun speakStream(deltas: Flow<String>): String {
        if (!voiceChosen) { voiceChosen = true; tts.selectVoice(); tts.rate = 1.05f }
        val reply = StringBuilder()
        _captions.value = _captions.value.copy(reply = "", spokenUpTo = -1, notice = "")
        val restoreVolume = tts.ensureAudible() // muted media would make the whole reply silent
        try {
            coroutineScope {
                // Sentences go to TTS in order while later deltas are still arriving.
                val sentences = Channel<Pair<Int, String>>(Channel.UNLIMITED)
                val speaker = launch { for ((start, sentence) in sentences) speakSentence(sentence, start) }
                var queued = 0
                deltas.collect { d ->
                    reply.append(d)
                    _captions.value = _captions.value.copy(reply = reply.toString())
                    while (true) {
                        val end = sentenceEnd(reply, queued) ?: break
                        sentences.send(queued to reply.substring(queued, end))
                        queued = end
                    }
                }
                if (queued < reply.length) sentences.send(queued to reply.substring(queued))
                sentences.close()
                speaker.join()
            }
        } finally {
            restoreVolume?.invoke()
            _captions.value = _captions.value.copy(spokenUpTo = -1)
            _state.value = VoiceState.IDLE
        }
        Log.i(TAG, "said: $reply")
        return reply.toString()
    }

    /** Speaks one sentence that starts at [offset] in the reply, moving the card's highlight with it. */
    private suspend fun speakSentence(sentence: String, offset: Int) {
        if (sentence.isBlank()) return
        _state.value = VoiceState.SPEAKING
        tts.speak(sentence, SpeakOptions(flush = false)).collect { e ->
            if (e is TtsEvent.Range) _captions.value = _captions.value.copy(spokenUpTo = offset + e.end)
        }
        _captions.value = _captions.value.copy(spokenUpTo = offset + sentence.length)
    }

    /** End of the first complete sentence after [from] ("6.30" doesn't split; long runs break at a comma). */
    private fun sentenceEnd(text: CharSequence, from: Int): Int? {
        for (i in from until text.length - 1) {
            if (text[i] in ".!?" && text[i + 1].isWhitespace()) return i + 1
            if (i - from > 160 && text[i] == ',' && text[i + 1].isWhitespace()) return i + 1
        }
        return null
    }

    /**
     * Speaks [question] and waits up to [ANSWER_TIMEOUT_MS] (3 min) for the answer. A question the
     * agent asked mid-task (OTP, which address, "should I go ahead?") often needs the user to look
     * something up; the 8 s default listen dropped those answers and ended the task.
     */
    override suspend fun ask(question: String): String? {
        speak(question)
        return listen(ANSWER_TIMEOUT_MS)
    }

    // ---- barge-in ----------------------------------------------------------------

    /** Safe from any thread: [GoogleSpeechRecognizer.cancel] hops to the main thread itself. */
    override fun stop() {
        tts.stop()
        stt.cancel()
        _level.value = 0f
        _state.value = VoiceState.IDLE
    }

    private companion object {
        const val TAG = "Voice"
        /** Quiet time after the last new word that ends a request. */
        const val REQUEST_DONE_MS = 1_300L
        /** How long a question waits for the user's answer. */
        const val ANSWER_TIMEOUT_MS = 180_000L
        /** Retries for transient recognizer errors (busy / disconnected), and the pause before each. */
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 600L
    }
}
