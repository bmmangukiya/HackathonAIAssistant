package com.hackathon.assistant.llm

import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.ToolSpec

/**
 * Scored, timed planner eval so models can be compared on-device (see DebugCommandReceiver).
 *
 * Each case says which tool(s) are acceptable, so the run ends with a `score N/M` line as well as
 * per-case latency. The cases include the exact situations that failed on the device (see
 * `docs/RELIABILITY.md`): a not-installed app with a TERMINAL observation in history, and a
 * `list_apps` result already in history. A model that re-calls `open_app`/`list_apps` there fails
 * the case, which is the behaviour the prompt + host guards are meant to prevent.
 *
 *   adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es bench <file>.litertlm
 *   adb logcat -s LlmBenchmark
 */
object LlmBenchmark {
    private const val TAG = "LlmBenchmark"

    /** Stand-ins for skills still being built, so the routing prompt has realistic length. */
    val plannedSkills: List<Skill> = listOf(
        stub("call_contact", "Phone call a contact or number", "contact", ex = "call mom"),
        stub("send_whatsapp", "Send a WhatsApp message", "contact", "message", ex = "whatsapp rahul I'm late"),
        stub("send_sms", "Send a text message", "contact", "message", ex = "text dad I reached"),
        stub("set_alarm", "Set an alarm", "time", "label?", ex = "wake me up at 6"),
        stub("set_timer", "Start a countdown timer", "duration", ex = "timer for 10 minutes"),
        stub("navigate_to", "Start Google Maps navigation", "destination", ex = "take me to the airport"),
        stub("play_youtube", "Search and play on YouTube", "query", ex = "play lofi music on youtube"),
        stub("toggle_flashlight", "Turn the flashlight on or off", "state", ex = "turn on the torch"),
        stub("web_search", "Search the web", "query", ex = "search for biryani near me"),
    )

    private fun stub(id: String, desc: String, vararg slots: String, ex: String = "") = object : Skill {
        override val id = id
        override val description = desc
        override val slots = slots.map { SlotSpec(it.removeSuffix("?"), it, required = !it.endsWith("?"), question = it) }
        override val examples = listOf(ex.ifBlank { id.replace('_', ' ') })
        override suspend fun execute(ctx: SkillContext, args: Map<String, String>) = ActionResult.Success()
    }

    private val WHATSAPP_SCREEN = """
        App: WhatsApp (com.whatsapp)
        [1] button "New chat"
        [2] input "Search"
        [3] tab "Chats" (selected)
        [4] tab "Updates"
        [5] button "Mom, Call me when free, 10:42"
        [6] button "Rahul, Ok see you, Yesterday"
        [7] button "Office Team, Priya: meeting at 4, Yesterday"
        [8] list (scrollable)
    """.trimIndent()

    private const val LAUNCHER = "Home screen. No app is open. Skills work from here; open an app only if the goal needs one."

    /** One eval case: what the model sees, and which tool(s) count as correct. */
    private data class Case(
        val name: String,
        val goal: String,
        val screen: String?,
        val history: List<String> = emptyList(),
        val accept: Set<String>,
        /** Optional arg check, e.g. the tapped id must be Rahul's row. */
        val argCheck: (Map<String, String>) -> Boolean = { true },
    )

    private val cases = listOf(
        Case("route: open app", "open youtube", LAUNCHER, accept = setOf("open_app")),
        // Calling/sending are "ask the user first" tools: confirming first is also correct.
        Case("route: skill w/ slot", "call mom", LAUNCHER, accept = setOf("call_contact", "ask_user")) {
            it["contact"]?.contains("mom", true) == true || it["question"]?.contains("mom", true) == true
        },
        Case("route: time normalisation", "set an alarm for 6 30 tomorrow", LAUNCHER, accept = setOf("set_alarm")) {
            it["time"]?.matches(Regex("0?6:30")) == true
        },
        Case("route: two slots", "send a whatsapp message to rahul saying I'm running late", LAUNCHER, accept = setOf("send_whatsapp", "whatsapp_message", "open_app", "ask_user")),
        Case("answer: general knowledge", "what's the capital of australia", LAUNCHER, accept = setOf("finish")) {
            it["answer"]?.contains("canberra", true) == true
        },
        Case("clarify: vague", "do the thing", LAUNCHER, accept = setOf("ask_user", "finish")),
        Case("screen: tap right chat", "Send Rahul the message: I'm running late", WHATSAPP_SCREEN, accept = setOf("tap")) { it["id"] == "6" },
        // The two situations from the device trace. Correct behaviour is to STOP, not retry.
        Case(
            "recover: app not installed (TERMINAL)", "order the grocery list on zepto", LAUNCHER,
            history = listOf(
                "Screen: Home screen\nThought: open Zepto\nAction: open_app(app=\"Zepto\")\n" +
                    "Observation: failed: Zepto is NOT INSTALLED on this phone. TERMINAL: do not try to open it again. Use finish to tell the user, or open the Play Store to install it.",
            ),
            accept = setOf("finish", "ask_user", "open_app", "install_app"),
        ) { args -> args["app"]?.contains("play", true) != false }, // finish / ask to install, or open_app(Play Store); never open_app(Zepto)
        Case(
            "recover: app list already shown", "open the notes app", LAUNCHER,
            history = listOf(
                "Screen: Home screen\nThought: find the app\nAction: list_apps()\n" +
                    "Observation: ok.\nInstalled apps (name (package)); open the right one with open_app:\nCalendar (com.google.android.calendar)\nNotes (com.vivo.notes)\nYouTube (com.google.android.youtube)",
            ),
            accept = setOf("open_app"),
        ) { it["app"]?.contains("notes", true) == true },
    )

    suspend fun run(llm: LiteRtLlm, rawTools: List<ToolSpec>) {
        // The registry and the planned stubs overlap (call_contact, set_alarm, send_sms); a
        // duplicated tool list is not what the agent sends and just inflates the prompt.
        val tools = rawTools.distinctBy { it.name }
        val planner = LlmPlanner(llm)
        val t0 = System.currentTimeMillis()
        llm.load()
        Log.i(TAG, "${llm.modelName}: load ${System.currentTimeMillis() - t0} ms")
        var passed = 0
        val times = mutableListOf<Long>()
        for (c in cases) {
            val t = System.currentTimeMillis()
            val step = planner.next(c.goal, tools, c.history, c.screen)
            val ms = System.currentTimeMillis() - t
            times += ms
            val ok = step != null && step.tool in c.accept && c.argCheck(step.args)
            if (ok) passed++
            Log.i(TAG, "${if (ok) "PASS" else "FAIL"} ${ms}ms | ${c.name} | ${c.goal} -> ${step?.tool}${step?.args ?: ""}${if (!ok) " (wanted ${c.accept})" else ""}")
        }
        val sorted = times.sorted()
        Log.i(
            TAG,
            "${llm.modelName}: score $passed/${cases.size} | step p50 ${sorted[sorted.size / 2]} ms, max ${sorted.last()} ms, mean ${times.average().toLong()} ms | DONE",
        )
    }
}
