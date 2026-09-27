package com.hackathon.assistant.llm

import android.util.Log
import com.hackathon.assistant.core.AgentStep
import com.hackathon.assistant.core.Completion
import com.hackathon.assistant.core.LlmChat
import com.hackathon.assistant.core.LocalLlm
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.ToolSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ReAct step selection on top of a [LocalLlm], with schema-constrained JSON output.
 *
 * One conversation per run: the system prompt is processed once and kept in the KV cache, and
 * each step sends only what is new (see [Prompts.turn]). Reliability layers, in order:
 * 1. **Timeout** per model call. [onTimeout] must interrupt the model (a blocking native call
 *    can't be cancelled from a coroutine); the conversation is then replaced by a fresh one.
 * 2. **Validation** of the parsed step against the tool list: unknown tool → reject, unknown args →
 *    dropped (small models invent `filter=installed`), screen actions without a numeric id → reject.
 *    Validation runs on the host, where it is free, instead of trusting the model.
 * 3. **Repair retry**: the second attempt carries the exact rejection reason, which turns a
 *    coin-flip retry into a corrected one.
 */
class LlmPlanner(
    private val llm: LocalLlm,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** Interrupts a generation in progress (e.g. [LiteRtLlm.cancel]); called when a call times out. */
    private val onTimeout: () -> Unit = {},
) : Planner {
    private var schemaFor: List<ToolSpec>? = null
    private var schema = ""

    /** The run's conversation: system prompt cached, each step adds only what's new. */
    private var chat: LlmChat? = null
    private var chatSystem: String? = null
    private var chatGoal: String? = null
    /** The run the conversation belongs to (the agent keeps one scratchpad list per run). */
    private var chatRun: List<String>? = null
    private var sentEntries = 0
    /** Pre-processed conversation for the next request, built while idle. */
    private var warm: Pair<String, LlmChat>? = null

    override suspend fun next(
        goal: String,
        tools: List<ToolSpec>,
        scratchpad: List<String>,
        screen: String?,
        memory: String,
    ): AgentStep? {
        if (schemaFor !== tools) { schema = Prompts.reactSchema(tools); schemaFor = tools }
        val system = Prompts.system(tools)
        fun fullTurn() = Prompts.turn(goal, true, history = scratchpad.takeLast(MAX_HISTORY), newEntries = emptyList(), screen = screen, memory = memory)
        // A new run (or a stopped run's leftover conversation for the same words) starts fresh.
        val newRun = scratchpad.isEmpty() || chat == null || chatRun !== scratchpad || chatSystem != system || chatGoal != goal
        chatRun = scratchpad
        var message = if (newRun) {
            openRunChat(system); chatGoal = goal
            // Skill check on a request's very first step: if one skill does the goal directly, that
            // step may only use it (or ask the user), so "send photos to Vishnu" can't wander into
            // Gallery / share sheets. Not after a hand-off (a skill already ran).
            val pick = if (scratchpad.isEmpty()) routeSkill(goal, tools) else null
            firstStepTools = pick?.let { p -> tools.filter { it.name == p || it.name == "ask_user" } }
            fullTurn() + (pick?.let { "\nUse the $it skill for this goal now." }.orEmpty())
        } else {
            val step = Prompts.turn(goal, false, history = emptyList(), newEntries = scratchpad.drop(sentEntries), screen = screen, memory = memory)
            // Roll over BEFORE this message would push the context past its limit (~3.5 chars/token).
            val projected = (chat?.tokenCount ?: 0) + step.length * 2 / 7 + MAX_OUTPUT_TOKENS
            if (projected > ROLL_OVER_TOKENS) {
                Log.i(TAG, "context would reach ~$projected tokens: starting a fresh conversation")
                openRunChat(system); chatGoal = goal; fullTurn()
            } else step
        }
        sentEntries = scratchpad.size
        // The skill check's pick narrows this step's output format to that skill (or ask_user).
        val stepSchema = firstStepTools.also { firstStepTools = null }?.let { Prompts.reactSchema(it) }
        repeat(MAX_ATTEMPTS) { attempt ->
            logPrompt(message)
            val raw = try {
                send(chat!!, message, stepSchema)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // An engine error (e.g. context full) leaves the conversation unusable: start a
                // fresh one with the whole turn. A second failure propagates.
                if (attempt == MAX_ATTEMPTS - 1) throw t
                Log.w(TAG, "llm[$attempt]: ${t.javaClass.simpleName}: ${t.message}; retrying in a fresh conversation")
                openRunChat(system); chatGoal = goal
                message = fullTurn()
                return@repeat
            }
            if (raw == null) {
                // The interrupted conversation is in an unknown state: start over with the whole turn.
                Log.w(TAG, "llm[$attempt]: timed out after $callTimeoutMs ms")
                openRunChat(system)
                chatGoal = goal
                message = fullTurn() + "\n" + Prompts.repairNote("you took too long; answer with a short thought and memory")
                return@repeat
            }
            Log.i(TAG, "llm[$attempt]: $raw")
            when (val v = validate(raw, tools)) {
                is Validation.Ok -> return v.step
                is Validation.Bad -> {
                    Log.w(TAG, "llm[$attempt] rejected: ${v.reason}")
                    message = Prompts.repairNote(v.reason)
                }
            }
        }
        return null
    }

    private sealed interface Validation {
        data class Ok(val step: AgentStep) : Validation
        data class Bad(val reason: String) : Validation
    }

    /** Host-side check of one model reply against the registered tools. */
    private fun validate(raw: String, tools: List<ToolSpec>): Validation {
        val json = extractJson(raw) ?: return Validation.Bad("not valid JSON")
        val toolName = json.optString("tool")
        val spec = tools.firstOrNull { it.name == toolName }
            ?: return Validation.Bad("\"$toolName\" is not a tool; choose one from the list")
        // Keys are trimmed: small models sometimes emit "text " and the value would be lost.
        val given = json.optJSONObject("args")
            ?.let { a -> a.keys().asSequence().associate { k -> k.trim() to (a.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()) } }
            .orEmpty()
        val known = spec.params.map { it.name }.toSet()
        val unknown = given.keys - known
        if (unknown.isNotEmpty()) Log.i(TAG, "dropping unknown args for $toolName: $unknown")
        val args = given.filterKeys { it in known }.filterValues { it.isNotBlank() }
        // Screen actions must point at a real element. Skills with missing required slots are
        // fine here: the host reports the missing args and the model asks the user.
        if ("id" in known && spec.params.first { it.name == "id" }.required && args["id"]?.toIntOrNull() == null) {
            return Validation.Bad("$toolName needs \"id\" = a number from the CURRENT screen list")
        }
        return Validation.Ok(
            AgentStep(
                screen = json.optString("screen").take(60),
                thought = json.optString("thought").take(120),
                memory = json.optString("memory"),
                tool = toolName,
                args = args,
                final = json.optBoolean("final", false),
            ),
        )
    }

    /**
     * Asked in the run's own conversation (it already holds the whole history), with a separate
     * output format. Anything unparseable counts as "not verified done"; a timeout doesn't block
     * finishing (the model already said it was done, and the screen checks passed).
     */
    override suspend fun checkDone(goal: String, memory: String, lastAction: String, lastResult: String, screen: String?): Completion {
        val c = chat ?: return Completion(true, emptyList(), "no conversation to check")
        val prompt = Prompts.checkDone(goal, memory, lastAction, lastResult, screen)
        logPrompt(prompt)
        // A timeout or an engine error (e.g. the context is full) doesn't block finishing: the model
        // already said it was done and the screen checks passed. The conversation is dropped.
        val raw = try {
            send(c, prompt, Prompts.DONE_SCHEMA)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "completion check failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } ?: run {
            chat?.close(); chat = null
            return Completion(true, emptyList(), "completion check unavailable")
        }
        Log.i(TAG, "completion check: $raw")
        val json = extractJson(raw) ?: return Completion(false, listOf("could not verify"), "unparseable: $raw")
        val missing = json.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            .orEmpty().filter { it.isNotBlank() }
        return Completion(json.optBoolean("done", false) && missing.isEmpty(), missing, json.optString("evidence"))
    }

    /**
     * One model call with a timeout. Returns null if it timed out. A native generation can't be
     * cancelled from a coroutine, so the watchdog calls [onTimeout] to interrupt it; any other
     * failure (e.g. the user's Stop interrupting the model) propagates.
     */
    private suspend fun send(c: LlmChat, text: String, jsonSchema: String? = null): String? = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val watchdog = launch { delay(callTimeoutMs); timedOut.set(true); onTimeout() }
        try {
            val out = c.send(text, jsonSchema)
            if (timedOut.get()) null else out
        } catch (t: Throwable) {
            if (timedOut.get() && t !is kotlinx.coroutines.CancellationException) null else throw t
        } finally {
            watchdog.cancel()
        }
    }

    /** Tools allowed for the first step when the skill check picked one. */
    private var firstStepTools: List<ToolSpec>? = null

    /**
     * Skill check before a request's first step: is there ONE skill that does the goal directly?
     * Asked in the run's conversation with an enum of skill names; a timeout or failure means "no".
     */
    private suspend fun routeSkill(goal: String, tools: List<ToolSpec>): String? {
        val direct = Prompts.directSkills(tools)
        if (direct.isEmpty()) return null
        val raw = runCatching { send(chat!!, Prompts.routeSkill(goal), Prompts.routeSchema(direct)) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return null
        val pick = Regex("\"skill\"\\s*:\\s*\"([a-z_]+)\"").find(raw)?.groupValues?.get(1)
        Log.i(TAG, "skill check: $pick  ($raw)")
        return pick?.takeIf { p -> p != "none" && direct.any { it.name == p } }
    }

    /** Uses the pre-warmed conversation when its system prompt matches, else opens a new one. */
    private suspend fun openRunChat(system: String) {
        chat?.close()
        chat = null
        val w = warm
        warm = null
        chat = if (w != null && w.first == system) {
            Log.i(TAG, "using pre-warmed conversation")
            w.second
        } else {
            w?.second?.close()
            llm.openChat(system, MAX_OUTPUT_TOKENS, schema)
        }
        chatSystem = system
        sentEntries = 0
    }

    /** Drops every conversation (the run's and the pre-warmed one), e.g. before the engine is closed. */
    fun reset() {
        chat?.close(); chat = null; chatGoal = null; chatSystem = null; chatRun = null
        warm?.second?.close(); warm = null
        sentEntries = 0
    }

    /** Called when idle: close the finished run's conversation and pre-process the next one. */
    override suspend fun prepare(tools: List<ToolSpec>) {
        if (schemaFor !== tools) { schema = Prompts.reactSchema(tools); schemaFor = tools }
        val system = Prompts.system(tools)
        chat?.close(); chat = null; chatGoal = null
        if (warm?.first == system) return
        warm?.second?.close(); warm = null
        warm = system to llm.openChat(system, MAX_OUTPUT_TOKENS, schema)
    }

    /** Full prompt to logcat (`adb logcat -s Prompt`), chunked under logcat's line limit. */
    private fun logPrompt(prompt: String) {
        Log.i(PROMPT_TAG, "======== prompt (${prompt.length} chars) ========")
        prompt.lines().forEach { Log.i(PROMPT_TAG, it.ifEmpty { " " }) }
    }

    private companion object {
        const val TAG = "LlmPlanner"
        const val PROMPT_TAG = "Prompt"
        const val MAX_ATTEMPTS = 2
        /** Room for the capped screen/thought/memory/answer fields plus the JSON around them. */
        const val MAX_OUTPUT_TOKENS = 384
        const val MAX_HISTORY = 6
        /** Start a fresh conversation before the 8192-token context fills up. */
        const val ROLL_OVER_TOKENS = 7_200
        /**
         * Gemma 4 E4B takes ~10–25 s per step on the test phone (slower when warm), and a new
         * conversation also pre-fills the system prompt; 60 s means something is wrong.
         */
        const val DEFAULT_CALL_TIMEOUT_MS = 60_000L
    }
}

/**
 * Pulls the first parseable JSON object out of model output. Tolerates code fences, chatter
 * and the stray `{"` prefix LiteRT-LM's constrained decoder emits (`{"{"type":"skill"}}`).
 */
internal fun extractJson(raw: String): JSONObject? {
    var start = raw.indexOf('{')
    while (start >= 0) {
        val parsed = runCatching { JSONTokener(raw.substring(start)).nextValue() as? JSONObject }.getOrNull()
        if (parsed != null && parsed.length() > 0) return parsed
        start = raw.indexOf('{', start + 1)
    }
    return null
}
