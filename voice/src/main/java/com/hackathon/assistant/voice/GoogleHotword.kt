package com.hackathon.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.time.LocalTime
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects "Jarvis". That's all it does: the request itself is taken by the same code as the mic
 * button (AndroidVoiceIO.listen on Google), so saying "Jarvis" = tapping the mic.
 *
 * How: our own mic stream is fed into Google's on-device recognizer (EXTRA_AUDIO_SOURCE), one
 * session after another. Feeding audio means the mic never restarts (no Google start sound every
 * few seconds, no gaps). Fed a stream, Google stops transcribing once an utterance is finished and
 * waits for the stream to end, so when words stop coming we close the stream and start a new
 * session with the audio buffered in between.
 *
 * On "Jarvis" it returns what followed it in the same breath ("what's the time"), or "" if the
 * user paused; the mic is fully released before returning.
 */
class GoogleHotword(
    private val context: Context,
    private val stt: GoogleSpeechRecognizer,
) {
    /** True from the moment "Jarvis" is heard until the app calls [sleep]: the card opens on this. */
    val awake = MutableStateFlow(false)
    /** Mic loudness while awake (before the request's listen takes over), for the card's waves. */
    val level = MutableStateFlow(0f)
    /** Words heard after "Jarvis" so far, for the card. */
    val partial = MutableStateFlow("")
    val pending = MutableStateFlow("")

    /** Extra words to favour (contact names). */
    @Volatile var hints: List<String> = emptyList()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val mic = MicFeed()
    private val othersRecording = MutableStateFlow(false)

    private val options = ListenOptions(
        partialResults = true, maxResults = 3,
        formatting = ListenOptions.Formatting.LATENCY, biasingStrings = BIAS,
        forceOnDevice = true,
    )

    fun sleep() {
        awake.value = false
        partial.value = ""; pending.value = ""; level.value = 0f
    }

    /**
     * Suspends until "Jarvis" is heard while [allowed]. Returns the words said after it in the same
     * breath ("" if none). The mic is released when this returns.
     */
    suspend fun awaitWake(allowed: StateFlow<Boolean>): String {
        val watcher = RecordingWatcher()
        audio.registerAudioRecordingCallback(watcher, Handler(Looper.getMainLooper()))
        // The callback only fires on changes: start from what is recording right now, not from a
        // value left over from the last wait.
        othersRecording.value = othersIn(audio.activeRecordingConfigurations)
        val gate = combine(allowed, othersRecording) { a, others -> a && !others }
        var backoff = 0L
        var busyRuns = 0
        var silentRuns = 0
        sleep()
        try {
            while (true) {
                if (!gate.first()) mic.stop() // give the mic back (Gboard, a call)
                gate.first { it }
                if (mic.dead) { trace("mic: read failed, reopening"); mic.stop(); delay(MIC_RETRY_MS) }
                mic.start()
                if (!mic.running) { delay(MIC_RETRY_MS); continue } // mic held elsewhere; try again shortly
                delay(if (backoff > 0) backoff else BETWEEN_SESSIONS_MS)
                when (val outcome = whileOpen(gate) { session() }) {
                    null -> backoff = 300
                    is Outcome.Woke -> return outcome.rest
                    is Outcome.Ended -> {
                        backoff = when {
                            outcome.contention -> (backoff * 2).coerceIn(400, 3_000)
                            // Missing language pack, no permission, audio error: retrying every 50 ms
                            // only drains the battery. Back off up to 10 s.
                            outcome.hardError -> (backoff * 2).coerceIn(500, 10_000)
                            else -> 0
                        }
                        // Self-heal: the service answering BUSY over and over means a stale session is
                        // holding it; the hotword would be deaf forever. Rebind after ~15 s of that.
                        busyRuns = if (outcome.contention) busyRuns + 1 else 0
                        silentRuns = if (outcome.silent) silentRuns + 1 else 0
                        if (silentRuns >= SILENT_RESET_AFTER) {
                            trace("recognizer: silent (no events), rebinding the speech service")
                            stt.reset(); silentRuns = 0; backoff = 1_000
                        }
                        if (busyRuns >= BUSY_RESET_AFTER) {
                            trace("recognizer: stuck BUSY, rebinding the speech service")
                            stt.reset(); busyRuns = 0; backoff = 1_000
                        }
                    }
                }
            }
        } finally {
            audio.unregisterAudioRecordingCallback(watcher)
            mic.stop()
        }
    }

    private object Tick

    private sealed interface Outcome {
        /** "Jarvis" was heard; [rest] = the words after it in the same breath. */
        data class Woke(val rest: String) : Outcome
        data class Ended(val contention: Boolean, val hardError: Boolean = false, val silent: Boolean = false) : Outcome
    }

    /**
     * One recognizer session fed our audio. When "Jarvis" appears the card opens at once; the
     * session then runs until the words stop (so a request said in the same breath is kept).
     */
    private suspend fun session(): Outcome {
        val pipe = mic.openSession()
        var woke = false
        var wokeAt = 0L
        var rest = ""
        var lastWordsAt = 0L
        var closedAt = 0L
        var cancelled = false
        val startedAt = SystemClock.uptimeMillis()
        var outcome: Outcome = Outcome.Ended(false)
        var anyEvent = false // the recognizer said anything at all (words, final, error)
        val ticks = flow { while (true) { delay(100); emit(Tick) } }
        try {
            merge(stt.listen(options.copy(audioSource = pipe, biasingStrings = options.biasingStrings + hints)), ticks).takeWhile { e ->
                var more = true
                when (e) {
                    is SpeechEvent.Partial -> {
                        anyEvent = true
                        lastWordsAt = SystemClock.uptimeMillis()
                        val after = afterWakeWord(e.text, loose = true)
                        if (after != null) {
                            if (!woke) { woke = true; wokeAt = SystemClock.uptimeMillis(); awake.value = true; trace("wake: \"${e.text}\"") }
                            rest = after
                            partial.value = afterWakeWord(e.stable, loose = true).orEmpty()
                            pending.value = after.removePrefix(partial.value).trim()
                        }
                    }
                    is SpeechEvent.Final -> {
                        anyEvent = true
                        val woken = e.alternatives.firstNotNullOfOrNull { afterWakeWord(it.text, loose = true) }
                        if (woken != null) {
                            if (!woke) { woke = true; awake.value = true; trace("wake (final): \"${e.text}\"") }
                            rest = woken
                        }
                        more = false
                    }
                    is SpeechEvent.Error -> {
                        anyEvent = true
                        val normal = e.error.isSilence || e.error == SpeechError.CANCELLED || e.error == SpeechError.CLIENT
                        if (!normal) trace("recognizer: ${e.error}")
                        val contention = e.error.isContention && e.error != SpeechError.CLIENT
                        // CANCELLED = the user closed the card (stop): not a wake, even mid-request.
                        cancelled = e.error == SpeechError.CANCELLED
                        if (!woke || cancelled) outcome = Outcome.Ended(contention, hardError = !normal && !contention)
                        more = false
                    }
                    Tick -> {
                        val now = SystemClock.uptimeMillis()
                        if (woke) level.value = mic.level
                        // A session that never produces a single event: the speech service has gone
                        // silent (seen after reinstalls). End it so the loop can rebind the service.
                        if (!anyEvent && now - startedAt > SILENT_SESSION_MS) { outcome = Outcome.Ended(contention = false, silent = true); more = false }
                        // Nothing after "Jarvis" within SAME_BREATH_WAIT_MS: they paused, so hand
                        // over to the normal listen right away instead of waiting for the utterance to end.
                        if (woke && rest.isBlank() && now - wokeAt > SAME_BREATH_WAIT_MS) more = false
                        // Words stopped: the recognizer has finished this utterance. Close the
                        // stream so it returns the final text (and, if idle, a new session starts).
                        if (closedAt == 0L && lastWordsAt > 0 && now - lastWordsAt > UTTERANCE_IDLE_MS) { closedAt = now; mic.endSession() }
                        if (closedAt > 0 && now - closedAt > FINAL_WAIT_MS) more = false
                        // A session with no end in sight (noise that never became words, a silently
                        // dead service): close it so a fresh one starts.
                        if (!woke && closedAt == 0L && now - startedAt > MAX_SESSION_MS) { closedAt = now; mic.endSession() }
                    }
                    else -> Unit
                }
                more
            }.collect {}
        } finally {
            mic.endSession()
            runCatching { pipe.close() }
        }
        if (cancelled) { sleep(); return outcome }
        if (woke) {
            val request = rest.trim().trimStart(',', '.', '!', '?').trim()
            trace(if (request.isEmpty()) "wake: listening for the request" else "wake: request in the same breath: \"$request\"")
            return Outcome.Woke(request)
        }
        return outcome
    }

    /** Runs [block] while [gate] stays open; if it closes, cancels [block] (freeing the recognizer) and returns null. */
    private suspend fun <T> whileOpen(gate: Flow<Boolean>, block: suspend () -> T): T? = coroutineScope {
        var closed = false
        val work = async { block() }
        val watch = launch { gate.first { !it }; closed = true; work.cancel() }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (closed) null else throw e
        } finally {
            watch.cancel()
        }
    }

    /** Anything recording besides our own mic stream belongs to someone else: Gboard, a call, the camera. */
    private inner class RecordingWatcher : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val others = othersIn(configs)
            if (others != othersRecording.value) trace(if (others) "mic: another app is recording, pausing" else "mic: free again")
            othersRecording.value = others
        }
    }

    private fun othersIn(configs: List<AudioRecordingConfiguration>) =
        configs.any { !it.isClientSilenced && it.clientAudioSessionId != mic.sessionId }

    /**
     * One AudioRecord (16 kHz mono PCM16) feeding the current recognizer session through a pipe.
     * Between sessions frames are kept (up to 3 s) and written first into the next one.
     */
    private inner class MicFeed {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        @Volatile var sessionId = -1
            private set
        /** 0..1 loudness of the latest 20 ms. */
        @Volatile var level = 0f
            private set
        private val backlog = ArrayDeque<ByteArray>()
        @Volatile private var out: OutputStream? = null
        /** The AudioRecord failed (audioserver restart, lost the mic): reopen it. */
        @Volatile var dead = false
            private set
        val running: Boolean get() = record != null

        @SuppressLint("MissingPermission")
        @Synchronized
        fun start() {
            if (record != null) return
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 640 * 8))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); trace("mic unavailable"); return }
            runCatching { r.startRecording() }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); trace("mic busy"); return }
            record = r
            sessionId = r.audioSessionId
            dead = false
            thread = Thread({
                val buf = ByteArray(640) // 20 ms
                while (record === r) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) { dead = true; break } // ERROR_DEAD_OBJECT / INVALID_OPERATION: never spin
                    if (n == 0) { Thread.sleep(10); continue }
                    val chunk = buf.copyOf(n)
                    level = rms(chunk)
                    // No live session (or its buffered audio is still being written): keep the frame.
                    val o = synchronized(backlog) {
                        out ?: run { backlog.addLast(chunk); while (backlog.size > 150) backlog.removeFirst(); null }
                    }
                    if (o != null && runCatching { o.write(chunk) }.isFailure) synchronized(backlog) { backlog.addLast(chunk) }
                }
            }, "jarvis-mic").also { it.start() }
        }

        /** Stops and releases the mic completely (so Google's own listen can open it). */
        @Synchronized
        fun stop() {
            val r = record ?: return
            record = null
            endSession()
            thread?.join(500)
            thread = null
            runCatching { r.stop() }
            r.release()
            sessionId = -1
            level = 0f
            synchronized(backlog) { backlog.clear() }
        }

        /** A pipe for the next recognizer session, pre-filled with what was said since the last one. */
        fun openSession(): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            val o = ParcelFileDescriptor.AutoCloseOutputStream(write)
            // Drain the buffered audio first (on its own thread: the pipe blocks until the recognizer
            // reads), then hand over to live frames, so the audio stays in order.
            Thread {
                runCatching {
                    while (true) {
                        val chunk = synchronized(backlog) { if (backlog.isEmpty()) { out = o; null } else backlog.removeFirst() } ?: break
                        o.write(chunk)
                    }
                }
            }.start()
            return read
        }

        fun endSession() {
            val o = out ?: return
            out = null
            runCatching { o.close() }
        }

        private fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < pcm.size) { val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble(); sum += v * v; i += 2 }
            val db = 20 * log10(sqrt(sum / (pcm.size / 2).coerceAtLeast(1)) / 32768.0 + 1e-9)
            return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
        }
    }

    /** vivo mutes app logcat, so activity also goes to files/jarvis.log (adb shell run-as ... cat). */
    fun trace(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val f = File(context.filesDir, "jarvis.log")
            if (f.length() > 512_000) f.delete()
            f.appendText("${LocalTime.now()} $msg\n")
        }
    }

    companion object {
        private const val TAG = "Hotword"
        private const val BETWEEN_SESSIONS_MS = 50L
        /** Pause before reopening a mic that failed or was busy. */
        private const val MIC_RETRY_MS = 1_000L
        /** Longest a session may run without "Jarvis" before it is recycled. */
        private const val MAX_SESSION_MS = 30_000L
        /** Consecutive BUSY/contention sessions before the recognizer is rebound. */
        private const val BUSY_RESET_AFTER = 5
        /** A session with no recognizer events for this long counts as the service having gone silent. */
        private const val SILENT_SESSION_MS = 20_000L
        /** Silent sessions in a row before the recognizer is rebound. */
        private const val SILENT_RESET_AFTER = 2
        /** After "Jarvis", how long to wait for more words before handing over to the normal listen. */
        private const val SAME_BREATH_WAIT_MS = 400L
        /** No new words for this long: the recognizer has finished the utterance; close its stream. */
        private const val UTTERANCE_IDLE_MS = 700L
        /** After closing the stream, how long to wait for the final text. */
        private const val FINAL_WAIT_MS = 1_500L

        /**
         * The names the assistant answers to: "Jarvis" (main) and "Friday" (for team phones in one
         * room, so saying one phone's name doesn't wake the others). Both always work.
         */
        val WAKE_WORD = WakeWord.JARVIS
        val WAKE_WORDS = listOf(WakeWord.JARVIS, WakeWord.FRIDAY)
        private val ALL_NAMES = WAKE_WORDS.joinToString("|") { it.names }
        private val ALL_PREFIXES = WAKE_WORDS.joinToString("|") { it.prefixes }
        val BIAS = WAKE_WORDS.flatMap { it.bias }

        private val WAKE = Regex("\\b(hey |hi |hello |ok |okay )?($ALL_NAMES)\\b[,.!?]*", RegexOption.IGNORE_CASE)
        /** Tentative text may still be mid-word: a prefix like "jarv…" / "frid…" is already unmistakable. */
        private val WAKE_LOOSE = Regex("\\b(hey |hi |hello |ok |okay )?($ALL_PREFIXES|$ALL_NAMES)\\b[,.!?]*", RegexOption.IGNORE_CASE)

        /** Text after the wake word, or null if it isn't there. A repeated wake word ("Hey Friday. Hey Friday.") isn't the request. */
        fun afterWakeWord(text: String, loose: Boolean = false): String? {
            val re = if (loose) WAKE_LOOSE else WAKE
            val m = re.find(text) ?: return null
            var rest = text.substring(m.range.last + 1).trim()
            while (true) {
                val again = re.find(rest)?.takeIf { it.range.first == 0 } ?: break
                rest = rest.substring(again.range.last + 1).trim()
            }
            return rest
        }
    }
}

/** Wake-word spellings the recognizer produces for each name, plus mid-word prefixes and bias strings. */
enum class WakeWord(val names: String, val prefixes: String, val bias: List<String>) {
    JARVIS(
        "jarvis|jarvis's|jervis|javis|jarvees|charvis|jarves|jarwis|jarbis|jarvi|garvis|jarvus|jarvix",
        "jarv\\w*|jarb\\w*",
        listOf("Jarvis", "Hey Jarvis", "Jarbis"),
    ),
    FRIDAY(
        "friday|friday's|fri day|fryday|frieday|fraiday|phriday",
        "frida\\w*|fryd\\w*",
        listOf("Friday", "Hey Friday"),
    ),
}
