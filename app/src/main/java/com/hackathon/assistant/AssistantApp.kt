package com.hackathon.assistant

import android.app.Application
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.UiController
import com.hackathon.assistant.llm.LiteRtLlm
import com.hackathon.assistant.llm.LlmPlanner
import com.hackathon.assistant.peers.PeerLink
import com.hackathon.assistant.peers.PeerSkills
import com.hackathon.assistant.perception.AccessibilityScreenReader
import com.hackathon.assistant.perception.AccessibilityUiController
import com.hackathon.assistant.perception.AssistantAccessibilityService
import com.hackathon.assistant.ui.AssistantOverlay
import com.hackathon.assistant.voice.AndroidVoiceIO
import com.hackathon.assistant.voice.GoogleHotword
import com.hackathon.assistant.voice.JarvisVoice
import com.hackathon.assistant.voice.TaskTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manual DI: the single place where modules are wired together.
 *
 * Jarvis flow: GoogleHotword ("Jarvis" on Google's on-device recognizer) or a tap on the card /
 *              accessibility button → Jarvis card → converse() → the on-device agent (LiteRT-LM)
 *              → Google TTS → back to listening for "Jarvis".
 */
class AssistantApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Steps of the running task: written by the agent, shown by the card. */
    val tasks = TaskTracker()

    /** Google speech-to-text and TTS, plus the "Jarvis" hotword and the task steps, for the card. */
    val voice: JarvisVoice by lazy {
        val google = AndroidVoiceIO(this)
        JarvisVoice(google, GoogleHotword(this, google.stt), tasks, scope)
    }
    private val hotword get() = voice.hotword

    /** The hotword listens only when enabled, not in a call, and no keyboard is up (so Gboard voice typing works). */
    val jarvisEnabled = MutableStateFlow(true)
    private val gate by lazy {
        HotwordGate(this, jarvisEnabled, AssistantAccessibilityService.keyboardVisible) {
            AssistantAccessibilityService.instance?.checkKeyboard() ?: run { AssistantAccessibilityService.keyboardVisible.value = false }
        }
    }
    private val wakeAllowed by lazy {
        gate.allowed(scope).also {
            scope.launch { gate.reason.collect { r -> hotword.trace(if (r.isEmpty()) "gate: open" else "gate: closed ($r)") } }
        }
    }
    private val overlay by lazy { AssistantOverlay(this, voice, onMic = ::onTrigger, onClose = ::stopAll) }

    val llm by lazy {
        LiteRtLlm(this, getSharedPreferences("assistant", MODE_PRIVATE).getString("model", null) ?: LiteRtLlm.DEFAULT_MODEL)
    }
    /** Screen control; while the agent's gesture touches the app, the card lets it through. */
    private val ui: UiController by lazy {
        AccessibilityUiController(aroundGesture = { dispatch -> overlay.letTouchesThrough { dispatch() } })
    }
    /** A timed-out step interrupts the model so the agent loop never hangs on a stuck generation. */
    val planner by lazy { LlmPlanner(llm, onTimeout = llm::cancel) }
    /** Phone-to-phone agent link; advertised under the owner's name (adb: --es owner Vishnu). */
    val peers by lazy {
        PeerLink(this, scope) {
            getSharedPreferences("assistant", MODE_PRIVATE).getString("owner", null)
                ?: Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL
        }
    }

    val assistant by lazy {
        val base = DefaultSkillRegistry()
        val peerSkills = PeerSkills(peers) { hide -> overlay.hideForCapture(hide) }.all
        val registry = object : com.hackathon.assistant.core.SkillRegistry {
            private val all = base.all() + peerSkills
            override fun all() = all
            override fun get(id: String) = all.firstOrNull { it.id == id }
        }
        Assistant(
            voice = voice,
            planner = planner,
            skills = registry,
            skillContext = SkillContext(this, AccessibilityScreenReader(), ui, voice),
            progress = tasks,
        )
    }

    private var conversation: Job? = null
    private var jarvis: Job? = null
    private var jarvisWanted = false
    @Volatile private var userStopped = false
    private val oneAtATime = Mutex()

    /** True once the on-device model loaded; false if the file is missing or the load threw. */
    private val llmReady = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        AssistantAccessibilityService.onTrigger = ::onTrigger
        AssistantAccessibilityService.onConnected = { overlay.refresh() }
        startPeers()
        // Warm the model up front so the first command doesn't pay the load, then pre-process the
        // fixed system prompt for the first request.
        scope.launch {
            oneAtATime.withLock {
                runCatching { llm.load() }
                    .onSuccess { llmReady.value = true; Log.i(TAG, "model ready: ${llm.modelName}") }
                    .onFailure { Log.e(TAG, "model load failed: ${llm.modelName}", it) }
                if (llmReady.value) assistant.prepare()
            }
        }
        startA11yWatchdog()
        overlay.start(scope)
        // The camera ring (vivo Halo) mirrors the Jarvis card: lit while it's on screen.
        scope.launch { halo.clear(); overlay.shown.collect { if (it) halo.on() else halo.off() } }
        scope.launch { loadSpeechHints() }
        voice.google.stt.onDeviceOnly = !getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("cloud_stt", false)
    }

    /**
     * Phone-to-phone requests go through normal, tracked agent runs: the hotword releases the
     * mic, the card shows the steps, and ✕ stops them.
     */
    private fun startPeers() {
        // Another phone wants to pair: the agent reads out the code and asks our user (ask_user),
        // then answers with pair_reply.
        peers.onPairRequest = { name, code ->
            start(
                heard = "Pairing request from $name's phone with code ${code.toCharArray().joinToString(" ")}. " +
                    "Ask me whether the same code is on $name's phone. Then call pair_reply with accept=yes only if I clearly confirm, else accept=no.",
            )
        }
        // A trusted phone's agent asks us to do something: run it and send back the closing sentence.
        peers.onTask = { from, text ->
            voice.speak("$from's phone asks: $text")
            val run = withContext(Dispatchers.Main) { start(heard = text); conversation }
            run?.join()
            assistant.lastClosing
        }
        peers.onPhotos = { from, count -> scope.launch { voice.speak("Received ${if (count == 1) "a photo" else "$count photos"} from $from. ${if (count == 1) "It's" else "They're"} in your gallery.") } }
        runCatching { peers.start() }.onFailure { Log.e(TAG, "peer link failed to start", it) }
    }

    /** Switches the on-device model (remembered across restarts), then loads and warms it. */
    fun switchModel(name: String) {
        stopAll()
        scope.launch {
            oneAtATime.withLock {
                // Conversations belong to the old engine: drop them before it is closed.
                planner.reset()
                llm.close()
                llmReady.value = false
                llm.modelName = name
                getSharedPreferences("assistant", MODE_PRIVATE).edit().putString("model", name).apply()
                Log.i(TAG, "switching model to $name")
                runCatching { llm.load() }
                    .onSuccess { llmReady.value = true }
                    .onFailure { Log.e(TAG, "model load failed", it) }
                if (llmReady.value) assistant.prepare()
            }
        }
    }

    // ---- self-heal for the accessibility service ------------------------------------------------

    /**
     * SELF-HEAL for vivo's anti-fraud AccessibilityEnhance monitor, which strips our service
     * from Settings.Secure.enabled_accessibility_services the instant a different app is
     * foregrounded. With WRITE_SECURE_SETTINGS (granted via: adb shell pm grant
     * com.hackathon.assistant android.permission.WRITE_SECURE_SETTINGS) we re-add ourselves
     * immediately when the setting changes, plus a fast periodic re-assert as backup.
     */
    private val a11yService by lazy {
        "$packageName/com.hackathon.assistant.perception.AssistantAccessibilityService"
    }
    @Volatile private var a11yWriteDenied = false

    private fun ensureA11yEnabled() {
        if (a11yWriteDenied) return
        try {
            val cr = contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (a11yService !in cur.split(':')) {
                val next = if (cur.isBlank()) a11yService else "$cur:$a11yService"
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "watchdog: re-enabled accessibility")
            }
        } catch (t: SecurityException) {
            // Not granted: nothing to heal with. Say so once instead of every 400 ms.
            a11yWriteDenied = true
            Log.w(TAG, "watchdog off: WRITE_SECURE_SETTINGS not granted (adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS)")
        } catch (t: Throwable) {
            Log.e(TAG, "watchdog: cannot write secure setting: ${t.message}")
        }
    }

    private fun startA11yWatchdog() {
        ensureA11yEnabled()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = ensureA11yEnabled()
            },
        )
        // Backup re-assert loop — cheap, and wins the race if the observer is throttled.
        scope.launch { while (!a11yWriteDenied) { ensureA11yEnabled(); kotlinx.coroutines.delay(400) } }
    }

    // ---- Jarvis loop ---------------------------------------------------------------------------

    /**
     * The job fields ([jarvis], [conversation], [jarvisWanted]) are only touched on the main
     * thread; calls from elsewhere (adb commands, the hotword loop) hop there first.
     */
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Starts listening for "Jarvis" (from [JarvisService]). */
    fun startJarvis() = onMain {
        jarvisWanted = true
        if (jarvis?.isActive == true) return@onMain
        // Contact names become recognizer hints once READ_CONTACTS is granted (first run).
        if (!contactHints) scope.launch { loadSpeechHints() }
        jarvis = scope.launch {
            hotword.trace("jarvis: listening for the wake word")
            // A tick the instant "Jarvis" is heard, as the card opens.
            launch { var was = false; hotword.awake.collect { if (it && !was) wakeHaptic(); was = it } }
            while (isActive) {
                // "Jarvis" = tapping the mic: the words said in the same breath, else a normal
                // listen (until the sentence ends). The hotword has released the mic.
                val sameBreath = hotword.awaitWake(wakeAllowed)
                tasks.clear() // a new request: last task's steps go
                val request = launch(start = CoroutineStart.LAZY) { converse(heard = sameBreath.ifBlank { null }) }
                withContext(Dispatchers.Main) { userStopped = false; conversation = request }
                request.start()
                request.join()
                hotword.sleep()
            }
        }
    }

    fun stopJarvis() = onMain {
        jarvisWanted = false
        jarvis?.cancel()
        hotword.sleep()
    }

    /** Card tap / accessibility button: while busy, stop (barge-in); while idle, listen now. */
    fun onTrigger() = onMain {
        if (conversation?.isActive == true) stopAll() else start(heard = null)
    }

    /**
     * Starts a request as the single tracked conversation (so ✕ / Stop can cancel it): [heard]
     * if given (typed, adb), else it listens first. The hotword is paused so it releases the
     * recognizer, and resumed afterwards.
     */
    fun start(heard: String?) = onMain {
        if (conversation?.isActive == true) stopAll()
        userStopped = false
        val listening = jarvis
        listening?.cancel()
        tasks.clear()
        conversation = scope.launch {
            listening?.join()
            try {
                converse(heard)
            } finally {
                hotword.sleep()
                val me = currentCoroutineContext()[Job]
                // Only the latest request resumes the hotword, and only while the service wants it.
                onMain { if (jarvisWanted && conversation === me) startJarvis() }
            }
        }
    }

    /** The card's ✕, a second trigger press, or adb --ez stop: cancel the run, silence voice, interrupt the model. */
    fun stopAll() = onMain {
        val wasRunning = conversation?.isActive == true
        userStopped = true
        conversation?.cancel()
        tasks.finish(success = false)
        runCatching { voice.stop() }.onFailure { Log.e(TAG, "voice stop failed", it) }
        runCatching { llm.cancel() }.onFailure { Log.e(TAG, "model cancel failed", it) }
        hotword.sleep()
        // The user stopped it: they know it stopped, so stay silent (log only).
        if (wasRunning) Log.i(TAG, "RESULT: stopped by user")
    }

    /** The card's ✕ (kept for debug commands). */
    fun cancelConversation() = stopAll()

    /**
     * Runs one request: [heard] if given (from the hotword, typed, adb), else listens first.
     * Listening happens before taking the request lock, so words said while the model is still
     * loading or warming up aren't lost; the agent run itself never overlaps another.
     */
    suspend fun converse(heard: String?) {
        try {
            // Nothing heard: the card shows "Didn't catch that" (a false wake-up stays quiet).
            val utterance = heard ?: voice.listen() ?: return
            voice.showHeard(utterance) // the card shows this request, not the last reply
            voice.setThinking(true)
            oneAtATime.withLock {
                if (llmReady.value || tryLoadLlm()) {
                    assistant.handle(utterance)
                } else {
                    assistant.endFailed("My on-device model isn't loaded, so I can't do that yet. Copy ${llm.modelName} to Download/models on this phone.")
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Stop interrupts the model mid-answer, which surfaces as an exception here; the
            // user already knows it stopped.
            if (userStopped || !currentCoroutineContext().isActive) return
            Log.e(TAG, "request failed", t)
            assistant.endFailed("Something went wrong (${t.javaClass.simpleName}), so I couldn't finish that.")
        } finally {
            voice.setThinking(false)
            // Pre-process the next request's fixed prompt while idle, as its own job: the request
            // is over, so a tap now means "listen", not "stop".
            if (currentCoroutineContext().isActive && llmReady.value) {
                scope.launch { oneAtATime.withLock { assistant.prepare() } }
            }
        }
    }

    val halo by lazy { HaloLight(this) }

    /** Late load (the warm-up hadn't finished yet, or the model was pushed after start). */
    private suspend fun tryLoadLlm(): Boolean {
        if (!File(llm.modelsDir, llm.modelName).canRead()) return false
        return runCatching { llm.load() }.onSuccess { llmReady.value = true }
            .onFailure { Log.e(TAG, "model load failed", it) }.isSuccess
    }

    // ---- settings ------------------------------------------------------------------------------

    /** Switches the request recognizer between Google cloud (when online) and on-device only (remembered). */
    fun setCloudStt(on: Boolean) {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("cloud_stt", on).apply()
        voice.google.stt.onDeviceOnly = !on
        hotword.trace("speech recognition: ${if (on) "cloud when available" else "on-device only"}")
    }

    /**
     * Contact names AND installed app names bias the recognizer toward them ("call Bansi", not
     * "kil Bansi"; "open Zepto", not "open septo").
     */
    @Volatile private var contactHints = false

    private fun loadSpeechHints() {
        contactHints = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val names = runCatching {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
            )?.use { c -> buildList { while (c.moveToNext() && size < 300) c.getString(0)?.let(::add) } }
        }.getOrNull().orEmpty()
        val apps = runCatching {
            val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcher, 0).map { it.loadLabel(packageManager).toString() }.distinct().take(150)
        }.getOrNull().orEmpty()
        val hints = (apps + names).distinct()
        voice.google.hints = hints
        hotword.hints = hints
        Log.i(TAG, "speech hints: ${apps.size} app names + ${names.size} contact names")
    }

    private fun wakeHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    private companion object { const val TAG = "Assistant" }
}
