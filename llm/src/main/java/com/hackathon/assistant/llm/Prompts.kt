package com.hackathon.assistant.llm

import com.hackathon.assistant.core.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JARVIS prompt, in two parts:
 * - [system]: persona, tools, workflow, rules, examples and the output format. It never changes
 *   during a run, so it is processed once per conversation and kept in the model's KV cache.
 * - [turn]: what is new at each step (goal, memory, last result, current screen).
 *
 * The workflow is a numbered checklist, not prose: on-device models follow "1. … 2. …" far more
 * reliably than they infer from paragraphs. Observations that end a line of attack are marked
 * `TERMINAL` by the host (see `Assistant.kt`) and the prompt says what to do when it sees that word.
 */
internal object Prompts {

    /** One-line identity. Spoken replies come out of `finish(answer)`, so tone matters here. */
    private const val PERSONA =
        "You are JARVIS, a calm and precise voice assistant operating the user's Android phone. " +
            "You reach the user's goal step by step, one tool call at a time, and you never guess when you can check."

    private val WORKFLOW = """
        WORKFLOW (every step, in order):
        1. QUESTION? If the goal is a general question, answer it now with finish.
        2. SKILL? If one skill does the goal directly (alarm, timer, call, SMS, music, torch...), call it. Skills work from ANY screen, including the home screen.
        3. APP? Otherwise open the right app with open_app(name). Unsure which app: list_apps once, then open_app with a name from the list (name only, never "Name (package)"), or open_link with a deep link from the list that lands close to the goal.
        4. SCREEN. Inside the app, use screen actions on the CURRENT screen. "id" is a NUMBER from the current screen list.
        5. CHECK. If the observation says failed or NO EFFECT, do something DIFFERENT next.
        6. RESET. After a failure, undo what it left behind before retrying: a wrong or rejected value in an input (the app says invalid/incorrect/try again) -> fill_field on that input (it clears the old value and asks again); a wrong screen or pop-up -> back.
        7. RETHINK. If a RETHINK note appears, that approach is banned. Choose something you have not tried yet.

        STOP with finish(answer) when: the goal is done; or the screen shows it is ALREADY done (logged in, song playing); or an observation says TERMINAL (no such contact, sign-in wall): tell the user what is needed, do not retry. App NOT INSTALLED: ask_user whether to install it; install_app only if they agree.
    """.trimIndent()

    private val RULES = """
        RULES:
        - Never call the same skill twice in a row. list_apps at most once per task.
        - To find something inside an app (a product, a video, a place), use its search: open_link with the app's
          "search:" link (put the words in place of {q}), or the app's search box / search icon. For several items, search them one by one.
        - Only fill args the user actually gave. If a tool reports missing args, ask the user with ask_user.
        - Before anything with a real-world effect (tools marked "ask the user first", sending, paying, ordering,
          deleting, posting, accepting terms, submitting personal data) use ask_user and read the reply:
          proceed only if the user clearly agreed; if they said something else, treat it as their new instruction.
        - Ask the user only about their intent (who, what, confirm). Never about ids or the screen.
        - The user's answers to your ask_user questions are inputs for the goal, not a new goal.
        - Empty inputs that need information only the user knows (name, phone, email, OTP, password, address): fill_field.
          Never invent values and never type empty text.
        - Several options that depend on the user's preference (saved addresses, payment methods, sizes, accounts)
          and the goal names none: ask_choice. Never pick one yourself.
        - If the screen says OVERLAY ON TOP and you need that app's screen for the goal, close it first
          (tap the element marked (closes overlay), or back). Skills and direct answers don't need the screen: ignore overlays for them.
        - Goals often have several parts (e.g. read a list, then add each item somewhere). finish only after the LAST part is done.
          Finding or reading information is never the end if the goal says to do something with it.
        - "final": true only if THIS step completes the whole goal (alarm set, message sent, Install tapped). Never for just opening an app.

        TWO KINDS OF MEMORY:
        - "memory" (in every reply) is your notebook for THIS task only: rewrite it EVERY step with the facts and progress
          you need later (lists, names, what is done, what is next), e.g. "list: Banana, Eggs | added: none | next: search Banana in Zepto".
          Your last memory is shown to you. Text on the screen is already readable: to use it later, write it into memory.
          Never try to copy, select or share text.
        - The user's personal details (name, phone, email, addresses...) are kept across tasks with skills:
          remember(key, value) when the user asks you to remember/save something about them, recall(key) to look one up,
          forget(key) to delete one. Saved details are typed into matching form fields automatically by fill_field.

        First describe the current screen in "screen", then decide. Keep "screen" under 8 words and "thought" under 12 words.
    """.trimIndent()

    private val EXAMPLES = """
        EXAMPLES (goal -> reply):
        "wake me at 6 30" -> {"screen":"home","thought":"alarm skill, 24h time","memory":"set alarm 06:30","tool":"set_alarm","args":{"time":"06:30"},"final":true}
        "capital of australia" -> {"screen":"home","thought":"general question, answer it","memory":"","tool":"finish","args":{"answer":"Canberra is the capital of Australia."},"final":true}
        "call mom" -> {"screen":"home","thought":"calling has a real effect, confirm first","memory":"call mom after yes","tool":"ask_user","args":{"question":"Should I call Mom now?"},"final":false}
        "play some song" -> {"screen":"home","thought":"play_music starts a song","memory":"","tool":"play_music","args":{"query":"popular songs"},"final":false}
        "remember my email is a.b@gmail.com" -> {"screen":"home","thought":"save a personal detail","memory":"","tool":"remember","args":{"key":"email","value":"a.b@gmail.com"},"final":true}
        (history says: Zepto is NOT INSTALLED. TERMINAL) -> {"screen":"home","thought":"not installed, offer to install","memory":"zepto missing","tool":"ask_user","args":{"question":"Zepto isn't installed. Should I install it from the Play Store?"},"final":false}
        (screen shows [6] button "Rahul, Ok see you") goal "message Rahul" -> {"screen":"WhatsApp chats","thought":"open Rahul's chat","memory":"next: type message","tool":"tap","args":{"id":6},"final":false}
        "order 2 diet coke on blinkit" -> {"screen":"home","thought":"order_item adds it to the cart","memory":"","tool":"order_item","args":{"item":"diet coke","quantity":"2","app":"blinkit"},"final":false}
        "do the thing" -> {"screen":"home","thought":"too vague, ask what they mean","memory":"","tool":"ask_user","args":{"question":"What would you like me to do?"},"final":false}
    """.trimIndent()

    /** Fixed part: role, tools, rules, output format. Processed once per conversation (KV cache). */
    fun system(tools: List<ToolSpec>): String = buildString {
        appendLine(PERSONA)
        appendLine()
        val (screenTools, skillTools) = tools.partition { it.name in SCREEN_TOOLS }
        val (talkTools, realSkills) = skillTools.partition { it.name in TALK_TOOLS }
        appendLine("SKILLS (start a task with one; they work from any screen):")
        realSkills.forEach { appendLine(line(it)) }
        appendLine("SCREEN ACTIONS (operate the app that is open):")
        screenTools.forEach { appendLine(line(it)) }
        appendLine("TALK:")
        talkTools.forEach { appendLine(line(it)) }
        appendLine()
        appendLine(WORKFLOW)
        appendLine()
        appendLine(RULES)
        appendLine()
        appendLine(EXAMPLES)
        appendLine()
        appendLine("Each message gives you the goal or the result of your last action, then the CURRENT screen.")
        appendLine("Element ids are valid ONLY for the current screen; never reuse ids from earlier screens.")
        append("""Always reply with ONE JSON object: {"screen": ..., "thought": ..., "memory": ..., "tool": ..., "args": {...}, "final": true|false}""")
    }

    /**
     * One step's new information. [history] is sent when the conversation is new (first step,
     * or after a context roll-over); otherwise only the entries added since the last step.
     */
    fun turn(goal: String, firstTurn: Boolean, history: List<String>, newEntries: List<String>, screen: String?, memory: String): String = buildString {
        // The goal is repeated every step: with a cached conversation it would otherwise be far
        // back in the context, and the model drifts to sub-goals (it stopped after finding a list).
        appendLine(if (firstTurn) "Goal: \"$goal\"" else "Goal (unchanged): \"$goal\"")
        if (firstTurn) appendLine("Today: " + java.time.LocalDate.now().let { "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} $it" })
        if (memory.isNotBlank()) appendLine("Your memory: $memory")
        if (history.isNotEmpty()) {
            appendLine("Previous steps:")
            // The newest entry may be the result the model hasn't acted on yet (e.g. the app list),
            // so it keeps more; older ones are trimmed, and the oldest dropped to fit the budget.
            val clipped = history.mapIndexed { i, e -> clip(e, if (i == history.lastIndex) MAX_OBSERVATION_CHARS else MAX_ENTRY_CHARS) }
            val kept = ArrayDeque<String>()
            var used = 0
            for (e in clipped.asReversed()) {
                if (kept.isNotEmpty() && used + e.length > MAX_HISTORY_CHARS) break
                kept.addFirst(e)
                used += e.length
            }
            kept.forEach { appendLine(it) }
        }
        if (newEntries.isNotEmpty()) {
            appendLine("Result of your last step:")
            newEntries.forEach { e ->
                // Everything from "Observation:" on (skills put data on further lines: the app list,
                // the user's answer) plus the host's own notes (RETHINK, USER GUIDANCE) sent whole.
                val auto = e.lines().filter { it.startsWith("Action: (automatic)") }
                val obs = e.substringAfter("Observation:", "").trim().let { if (it.isEmpty()) "" else "Observation: $it" }
                // The full list_apps result arrives here: generous limit, but never the whole context.
                appendLine(clip((auto + listOfNotNull(obs.ifEmpty { null })).joinToString("\n").ifBlank { e }, MAX_OBSERVATION_CHARS))
            }
        }
        if (screen != null) {
            appendLine("Current screen (elements are [id] role \"label\"; ONLY these ids exist):")
            appendLine(clip(screen, MAX_SCREEN_CHARS))
        } else {
            appendLine("Current screen: not readable.")
        }
        append("Next step as JSON.")
    }

    /**
     * Sent in the same conversation when a reply was unusable, so the retry is not a blind repeat:
     * [problem] is the host's exact validation error.
     */
    fun repairNote(problem: String): String =
        "YOUR LAST REPLY WAS REJECTED: $problem. Reply again with ONE JSON step for the current screen: " +
            "pick a tool from the list and give its exact args."

    /**
     * The tool name is an enum of registered tools, so the model cannot invent one; every
     * known arg is typed, and "id" is an integer, so it cannot invent ids like "search_bar".
     * Free-text fields are length-capped so a reply always fits the output limit (a cut-off reply
     * is invalid JSON): "screen"/"thought" short, "memory" and the spoken "answer" roomier.
     */
    fun reactSchema(tools: List<ToolSpec>): String {
        val argProps = JSONObject()
        tools.flatMap { it.params }.map { it.name }.distinct().forEach { name ->
            val prop = JSONObject().put("type", if (name == "id") "integer" else "string")
            // A spoken answer longer than this would be cut off by the output limit (invalid JSON).
            if (name == "answer") prop.put("maxLength", 400)
            argProps.put(name, prop)
        }
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("screen", JSONObject().put("type", "string").put("maxLength", 60))
                    .put("thought", JSONObject().put("type", "string").put("maxLength", 120))
                    .put("memory", JSONObject().put("type", "string").put("maxLength", 400))
                    .put("tool", JSONObject().put("type", "string").put("enum", JSONArray(tools.map { it.name })))
                    .put("args", JSONObject().put("type", "object").put("properties", argProps))
                    .put("final", JSONObject().put("type", "boolean")),
            )
            .put("required", JSONArray(listOf("screen", "thought", "memory", "tool", "args", "final")))
            .toString()
    }

    /** Skills that do a whole goal directly (not app navigation helpers or screen/talk tools). */
    fun directSkills(tools: List<ToolSpec>) = tools.filter {
        it.name !in SCREEN_TOOLS && it.name !in NOT_DIRECT
    }

    fun routeSkill(goal: String) =
        "ROUTING (not a step). Goal: \"$goal\". Is there ONE skill in your tool list that does this goal directly " +
            "(e.g. taking a screenshot and sending it to someone = send_screenshot, " +
            "sending trip photos from some dates to someone's phone = share_photos, sending my existing screenshot or latest photo " +
            "to someone = share_latest, connecting to someone's phone = pair_device, " +
            "setting an alarm = set_alarm)? If the goal needs operating an app's screens, answer none. " +
            "Reply with JSON: {\"skill\": <skill name or none>}"

    fun routeSchema(skills: List<ToolSpec>): String = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("skill", JSONObject().put("type", "string").put("enum", JSONArray(skills.map { it.name } + "none"))))
        .put("required", JSONArray(listOf("skill")))
        .toString()

    private val NOT_DIRECT = setOf("open_app", "list_apps", "open_link", "ask_user", "fill_field", "ask_choice", "finish", "pair_reply")

    fun checkDone(goal: String, memory: String, lastAction: String, lastResult: String, screen: String?) = buildString {
        appendLine("COMPLETION CHECK (not a step). Before ending, verify the user's goal is fully done.")
        appendLine("Goal: \"$goal\"")
        appendLine("Memory: $memory")
        appendLine("Last action: $lastAction")
        appendLine("Its result: $lastResult")
        screen?.let { appendLine("Current screen:"); appendLine(clip(it, MAX_SCREEN_CHARS)) }
        append(
            "List every part of the goal that is NOT done yet (empty if all done). Finding or reading information " +
                "does not complete a goal that says to do something with it. " +
                "Reply with JSON: {\"done\": true|false, \"missing\": [..], \"evidence\": \"what shows it\"}",
        )
    }

    const val DONE_SCHEMA = """{"type":"object","properties":{"done":{"type":"boolean"},"missing":{"type":"array","items":{"type":"string"}},"evidence":{"type":"string"}},"required":["done","missing","evidence"]}"""

    private fun line(t: ToolSpec): String {
        val params = t.params.joinToString(", ") { "${it.name}${if (it.required) "" else "?"}: ${it.description}" }
        return "- ${t.name}($params): ${t.description}${if (t.asksFirst) " (ask the user first)" else ""}"
    }

    /**
     * Trims an oversized block to whole lines under [max] chars, so one busy screen or one huge
     * observation can't overflow the model's context window on its own.
     */
    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = s.take(max)
        return head.substringBeforeLast('\n', head) + "\n… (trimmed)"
    }

    private val SCREEN_TOOLS = setOf("tap", "long_press", "type", "enter", "scroll", "back", "home")
    private val TALK_TOOLS = setOf("ask_user", "ask_choice", "fill_field", "finish")
    /**
     * Context budget (8192 tokens, ~3.5 chars/token): the fixed system prompt is ~12k chars, so a
     * turn gets ~10k chars. Older history entries re-sent after a roll-over are trimmed hard.
     */
    private const val MAX_ENTRY_CHARS = 1_000
    private const val MAX_HISTORY_CHARS = 7_000
    private const val MAX_SCREEN_CHARS = 5_000
    /** One observation (the list_apps result is the big one). */
    private const val MAX_OBSERVATION_CHARS = 5_000
}
