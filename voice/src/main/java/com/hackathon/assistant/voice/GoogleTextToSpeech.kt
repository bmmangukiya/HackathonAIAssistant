package com.hackathon.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Typed wrapper over Android [TextToSpeech], bound to Google's engine with an offline voice.
 * Speaking is a [Flow] of [TtsEvent]s, including the word being spoken ([TtsEvent.Range]) so the
 * card can follow along, and other audio is ducked while Jarvis talks.
 */
class GoogleTextToSpeech(
    private val context: Context,
    private val engine: String? = GOOGLE_ENGINE,
) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listeners = mutableMapOf<String, UtteranceProgressListener>()

    var rate: Float = 1.0f
        set(v) { field = v; tts?.setSpeechRate(v) }
    var pitch: Float = 1.0f
        set(v) { field = v; tts?.setPitch(v) }

    /** Voices on the phone; [offlineOnly] drops ones that need a network. */
    suspend fun voices(offlineOnly: Boolean = true, language: String? = "en"): List<VoiceInfo> =
        engine()?.voices.orEmpty()
            .filter { (!offlineOnly || !it.isNetworkConnectionRequired) && (language == null || it.locale.language == language) }
            .map { VoiceInfo(it.name, it.locale, it.quality, it.latency, !it.isNetworkConnectionRequired, it.features.orEmpty()) }
            .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { it.latency })

    val currentVoice: String? get() = tts?.voice?.name

    /** Picks the best voice: an exact [name], else the highest-quality offline voice for the first matching locale. */
    suspend fun selectVoice(name: String? = null, locales: List<Locale> = listOf(Locale.forLanguageTag("en-IN"), Locale.US, Locale.UK)): String? {
        val t = engine() ?: return null
        val all = t.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        val pick = name?.let { n -> all.firstOrNull { it.name == n } }
            ?: locales.firstNotNullOfOrNull { loc ->
                all.filter { it.locale.language == loc.language && it.locale.country == loc.country }
                    .maxWithOrNull(compareBy<Voice> { it.quality }.thenByDescending { it.latency })
            }
        if (pick != null) t.voice = pick else t.language = locales.first()
        Log.i(TAG, "voice: ${t.voice?.name}")
        return t.voice?.name
    }

    fun isLanguageAvailable(locale: Locale): Boolean =
        (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    val engines: List<String> get() = tts?.engines.orEmpty().map { it.name }
    val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    /**
     * Speaks [text] and completes when it has been heard (or stopped). [SpeakOptions.flush]
     * replaces anything queued; otherwise it plays after the current utterance.
     */
    fun speak(text: String, options: SpeakOptions = SpeakOptions()): Flow<TtsEvent> = callbackFlow {
        val t = engine() ?: run { trySend(TtsEvent.Error("TTS engine unavailable")); close(); return@callbackFlow }
        val id = UUID.randomUUID().toString()
        val focus = if (options.duckOthers) requestFocus() else null
        // Callbacks arrive on the engine's binder thread, the watchdog on Main.
        val finished = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        fun end(e: TtsEvent) { if (finished.compareAndSet(false, true)) { trySend(e); close() } }
        listeners[id] = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { started.set(true); trySend(TtsEvent.Started) }
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) { trySend(TtsEvent.Range(start, end)) }
            override fun onDone(utteranceId: String) = end(TtsEvent.Done)
            override fun onStop(utteranceId: String, interrupted: Boolean) = end(TtsEvent.Stopped)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = end(TtsEvent.Error("synthesis failed"))
            override fun onError(utteranceId: String, errorCode: Int) = end(TtsEvent.Error("synthesis failed ($errorCode)"))
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, options.volume)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, options.pan)
        }
        val said = text.take(maxInputLength)
        if (t.speak(said, if (options.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
            // The engine's process died or was updated: rebind on the next call instead of reusing it.
            Log.w(TAG, "speak() refused; rebinding the TTS engine")
            resetEngine()
            end(TtsEvent.Error("TTS engine not bound"))
        }
        // No callbacks at all (the engine died mid-utterance) must not hang the reply forever.
        val watchdog = launch {
            delay(START_TIMEOUT_MS)
            if (!started.get() && !finished.get()) {
                Log.w(TAG, "utterance never started; rebinding the TTS engine")
                resetEngine()
                end(TtsEvent.Error("TTS did not start"))
                return@launch
            }
            delay(said.length * MS_PER_CHAR + END_SLACK_MS)
            if (!finished.get()) { Log.w(TAG, "utterance never finished"); end(TtsEvent.Error("TTS did not finish")) }
        }
        awaitClose {
            watchdog.cancel()
            // Collector cancelled mid-sentence (barge-in, ✕): cut the audio too.
            if (!finished.get()) t.stop()
            listeners.remove(id)
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
    }.flowOn(Dispatchers.Main)

    /** Renders [text] to a WAV file, e.g. for caching fixed phrases. */
    suspend fun synthesizeToFile(text: String, file: File): Boolean {
        val t = engine() ?: return false
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            listeners[id] = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) { listeners.remove(id); if (cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { listeners.remove(id); if (cont.isActive) cont.resume(false) }
            }
            if (t.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) { listeners.remove(id); cont.resume(false) }
        }
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = null
    }

    /** Drops a dead engine so [engine] binds a fresh one. Main thread (the flow runs there). */
    private fun resetEngine() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
    }

    /** Binds the engine once (preferring Google's) and routes every utterance's callbacks by id. */
    private suspend fun engine(): TextToSpeech? {
        val r = ready ?: withContext(Dispatchers.Main) {
            ready ?: CompletableDeferred<Boolean>().also { d ->
                ready = d
                tts = TextToSpeech(context, { d.complete(it == TextToSpeech.SUCCESS) }, engine).apply {
                    setAudioAttributes(ATTRIBUTES)
                    setOnUtteranceProgressListener(Router())
                }
            }
        }
        if (!r.await()) { Log.e(TAG, "TTS engine ${engine ?: "default"} failed to start"); return null }
        return tts?.also { it.setSpeechRate(rate); it.setPitch(pitch) }
    }

    private inner class Router : UtteranceProgressListener() {
        override fun onStart(id: String) { listeners[id]?.onStart(id) }
        override fun onDone(id: String) { listeners[id]?.onDone(id) }
        override fun onStop(id: String, interrupted: Boolean) { listeners[id]?.onStop(id, interrupted) }
        override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) { listeners[id]?.onRangeStart(id, start, end, frame) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String) { @Suppress("DEPRECATION") listeners[id]?.onError(id) }
        override fun onError(id: String, errorCode: Int) { listeners[id]?.onError(id, errorCode) }
    }

    /**
     * Jarvis speaks on media volume. If that is muted or at zero, the reply would be silent, so
     * raise it to [MIN_AUDIBLE] of max for a whole reply; returns how to put it back (null if
     * nothing was changed). Call once per reply, not per sentence.
     */
    fun ensureAudible(): (() -> Unit)? {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        val muted = audio.isStreamMute(stream)
        if (!muted && before > 0) return null
        val target = (max * MIN_AUDIBLE).toInt().coerceAtLeast(1)
        runCatching {
            if (muted) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(stream, target, 0)
        }.onFailure { Log.w(TAG, "can't raise media volume", it); return null }
        Log.i(TAG, "media volume was ${if (muted) "muted" else "0"}; speaking at $target/$max")
        return {
            runCatching {
                audio.setStreamVolume(stream, before, 0)
                if (muted) audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            }
        }
    }

    /** Lowers music/video while speaking instead of pausing it. */
    private fun requestFocus(): AudioFocusRequest? {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRIBUTES).build()
        return req.takeIf { audio.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    }

    companion object {
        private const val TAG = "GoogleTts"
        const val GOOGLE_ENGINE = "com.google.android.tts"
        /** Volume used when media is muted/at zero, as a fraction of max. */
        private const val MIN_AUDIBLE = 0.35f
        /** An utterance that hasn't started after this is lost (queued sentences start within ~1 s). */
        private const val START_TIMEOUT_MS = 8_000L
        /** Generous speaking time per character (~15 chars/s) plus slack, before giving up. */
        private const val MS_PER_CHAR = 120L
        private const val END_SLACK_MS = 5_000L
        // Media volume, not USAGE_ASSISTANT: OriginOS keeps the assistant stream near 1/15 on the
        // speaker with no slider for it, so replies were practically silent. The volume keys control media.
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

data class SpeakOptions(val flush: Boolean = true, val volume: Float = 1f, val pan: Float = 0f, val duckOthers: Boolean = true)

data class VoiceInfo(val name: String, val locale: Locale, val quality: Int, val latency: Int, val offline: Boolean, val features: Set<String>)

sealed interface TtsEvent {
    data object Started : TtsEvent
    /** Characters [start, end) of the text are being spoken now. */
    data class Range(val start: Int, val end: Int) : TtsEvent
    data object Done : TtsEvent
    data object Stopped : TtsEvent
    data class Error(val message: String) : TtsEvent
}
