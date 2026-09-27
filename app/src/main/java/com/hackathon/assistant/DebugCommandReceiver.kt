package com.hackathon.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.ToolSpec
import com.hackathon.assistant.perception.AccessibilityScreenReader
import com.hackathon.assistant.llm.LlmBenchmark
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dev/test hook over adb:
 *   --es text "open youtube"                      run a command as if spoken
 *   --es bench gemma-4-E4B-it-gpu.litertlm        benchmark a model (logcat -s LlmBenchmark)
 *   --es model gemma-4-12B-it-gpu.litertlm        switch model (remembered; file in Download/models)
 *   --ez stop true                                same as the Stop button
 *   --ez talk true                                same as pressing the assistant button (listen)
 *   --ez dump true                                log the translated current screen (logcat -s ScreenDump)
 *   --es skill read_sms --es args "limit=5"       run ONE skill directly, no LLM (logcat -s Assistant)
 *                                                 args are "k=v;k2=v2"; see the skill's slots
 *   --es say "hello there"                        speak with the active TTS
 *   --ez cloud_stt true|false                     request recognition on Google cloud / on-device only
 */
class DebugCommandReceiver : BroadcastReceiver() {

    /**
     * File sink for test output. vivo/iQOO devices mute app logcat, so we mirror
     * debug results to a file readable with:
     *   adb shell run-as com.hackathon.assistant cat files/skilltest.log
     */
    private fun Context.dbg(msg: String) {
        Log.i("Assistant", msg)
        runCatching { File(filesDir, "skilltest.log").appendText(msg + "\n") }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AssistantApp
        // --ez cancel true: exactly what the card's ✕ does.
        if (intent.getBooleanExtra("cancel", false)) { app.cancelConversation(); app.dbg("✕ cancel"); return }
        // Not goAsync(): benchmarks run for minutes, far past the broadcast deadline.
        app.scope.launch {
            try {
                intent.getStringExtra("bench")?.let { model ->
                    app.llm.close()
                    app.llm.modelName = model
                    LlmBenchmark.run(
                        app.llm,
                        (DefaultSkillRegistry().all() + LlmBenchmark.plannedSkills).map { ToolSpec(it.id, it.description, it.slots) } +
                            Assistant.UI_TOOLS + Assistant.TALK_TOOLS,
                    )
                }
                if (intent.getBooleanExtra("dump", false)) {
                    val screen = app.assistant.skillContext.screen
                    val state = screen.capture()
                    Log.i("ScreenDump", state?.let(screen::toPrompt) ?: "accessibility service not connected")
                }
                if (intent.getBooleanExtra("raw", false)) {
                    val reader = app.assistant.skillContext.screen as AccessibilityScreenReader
                    reader.rawDump().lines().forEach { Log.i("RawDump", it) }
                }
                // --ez seed_sms true  → load genuine-looking demo SMS (SIM-less test device).
                // --ez seed_sms false → clear demo data, use the real SMS provider again.
                if (intent.hasExtra("seed_sms")) {
                    val on = intent.getBooleanExtra("seed_sms", false)
                    com.hackathon.assistant.actions.ActionsDebug.seedSms(on)
                    app.dbg("seed_sms=$on (demo messages ${if (on) "loaded" else "cleared"})")
                }
                intent.getStringExtra("skill")?.let { skillId ->
                    val skill = DefaultSkillRegistry().get(skillId)
                    if (skill == null) {
                        app.dbg("no such skill: $skillId")
                        return@let
                    }
                    val args = intent.getStringExtra("args").orEmpty()
                        .split(";").filter { it.contains("=") }
                        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
                    app.dbg("▶ direct skill: $skillId args=$args")
                    val result = skill.execute(app.assistant.skillContext, args)
                    app.dbg("◀ result: $result")
                }
                // --ez cloud_stt true: Google's online recognizer when there is internet (Gboard accuracy).
                if (intent.hasExtra("cloud_stt")) {
                    app.setCloudStt(intent.getBooleanExtra("cloud_stt", false))
                }
                intent.getStringExtra("say")?.let { app.voice.speak(it) }
                // Tracked like a spoken request, so --ez stop and the card's ✕ can cancel it.
                intent.getStringExtra("text")?.let { app.start(heard = it) }
                if (intent.getBooleanExtra("talk", false)) app.onTrigger()
                if (intent.getBooleanExtra("stop", false)) app.stopAll()
                intent.getStringExtra("model")?.let { app.switchModel(it) }
                intent.getStringExtra("owner")?.let {
                    app.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE).edit().putString("owner", it).apply()
                    Log.i("Assistant", "owner name set to $it (restart the app to re-advertise)")
                }
            } catch (t: Throwable) {
                Log.e("Assistant", "debug command failed", t)
            }
        }
    }
}

