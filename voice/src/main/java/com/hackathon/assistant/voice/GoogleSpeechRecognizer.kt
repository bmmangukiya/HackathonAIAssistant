package com.hackathon.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Typed wrapper over Android's [SpeechRecognizer], preferring Google's on-device recognizer
 * (Android System Intelligence, the model behind Gboard voice typing). Every option the platform
 * offers is in [ListenOptions]; features newer than the running OS are skipped quietly.
 *
 * Sessions never overlap (Android System Intelligence serves one on-device session at a time).
 * One recognizer per mode is reused for the app's lifetime: destroying and recreating it
 * disconnects the shared service (ERROR_SERVER_DISCONNECTED, then ERROR_RECOGNIZER_BUSY). Results
 * that arrive before a new session is ready (late callbacks of a cancelled one) are ignored.
 */
class GoogleSpeechRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    /** Separate on-device instance, for sessions that must run on the phone (e.g. fed our own audio). */
    private var deviceRecognizer: SpeechRecognizer? = null
    /** The live session's recognizer, for [cancel]. */
    @Volatile private var current: SpeechRecognizer? = null
    /** Ends the live session's flow; the platform sends no callback after cancel(). */
    @Volatile private var endCurrent: (() -> Unit)? = null
    private val session = Mutex()

    /** Languages that failed as "not installed" this run; skipped until the app restarts. */
    private val unavailable = mutableSetOf<String>()

    /** True if Google's on-device model is present (Android 12+). */
    val isOnDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * true: Google's on-device model only (private, works offline). false: Google's speech service
     * may use its online recognizer when there is internet (Gboard-level accuracy) and falls back
     * to on-device offline.
     */
    @Volatile var onDeviceOnly: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            // The next session binds the other recognizer service.
            recognizer?.let { r -> context.mainExecutor.execute { r.destroy() } }
            recognizer = null
        }

    /**
     * Drops both cached recognizers so the next session binds the speech service afresh. Used when
     * the service keeps answering BUSY (a session from a killed/restarted client is still held).
     */
    fun reset() {
        val stale = listOfNotNull(recognizer, deviceRecognizer)
        recognizer = null; deviceRecognizer = null
        context.mainExecutor.execute { stale.forEach { runCatching { it.cancel(); it.destroy() } } }
    }

    /** Language the last successful session used. */
    @Volatile var activeLanguage: String? = null
        private set

    /**
     * One recognition session. Emits [SpeechEvent]s and completes after [SpeechEvent.Final] or
     * [SpeechEvent.Error]. Tries [ListenOptions.language] then each fallback; a language whose
     * offline pack is missing is skipped (and its download requested). Cancel the collector to stop.
     */
    fun listen(options: ListenOptions = ListenOptions()): Flow<SpeechEvent> = flow {
        session.withLock {
            val languages = (listOf(options.language) + options.fallbackLanguages).distinct()
            val candidates = languages.filter { it !in unavailable }.ifEmpty { languages.takeLast(1) }
            for ((i, lang) in candidates.withIndex()) {
                var missing = false
                oneSession(options, lang).collect { e ->
                    val last = i == candidates.lastIndex
                    if (e is SpeechEvent.Error && e.error.isLanguageMissing && !last) {
                        missing = true
                        unavailable += lang
                        Log.w(TAG, "$lang has no offline pack; requesting it and trying the next language")
                        requestDownload(lang)
                    } else {
                        if (e is SpeechEvent.Final) activeLanguage = lang
                        emit(e)
                    }
                }
                if (!missing) return@withLock
            }
        }
    }.flowOn(Dispatchers.Main)

    private fun oneSession(options: ListenOptions, lang: String): Flow<SpeechEvent> = callbackFlow {
        val onDevice = (options.forceOnDevice || onDeviceOnly) && isOnDeviceAvailable
        val r = if (onDevice) deviceRecognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { deviceRecognizer = it }
        else recognizer ?: create().also { recognizer = it }
        current = r
        var finished = false
        var ready = false
        var lastPartial = ""
        fun end(e: SpeechEvent) {
            if (finished) return
            finished = true
            trySend(e)
            close()
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready = true; trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.SpeechStarted) }
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { trySend(SpeechEvent.SpeechEnded) }
            override fun onError(error: Int) = end(SpeechEvent.Error(SpeechError.of(error)))
            // Results before onReadyForSpeech belong to a previous, cancelled session.
            // Some sessions (e.g. fed our own audio) return an empty final after full partials: keep the words.
            override fun onResults(results: Bundle?) {
                if (!ready) return
                val alts = alternatives(results).filter { it.text.isNotBlank() }
                end(SpeechEvent.Final(alts.ifEmpty { listOfNotNull(lastPartial.takeIf { it.isNotBlank() }?.let { Alternative(it, null) }) }))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!ready || partialResults == null) return
                val stable = alternatives(partialResults).firstOrNull()?.text.orEmpty().trim()
                val unstable = partialResults.getStringArrayList(UNSTABLE_TEXT)?.firstOrNull().orEmpty().trim()
                if (stable.isNotEmpty() || unstable.isNotEmpty()) {
                    val p = SpeechEvent.Partial(stable, unstable)
                    lastPartial = p.text
                    trySend(p)
                }
            }
            override fun onSegmentResults(segmentResults: Bundle) { trySend(SpeechEvent.Segment(alternatives(segmentResults))) }
            override fun onEndOfSegmentedSession() = end(SpeechEvent.Final(emptyList()))
            override fun onLanguageDetection(results: Bundle) {
                val detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: return
                trySend(SpeechEvent.LanguageDetected(detected, results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        endCurrent = { end(SpeechEvent.Error(SpeechError.CANCELLED)) }
        r.startListening(options.copy(preferOffline = onDevice && options.preferOffline).toIntent(lang))
        awaitClose {
            if (!finished) r.cancel()
            if (current === r) { current = null; endCurrent = null }
        }
    }

    /** Offline language packs: installed, downloading, and downloadable (Android 13+). */
    suspend fun languageSupport(language: String = "en-IN"): LanguageSupport? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) return@withContext null
        val r = create()
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { r.destroy() }
            r.checkRecognitionSupport(ListenOptions(language = language).toIntent(language), mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    r.destroy()
                    if (cont.isActive) cont.resume(LanguageSupport(s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages, s.onlineLanguages))
                }
                override fun onError(error: Int) { r.destroy(); if (cont.isActive) cont.resume(null) }
            })
        }
    }

    /** Asks Android System Intelligence to download the offline pack for [language] (Android 13+). */
    fun requestDownload(language: String, onDone: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 33 || !isOnDeviceAvailable) return
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = ListenOptions(language = language).toIntent(language)
            if (Build.VERSION.SDK_INT >= 34 && onDone != null) {
                r.triggerModelDownload(intent, mainExecutor, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = Unit
                    override fun onSuccess() { onDone(true); r.destroy() }
                    override fun onScheduled() { onDone(false); r.destroy() }
                    override fun onError(error: Int) { onDone(false); r.destroy() }
                })
            } else {
                r.triggerModelDownload(intent)
                r.destroy() // fire-and-forget: nothing will call back, so don't keep the binding
            }
            Log.i(TAG, "requested offline speech pack for $language")
        }.onFailure { Log.w(TAG, "download request for $language failed", it) }
    }

    /** Stops the current session and hands the on-device recognizer back (e.g. to Gboard). */
    fun cancel() {
        val r = current ?: return
        val end = endCurrent
        context.mainExecutor.execute { r.cancel(); end?.invoke() }
    }

    private fun create(): SpeechRecognizer =
        if (onDeviceOnly && isOnDeviceAvailable) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context) // the system's default service (Speech Services by Google)

    private val mainExecutor: Executor get() = context.mainExecutor

    private fun alternatives(b: Bundle?): List<Alternative> {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val scores = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return texts.mapIndexed { i, t -> Alternative(t, scores?.getOrNull(i)?.takeIf { it >= 0f }) }
    }

    private companion object {
        const val TAG = "GoogleStt"
        /** Extra that Google's recognizers add to partial results: the not-yet-stable tail. */
        const val UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"
    }
}

/** Everything [SpeechRecognizer] can be told. Newer-than-OS options are ignored on older phones. */
data class ListenOptions(
    val language: String = "en-IN",
    val fallbackLanguages: List<String> = listOf("en-US"),
    val preferOffline: Boolean = true,
    val partialResults: Boolean = true,
    val maxResults: Int = 3,
    /** Silence that ends the utterance. */
    val completeSilenceMs: Long? = 1_200,
    val possiblyCompleteSilenceMs: Long? = null,
    val minimumLengthMs: Long? = null,
    /** Words to favour (contact names, app names). Android 13+. */
    val biasingStrings: List<String> = emptyList(),
    /** Punctuation and capitalisation. Android 13+. */
    val formatting: Formatting? = Formatting.QUALITY,
    val hidePartialTrailingPunctuation: Boolean = true,
    val maskOffensiveWords: Boolean = false,
    /** Keep one session open and get [SpeechEvent.Segment] per pause. Android 13+. */
    val segmentedSession: Boolean = false,
    /**
     * Feed our own 16 kHz mono PCM16 audio instead of letting the recognizer open the mic
     * (Android 13+): lets us keep the mic, pre-roll the words said just after the wake word, and
     * share audio with other models.
     */
    val audioSource: android.os.ParcelFileDescriptor? = null,
    /** Run on Google's on-device model even in online mode (the online service stalls on fed audio). */
    val forceOnDevice: Boolean = false,
    /** Detect which of these languages is spoken. Android 14+. */
    val detectLanguages: List<String> = emptyList(),
    /** Switch the model when a detected language changes mid-session. Android 14+. */
    val languageSwitch: Boolean = false,
) {
    enum class Formatting { QUALITY, LATENCY }

    fun toIntent(lang: String = language) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
        completeSilenceMs?.let { putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it) }
        possiblyCompleteSilenceMs?.let { putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it) }
        minimumLengthMs?.let { putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, it) }
        if (Build.VERSION.SDK_INT >= 33) {
            if (biasingStrings.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingStrings))
            formatting?.let {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    if (it == Formatting.QUALITY) RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY else RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            }
            putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, hidePartialTrailingPunctuation)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, maskOffensiveWords)
            if (segmentedSession) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            audioSource?.let {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, it)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            if (detectLanguages.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(detectLanguages))
            }
            if (languageSwitch) putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        }
    }
}

data class Alternative(val text: String, val confidence: Float?)

data class LanguageSupport(val installed: List<String>, val downloading: List<String>, val downloadable: List<String>, val online: List<String>)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Mic loudness, 0..1. */
    data class Level(val value: Float) : SpeechEvent
    /**
     * [stable]: words the recognizer is confident about (they arrive in bursts). [unstable]: its
     * current guess for the words after them, updated word by word as the user speaks (Google's
     * recognizers put this in "android.speech.extra.UNSTABLE_TEXT"). Show both, like Gboard.
     */
    data class Partial(val stable: String, val unstable: String = "") : SpeechEvent {
        val text: String get() = listOf(stable, unstable).filter { it.isNotBlank() }.joinToString(" ").trim()
    }
    data class Segment(val alternatives: List<Alternative>) : SpeechEvent
    data class LanguageDetected(val language: String, val confidence: Int) : SpeechEvent
    data object SpeechEnded : SpeechEvent
    /** Best guess first. Empty if nothing was recognised. */
    data class Final(val alternatives: List<Alternative>) : SpeechEvent {
        val text: String get() = alternatives.firstOrNull()?.text.orEmpty()
    }
    data class Error(val error: SpeechError) : SpeechEvent
}

enum class SpeechError(val code: Int) {
    NETWORK_TIMEOUT(1), NETWORK(2), AUDIO(3), SERVER(4), CLIENT(5), SPEECH_TIMEOUT(6), NO_MATCH(7),
    BUSY(8), NO_PERMISSION(9), TOO_MANY_REQUESTS(10), SERVER_DISCONNECTED(11),
    LANGUAGE_NOT_SUPPORTED(12), LANGUAGE_UNAVAILABLE(13), CANNOT_CHECK_SUPPORT(14), UNKNOWN(-1),
    /** Our own cancel() (no platform code). */
    CANCELLED(-2);

    /** Heard nothing (silence or no words): not a failure. */
    val isSilence get() = this == SPEECH_TIMEOUT || this == NO_MATCH
    val isLanguageMissing get() = this == LANGUAGE_UNAVAILABLE || this == LANGUAGE_NOT_SUPPORTED
    /** The recognizer service is busy or restarting: back off before retrying. */
    val isContention get() = this == BUSY || this == TOO_MANY_REQUESTS || this == SERVER_DISCONNECTED || this == CLIENT || this == SERVER

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
