package com.hackathon.assistant

import android.util.Log
import com.hackathon.assistant.actions.OtpReader
import com.hackathon.assistant.actions.RememberedFacts
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.AgentStep
import com.hackathon.assistant.core.InputKind
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Settle
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SkillRegistry
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.StepStatus
import com.hackathon.assistant.core.TaskProgress
import com.hackathon.assistant.core.ToolSpec
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.UiElement
import com.hackathon.assistant.core.VoiceIO

/**
 * ReAct agent: each step the model thinks, picks one tool (a skill, a screen action, or
 * ask/finish), we run it and feed the observation back, until it finishes.
 *
 * Tools come from two places: every registered [Skill], plus the built-in [UI_TOOLS] and
 * [TALK_TOOLS]. Skills with missing required args are reported back so the model asks the user;
 * [Risk.CONFIRM] skills are marked "ask the user first" in the prompt. A step marked `final` that
 * succeeds ends the request with no extra model call, so "set an alarm" still costs one inference.
 *
 * The host does not trust the model to notice loops: repeated or banned actions, stalls and
 * circles are detected here, and each time the model is told to change approach (RETHINK) before
 * the user is asked what to do. Every step is reported to [progress] for the Jarvis card.
 */
class Assistant(
    private val voice: VoiceIO,
    private val planner: Planner,
    private val skills: SkillRegistry,
    val skillContext: SkillContext,
    /** Where each step is reported in plain words, for the Jarvis card. */
    private val progress: TaskProgress = TaskProgress.None,
) {
    /** Only skills usable on this device right now (e.g. WhatsApp installed and logged in). */
    private fun availableTools(): List<ToolSpec> {
        val usable = skills.all().filter { runCatching { it.isAvailable(skillContext.android) }.getOrDefault(false) }
        val hidden = skills.all().map { it.id } - usable.map { it.id }.toSet()
        if (hidden.isNotEmpty()) log("skills unavailable on this device: $hidden")
        return usable.map { ToolSpec(it.id, it.description, it.slots, asksFirst = it.risk == Risk.CONFIRM) } + UI_TOOLS + TALK_TOOLS
    }

    private sealed interface Outcome {
        data class Observed(
            val text: String,
            val ok: Boolean,
            val spoken: String = "",
            val doneWhen: (() -> Boolean)? = null,
            /** A skill got us to the right screen and handed off the rest ("tap the first video"). */
            val followUp: String? = null,
        ) : Outcome
        /** Ends the run. [closing] is spoken once by [end]; [done] = goal achieved. */
        data class Stop(val closing: String, val done: Boolean = false) : Outcome
    }

    /** The user's request (normalized), for the closing sentence. */
    private var request = ""
    private var warnedCircle = false
    /** The model's own memory from its last step (facts, progress); shown back every step. */
    private var memory = ""
    private var completionRejects = 0
    /** Whether this run has worked inside an app (then "done" is double-checked). */
    private var usedScreen = false
    private var verifications = 0
    /** Fresh rounds granted after running out of steps (reset per request). */
    private var extraRounds = 0

    /** The skill run by the previous step; running it again right away is always a loop. */
    private var lastSkill: String? = null

    /**
     * Watches the screen for [VERIFY_MS] after the model believes it is done. Apps often show a
     * dialog or the next screen a moment later ("Is this the correct number?"); if anything
     * changes, the task isn't finished.
     */
    private suspend fun screenChangesAfter(reference: ScreenState?): Boolean {
        reference ?: return false
        var waited = 0L
        while (waited < VERIFY_MS) {
            kotlinx.coroutines.delay(500)
            waited += 500
            val now = skillContext.screen.capture() ?: return false
            if (now.elements.map { it.label } != reference.elements.map { it.label }) return true
        }
        return false
    }

    /**
     * Every run ends here with exactly one spoken closing sentence: the result when done, or
     * what stopped it plus the fact that the request wasn't finished. Returns [done].
     */
    /** The last run's closing sentence (sent back to a phone that asked us to do something). */
    var lastClosing = ""
        private set

    private suspend fun end(done: Boolean, closing: String): Boolean {
        val text = if (done) closing else "$closing I couldn't finish: ${request.trim().trimEnd('.')}."
        lastClosing = text
        log("RESULT: ${if (done) "done" else "failed"} | $text")
        voice.speak(text)
        return done
    }

    /** Idle-time warm-up: pre-process the fixed prompt for the next request. */
    suspend fun prepare() = runCatching { planner.prepare(availableTools()) }
        .onFailure { Log.w(TAG, "warm-up failed", it) }

    /** Same closing rule for failures that happen outside a run (crash, nothing heard). */
    suspend fun endFailed(reason: String) {
        log("RESULT: failed | $reason")
        voice.speak(reason)
    }

    suspend fun handle(utterance: String) {
        progress.start(utterance)
        extraRounds = 0
        var success = false
        try {
            success = runSteps(utterance)
        } finally {
            progress.finish(success)
        }
    }

    /**
     * Cleans what STT gave us before it becomes the goal of every prompt: drops the wake word
     * and polite filler, collapses whitespace. Deterministic, so it never changes meaning; the
     * original is what the card shows.
     */
    internal fun normalize(utterance: String): String {
        var s = utterance.trim().replace(Regex("\\s+"), " ")
        s = s.replace(Regex("^(hey |ok |okay )?(jarvis|jarv|friday|fri day)[,.!]?\\s*", RegexOption.IGNORE_CASE), "")
        for (f in FILLER) s = s.replace(Regex("^$f\\s+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s+(please|for me|thanks|thank you)[.!?]*$", RegexOption.IGNORE_CASE), "")
        return s.trim().trimEnd('.', '!', '?').ifBlank { utterance.trim() }
    }

    /**
     * "open <app>" / "launch <app>" when the app IS installed needs no model: saves a whole
     * inference. Anything more ("open youtube and search lofi") or an app that isn't installed
     * falls through to the model.
     */
    private suspend fun fastPath(goal: String): Boolean {
        val m = Regex("^(?:open|launch|start|go to)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE).find(goal) ?: return false
        val name = m.groupValues[1].trim()
        val words = name.lowercase().split(' ')
        if (name.length < 2 || words.size > 3 || words.any { it in TASK_WORDS }) return false
        val skill = skills.get("open_app") ?: return false
        val id = progress.step("Opening $name")
        val r = try { skill.execute(skillContext, mapOf("app" to name)) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) { null }
        return if (r is ActionResult.Success) {
            progress.update(id, StepStatus.DONE)
            log("fast path: open_app($name) -> ${r.message}")
            end(true, r.message.ifBlank { "Opening $name." })
        } else {
            progress.update(id, StepStatus.FAILED, detail = (r as? ActionResult.Failure)?.reason?.substringBefore(". TERMINAL"))
            false
        }
    }

    /**
     * Deterministic first step for commands with one obvious tool ("pause the music", "remember my
     * email is …", "install zepto"). Only unambiguous phrasings are routed; everything else goes
     * to the model. A routed step still passes all the guards.
     */
    internal fun preRoute(goal: String): AgentStep? {
        val g = goal.lowercase().trim()
        // media_control is complete in one step (final); play_music ends via its doneWhen check.
        fun step(tool: String, args: Map<String, String>) = AgentStep("", "known command", tool = tool, args = args, final = tool == "media_control" || tool == "install_app")
        Regex("^(pause|stop)( the)? (music|song|playback|video)$").find(g)?.let { return step("media_control", mapOf("action" to "pause")) }
        Regex("^(resume|continue|unpause)( the)?( music| song| playback)?$|^play (it|this|that)( song| one)?$").find(g)?.let { return step("media_control", mapOf("action" to "play")) }
        Regex("^(next|skip)( the)?( song| track)?$|^play (the )?next (song|track)$").find(g)?.let { return step("media_control", mapOf("action" to "next")) }
        Regex("^(previous|last)( song| track)$|^play (the )?previous (song|track)$").find(g)?.let { return step("media_control", mapOf("action" to "previous")) }
        memoryStep(g, goal)?.let { return it }
        // "order 2 diet coke on blinkit", "buy two quantity of milk from zepto", "add bread to my blinkit cart"
        Regex("^(?:let'?s |please )?(?:order|buy|add|get me)(?: me)? (?:(\\d+|one|two|three|four|five|six|a couple of)\\s+)?(?:(?:quantity|qty|packs?|bottles?|cans?|units?) (?:of )?)?(.+?)\\s+(?:on|from|in|to|using)(?: my)? (blinkit|zepto)(?: app| cart)?$").find(g)?.let {
            val (n, what, app) = it.destructured
            return step("order_item", buildMap { put("item", what.trim()); put("app", app); if (n.isNotBlank()) put("quantity", n) })
        }
        (Regex("^install(?: the)? (.+?)(?: app)?(?: from (?:the )?play ?store)?$").find(g)
            ?: Regex("^(?:download|get)(?: the)? (.+?) (?:app(?: from (?:the )?play ?store)?|from (?:the )?play ?store)$").find(g))
            ?.let { return step("install_app", mapOf("app" to it.groupValues[1].trim())) }
        val play = Regex("^play (.+?)(?: (?:on|in|using) (youtube music|youtube|spotify))?$").find(g) ?: return null
        val what = play.groupValues[1].removePrefix("some ").removeSuffix(" please").trim()
        if (what.startsWith("store") || what == "") return null // "play store" is an app, not music
        if (Regex("\\b(game|games|chess|ludo|carrom|sudoku|quiz|cricket)\\b").containsMatchIn(what)) return null // a game, not a song
        val app = play.groupValues[2]
        return step("play_music", buildMap { put("query", what); if (app.isNotBlank()) put("app", app) })
    }

    /**
     * Personal-memory commands with an explicit verb: "remember my number is 98…" → remember,
     * "forget my address" → forget, "what's my email" → recall (only if it IS saved; otherwise
     * "what's my battery level" would be answered from memory). "Forget everything" is left to
     * the model, which asks first.
     */
    private fun memoryStep(g: String, original: String): AgentStep? {
        fun step(tool: String, args: Map<String, String>) = AgentStep("", "memory command", tool = tool, args = args, final = true)
        Regex("^forget(?: about)? my (.+)$").find(g)?.let { return step("forget", mapOf("key" to memoryKey(it.groupValues[1]))) }
        Regex("^(?:what(?:'s| is)|tell me) my (.+?)\\??$").find(g)?.let {
            val key = memoryKey(it.groupValues[1])
            return if (RememberedFacts.find(skillContext.android, key) != null) step("recall", mapOf("key" to key)) else null
        }
        val m = Regex("^(?:please )?(?:remember|save|store|note)(?: that)? my ([a-z ]+?)(?:,)? (?:is|it's|it is|as|=|:)\\s*(.+)$").find(g)
            ?: Regex("^(?:remember|save|store|note) my ([a-z ]+?)[,:]?\\s+(.+)$").find(g)
            ?: return null
        val key = memoryKey(m.groupValues[1])
        // Value from the original text (keeps email/address casing), after the matched key phrase.
        val raw = m.groupValues[2].trim().trimEnd('.', '!')
        val value = Regex(Regex.escape(raw), RegexOption.IGNORE_CASE).findAll(original).lastOrNull()?.value?.trim() ?: raw
        val cleaned = if (key == "phone") value.filter(Char::isDigit).let { d -> if (d.length == 12 && d.startsWith("91")) d.drop(2) else d } else value
        return step("remember", mapOf("key" to key, "value" to cleaned))
    }

    /** Canonical memory keys so "number", "mobile number" and "phone" all mean the same fact. */
    private fun memoryKey(k: String): String {
        val x = k.trim().lowercase().removePrefix("the ")
        return when {
            x in setOf("number", "phone", "phone number", "mobile", "mobile number", "contact number", "cell") -> "phone"
            x in setOf("email", "email id", "email address", "mail", "mail id") -> "email"
            x in setOf("name", "full name") -> "name"
            else -> x.replace(' ', '_')
        }
    }

    /** The ReAct loop. Returns true if the request was completed. */
    private suspend fun runSteps(utterance: String): Boolean {
        var goal = normalize(utterance)
        request = goal
        log("user: $utterance${if (goal != utterance) "  → goal: $goal" else ""}")
        lastSkill = null
        usedScreen = false
        verifications = 0
        memory = ""
        completionRejects = 0
        warnedCircle = false
        if (fastPath(goal)) return true
        val tools = availableTools()
        val scratchpad = mutableListOf<String>()
        val seen = mutableMapOf<String, Int>()          // tool+args → how many times tried
        var stalled = 0                                  // consecutive steps that did nothing useful
        var repeatBlocks = 0                             // how often the same-skill-twice guard fired
        var rethinks = 0                                 // "stuck → try something new" rounds used
        var askedUser = false                            // after MAX_RETHINKS, the user was asked once
        val banned = mutableSetOf<String>()              // tool+args that already failed: never run again
        var pendingFollowUp: String? = null              // a skill handed off unfinished work (checkout, tap a video)
        val handledPopups = mutableSetOf<String>()      // each pop-up settled once, never looped on
        /**
         * Stuck (same action again, same skill again, nothing changes, going in circles). Instead
         * of stopping: up to [MAX_RETHINKS] times, ban what failed and tell the model everything
         * already tried so it picks a NEW approach. After that, tell the user what was done and
         * ask what to do. Returns false only when the user gives no guidance (the run then ends).
         */
        suspend fun stuck(why: String, failedKey: String?): Boolean {
            failedKey?.let { banned += it }
            seen.clear(); stalled = 0; repeatBlocks = 0
            if (rethinks < MAX_RETHINKS) {
                rethinks++
                log("   RETHINK $rethinks/$MAX_RETHINKS: $why")
                progress.step("Trying a different approach", why)
                scratchpad += "RETHINK ($rethinks of $MAX_RETHINKS): $why. That approach is BANNED.\n" +
                    "Already tried: ${triedSoFar(scratchpad)}.\n" +
                    "Pick a DIFFERENT approach: another element on the screen, scroll to find it, go back, " +
                    "a different skill or app, or ask_user for missing information."
                return true
            }
            if (askedUser) return false
            askedUser = true
            val question = "I'm stuck. So far I have ${triedSoFar(scratchpad, spoken = true)}. ${lastBlocker(scratchpad)} What should I do next?"
            val id = progress.step("Asking you what to do", why, StepStatus.WAITING_FOR_USER)
            val answer = voice.ask(question)
            if (answer.isNullOrBlank() || answer.lowercase().trim() in CANCEL_WORDS) {
                progress.update(id, StepStatus.FAILED)
                return false
            }
            progress.update(id, StepStatus.DONE, detail = answer)
            log("   user guidance: $answer")
            goal = "$goal. USER GUIDANCE (follow this): $answer"
            scratchpad += "USER GUIDANCE: \"$answer\". Follow it now; do not repeat banned actions."
            return true
        }

        var doneWhen: (() -> Boolean)? = null
        var doneSpeech: String? = null
        val startedAt = android.os.SystemClock.uptimeMillis()
        var userTimeMs = 0L // time spent waiting for the user's spoken answers: not on the clock
        repeat(MAX_STEPS) {
            if (doneWhen?.invoke() == true) return end(true, doneSpeech ?: "Done.")
            if (android.os.SystemClock.uptimeMillis() - startedAt - userTimeMs > REQUEST_DEADLINE_MS) {
                return end(false, "This is taking too long, so I stopped. ${lastBlocker(scratchpad)}".trim())
            }
            val tPerceive = android.os.SystemClock.uptimeMillis()
            var state = awaitContent()
            // Android permission prompts are allowed automatically (user's standing instruction).
            if (state != null && state.packageName in PERMISSION_CONTROLLERS) {
                val id = progress.step("Allowing a permission")
                autoAllow(state)?.let { scratchpad += "Screen: Android permission prompt\nAction: (automatic) $it"; state = awaitContent() }
                progress.update(id, StepStatus.DONE)
            }
            // Pop-ups (notification opt-ins, promos, location, ratings…): settle them here with a safe
            // default, or ask the user at once, instead of spending a ~20 s model step on each.
            if (state != null && state.overlay != null && handledPopups.add(state.overlay!!)) {
                handlePopup(state, goal)?.let { note ->
                    scratchpad += "Screen: pop-up ${state.overlay}\nAction: (automatic) $note"
                    state = awaitContent()
                }
            }
            // Home-screen icons only distract the model from skills; show real app screens only.
            // NOTE: this line is read as an instruction. It must NOT say "open an app", or every
            // skill request from the home screen turns into open_app.
            val screenText = when {
                state == null -> null
                isLauncher(state.packageName) -> "Home screen. No app is open. Skills work from here; open an app only if the goal needs one."
                state.elements.isEmpty() -> "App: ${state.appLabel ?: state.packageName} (still loading, nothing readable yet)"
                else -> skillContext.screen.toPrompt(state)
            }
            val stepId = progress.step("Thinking", state?.appLabel?.let { "Looking at $it" }.orEmpty())
            val tDecide = android.os.SystemClock.uptimeMillis()
            // Step 1 of a well-known command is chosen deterministically; everything after it (and
            // every other request) goes to the model.
            val routed = if (scratchpad.isEmpty()) preRoute(goal)?.takeIf { r -> tools.any { it.name == r.tool } } else null
            routed?.let { log("   pre-routed: ${it.tool}${it.args}") }
            val step = routed ?: planner.next(goal, tools, scratchpad, screenText, memory)
                ?: run {
                    progress.update(stepId, StepStatus.FAILED)
                    if (!stuck("you gave no usable answer for this screen", null)) {
                        return end(false, "I couldn't work out the next step. ${lastBlocker(scratchpad)}".trim())
                    }
                    return@repeat
                }
            if (step.memory.isNotBlank()) memory = step.memory
            log("step ${scratchpad.size + 1} | screen: ${step.screen} | thought: ${step.thought} | -> ${step.tool}${step.args}${if (step.final) " (final)" else ""}")
            if (step.memory.isNotBlank()) log("   memory: ${step.memory}")
            progress.update(
                stepId,
                if (step.tool in WAITS_FOR_USER) StepStatus.WAITING_FOR_USER else StepStatus.RUNNING,
                title = stepTitle(step, state),
                detail = step.thought.take(120),
            )

            // ---- host-side loop detection: never trust the model to notice it is looping ----
            // Scrolling the same list repeatedly is legitimate (searching for an item), so it is
            // exempt here; a scroll that changes nothing still feeds the stall counter below.
            val key = step.tool + step.args.toSortedMap().toString()
            val times = (seen[key] ?: 0) + 1
            seen[key] = times
            if (key in banned && step.tool !in CYCLE_EXEMPT) {
                progress.update(stepId, StepStatus.FAILED, detail = "Already failed; picking something else")
                if (!stuck("you chose ${step.tool} again, which already failed", key)) return end(false, "Okay, I stopped.")
                return@repeat
            }
            if (step.tool !in CYCLE_EXEMPT && times >= MAX_SAME_ACTION) {
                progress.update(stepId, StepStatus.FAILED, detail = "Repeated the same action")
                if (!stuck("${describe(step, state)} was tried $times times without progress", key)) return end(false, "Okay, I stopped.")
                return@repeat
            }

            val tAct = android.os.SystemClock.uptimeMillis()
            // Deterministic repair for a common small-model loop: asking for the app list again
            // when it is already in history. If the goal names an installed app, open it instead.
            val effective = if (step.tool == "list_apps" && scratchpad.any { it.contains("Action: list_apps") }) {
                appNamedInGoal(goal)?.let { app -> log("   repair: list_apps again -> open_app($app)"); step.copy(tool = "open_app", args = mapOf("app" to app), final = false) } ?: step
            } else repairAppTools(step, state, goal) ?: repairTyping(step, state, goal)
            val outcome = installNotAsked(effective, state, goal)?.let { log("   blocked: $it"); Outcome.Observed(it, ok = false) }
                ?: runGuarded(effective, state) { blocked -> if (blocked) repeatBlocks++ }
            val tEnd = android.os.SystemClock.uptimeMillis()
            if (effective.tool in WAITS_FOR_USER || skills.get(effective.tool)?.risk == Risk.CONFIRM) userTimeMs += tEnd - tAct
            log("   ⏱ step ${scratchpad.size + 1}: perceive=${tDecide - tPerceive}ms decide=${tAct - tDecide}ms act=${tEnd - tAct}ms total=${tEnd - tPerceive}ms")
            lastSkill = effective.tool.takeIf { skills.get(it) != null && outcome is Outcome.Observed && outcome.ok }

            if (outcome is Outcome.Stop) {
                if (outcome.done && usedScreen && verifications < MAX_VERIFICATIONS && screenChangesAfter(skillContext.screen.capture())) {
                    verifications++
                    progress.update(stepId, StepStatus.FAILED, title = "Not finished yet", detail = "The screen changed")
                    scratchpad += NOT_FINISHED.format(step.screen, step.thought)
                    log("   finish rejected: the screen changed after the last action")
                    return@repeat
                }
                // Independent completion check: is every part of the goal really done?
                if (outcome.done && usedScreen) {
                    val lastEntry = scratchpad.lastOrNull().orEmpty()
                    val check = planner.checkDone(
                        goal, memory,
                        lastAction = lastEntry.substringAfter("Action: ", "none").substringBefore('\n'),
                        lastResult = lastEntry.substringAfter("Observation: ", "none").substringBefore('\n'),
                        screen = skillContext.screen.capture()?.let(skillContext.screen::toPrompt),
                    )
                    log("   completion check: done=${check.done} missing=${check.missing} evidence=${check.evidence}")
                    if (!check.done) {
                        completionRejects++
                        progress.update(stepId, StepStatus.FAILED, title = "Not finished yet", detail = check.missing.joinToString())
                        if (completionRejects > MAX_COMPLETION_REJECTS) {
                            return end(false, "I couldn't complete: ${check.missing.joinToString()}.")
                        }
                        scratchpad += "Screen: ${step.screen}\nThought: ${step.thought}\nAction: finish\n" +
                            "Observation: NOT finished yet. Missing: ${check.missing.joinToString()}. Continue with the next missing part."
                        return@repeat
                    }
                }
                progress.update(stepId, if (outcome.done) StepStatus.DONE else StepStatus.FAILED)
                return end(outcome.done, outcome.closing)
            }
            if (effective.tool in UI_TOOL_NAMES || effective.tool in ENTRY_TOOLS || effective.tool == "open_app") usedScreen = true
            outcome as Outcome.Observed
            progress.update(stepId, if (outcome.ok) StepStatus.DONE else StepStatus.FAILED)
            outcome.doneWhen?.let { doneWhen = it; doneSpeech = outcome.spoken.takeIf { s -> s.isNotBlank() && outcome.followUp == null } }
            // Hand-off: from now on the model works on the follow-up goal, on the current screen.
            // Without this the original goal ("play some song") keeps routing back to the skill.
            outcome.followUp?.let { goal = "$it (the user asked: $request)"; pendingFollowUp = it; log("   goal -> $goal") }
            log("   observation: ${outcome.text}")
            if (repeatBlocks >= MAX_REPEAT_BLOCKS) {
                if (!stuck("you keep calling ${effective.tool} again", key)) return end(false, "Okay, I stopped.")
                return@repeat
            }
            // The app list is large: show it for the next decision only, then collapse it. Only
            // collapse an entry whose observation actually IS the list (never a "not run" guard).
            scratchpad.indices.lastOrNull { i -> scratchpad[i].contains("Action: list_apps") && scratchpad[i].contains("Installed apps") }
                ?.let { i -> scratchpad[i] = scratchpad[i].substringBefore("Observation:") + "Observation: (app list was shown; pick a name from it)" }
            var observation = outcome.text
            // Stall recovery: after several useless steps, tell the model plainly, then rethink.
            stalled = if (outcome.ok) 0 else stalled + 1
            if (stalled == STALL_HINT_AT) observation += "\nYou are stuck: the last $stalled steps changed nothing. Change approach or call finish and explain."
            if (stalled >= STALL_STOP_AT) {
                scratchpad += "Screen: ${step.screen}\nAction: ${describe(effective, state)}\nObservation: $observation"
                if (!stuck("the last $stalled steps changed nothing on the screen", key)) return end(false, "Okay, I stopped.")
                return@repeat
            }
            scratchpad += "Screen: ${step.screen}\nThought: ${step.thought}\nAction: ${describe(effective, state)}${if (effective !== step) " (repaired)" else ""}\nObservation: $observation"
            // Entering an app is never the end: the next step must look at where we landed. That
            // covers list_apps/open_link, forms, and any skill whose observation says an app opened.
            // Asking the user is never the end either: their answer still has to be acted on.
            val notTheEnd = effective.tool in ENTRY_TOOLS || effective.tool in FORM_TOOLS || effective.tool in WAITS_FOR_USER ||
                (skills.get(effective.tool) != null && (outcome.text.contains(" is open.") || outcome.text.contains(" Next: ") || outcome.doneWhen != null))
            if (effective.final && outcome.ok && !notTheEnd) {
                // A skill gave us a real success check (music playing): trust it over the model. Give
                // the UI a moment, then either finish for real or tell the model it isn't done yet.
                val check = doneWhen
                if (check != null) {
                    repeat(12) { if (!check()) kotlinx.coroutines.delay(250) }
                    if (check()) return end(true, doneSpeech ?: "Done.")
                    scratchpad[scratchpad.lastIndex] += "\nNOT DONE YET: the goal's result has not happened (nothing is playing). Keep going on the current screen."
                    return@repeat
                }
                if (usedScreen && verifications < MAX_VERIFICATIONS && screenChangesAfter(skillContext.screen.capture())) {
                    verifications++
                    scratchpad += NOT_FINISHED.format(step.screen, step.thought)
                    log("   final step rejected: the screen changed afterwards")
                    return@repeat
                }
                return end(true, outcome.spoken.ifBlank { "Done." })
            }
            // Going in circles: 2 actions alternating over 4 steps, or 3 actions repeating over 6
            // (tap message -> long-press -> back -> tap message ...).
            val actions = scratchpad.filter { it.startsWith("Screen: ") }.map { it.substringAfter("Action: ").substringBefore('\n') }
            val recent = actions.takeLast(4)
            val recent6 = actions.takeLast(6)
            val circling = (recent.size == 4 && recent.distinct().size <= 2) ||
                (recent6.size == 6 && recent6.distinct().size <= 3 && recent6.take(3) == recent6.takeLast(3))
            // Scrolling back and forth through a list is searching, not a loop.
            if (circling && recent.any { !it.startsWith("scroll") }) {
                val loop = (if (recent.distinct().size <= 2) recent else recent6).distinct().joinToString(" and ")
                if (!warnedCircle) {
                    warnedCircle = true
                    scratchpad += "Observation: You are going in circles between $loop. " +
                        "Do something different (e.g. read the text into memory, or use the app's search)."
                } else {
                    warnedCircle = false
                    if (!stuck("you were going in circles between $loop", key)) return end(false, "I was going in circles between $loop.")
                }
            }
        }
        return end(false, "That took too many steps. ${lastBlocker(scratchpad)}".trim())
    }

    /**
     * Runs one step, turning any exception into an observation the model can react to instead of
     * letting it abort the whole request. [onRepeatBlocked] is told when the same-skill-twice guard
     * fired, so the loop can escalate.
     */
    private suspend fun runGuarded(step: AgentStep, state: ScreenState?, onRepeatBlocked: (Boolean) -> Unit): Outcome =
        try {
            val o = run(step, state)
            onRepeatBlocked(o is Outcome.Observed && o.text.startsWith(REPEAT_BLOCKED))
            o
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            log("   step threw ${t.javaClass.simpleName}: ${t.message}")
            Outcome.Observed("failed: ${step.tool} threw ${t.javaClass.simpleName}${t.message?.let { " ($it)" }.orEmpty()}. Try a different approach.", ok = false)
        }

    /**
     * Models type into forms without knowing the value: `type(id)` with no text, or the field's
     * own label ("Enter Phone Number") as the text. If the text is missing or doesn't fit the field
     * (not a number for a phone field, or just the label), ask the user with fill_field instead.
     * Text the user actually said in the goal is kept as is.
     */
    private fun repairTyping(step: AgentStep, state: ScreenState?, goal: String): AgentStep {
        if (step.tool != "type" || state == null) return step
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val emptyInputs = state.elements.filter { it.editable && it.value.isNullOrEmpty() }
        val text = step.args["text"].orEmpty().trim()
        // Typing into a heading ("OTP Verification") while the screen has one empty input: the
        // model means that input. On an OTP screen the value must come from SMS or the user.
        val el = state.elements.firstOrNull { it.id == id && it.editable }
            ?: emptyInputs.singleOrNull()?.also { log("   repair: type target [$id] is not an input -> [${it.id}]") }
            ?: return step
        if (isOtpScreen(state) && !(text.isNotEmpty() && goal.contains(text))) {
            log("   repair: OTP screen -> fill_field")
            return step.copy(tool = "fill_field", args = mapOf("id" to el.id.toString()), final = false)
        }
        // Already there: don't retype or ask; the step becomes "tap the next button".
        val current = el.value?.filter(Char::isLetterOrDigit).orEmpty()
        if (current.isNotEmpty() && (text.isEmpty() || current == text.filter(Char::isLetterOrDigit))) {
            val next = state.elements.firstOrNull { e -> e.clickable && NEXT_BUTTONS.any { e.label.lowercase().startsWith(it) } }
            if (next != null) { log("   repair: field already holds \"${el.value}\" -> tap \"${next.label}\""); return step.copy(tool = "tap", args = mapOf("id" to next.id.toString()), final = false) }
        }
        val saidByUser = text.isNotEmpty() && goal.contains(text, ignoreCase = true)
        val fits = when (el.inputKind) {
            InputKind.PHONE, InputKind.NUMBER -> text.count { it.isDigit() } >= 4 && text.none { it.isLetter() }
            InputKind.EMAIL -> "@" in text
            InputKind.PASSWORD -> false // never typed by us
            else -> text.isNotEmpty()
        }
        val isLabel = text.isNotEmpty() && (el.label.contains(text, ignoreCase = true) || text.contains(fieldName(el.label), ignoreCase = true))
        // Personal data (phone, email, OTP, password, name…) must come from the user (or what they
        // asked us to remember), never the model: small models invent plausible numbers.
        val personal = el.inputKind in PERSONAL_KINDS || PERSONAL_WORDS.any { el.label.contains(it, ignoreCase = true) }
        val keep = if (personal) saidByUser && fits else (fits && !isLabel) || saidByUser
        if (keep && el.inputKind != InputKind.PASSWORD) return step
        log("   repair: type(\"$text\") into ${el.inputKind ?: "input"} \"${el.label}\" -> fill_field")
        return step.copy(tool = "fill_field", args = mapOf("id" to el.id.toString()), final = false)
    }

    /**
     * Blocks two small-model mistakes seen on Blinkit: install_app for something the user never
     * named ("Red Bull", or Blinkit again when it is open), and open_app for an on-screen tab ("Print
     * tab"). The first becomes an observation, the second a tap on that element.
     */
    private fun repairAppTools(step: AgentStep, state: ScreenState?, goal: String): AgentStep? {
        val name = step.args["app"]?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (step.tool == "open_app" && state != null) {
            val onScreen = state.elements.firstOrNull { it.clickable && it.label.equals(name, true) || it.label.equals(name.removeSuffix(" tab"), true) }
            if (onScreen != null && appNamedInGoal(name) == null) {
                log("   repair: open_app(\"$name\") is an on-screen element -> tap")
                return step.copy(tool = "tap", args = mapOf("id" to onScreen.id.toString()))
            }
        }
        return null
    }

    private fun installNotAsked(step: AgentStep, state: ScreenState?, goal: String): String? {
        if (step.tool != "install_app") return null
        val name = step.args["app"]?.trim().orEmpty()
        val asked = name.isNotEmpty() && goal.lowercase().contains(name.lowercase().take(5))
        val alreadyOpen = state?.appLabel?.equals(name, true) == true
        return when {
            alreadyOpen -> "not run: $name is already installed and open. Work on the current screen."
            !asked -> "not run: the user did not ask to install \"$name\". Work inside the current app instead."
            else -> null
        }
    }

    /**
     * The installed app whose label appears in the goal ("open the notes app" → "Notes"), longest
     * label first so "Google Play Store" wins over "Google". Null if none is mentioned.
     */
    private fun appNamedInGoal(goal: String): String? {
        val g = " ${goal.lowercase()} "
        val pm = skillContext.android.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() } }.getOrDefault(emptyList())
            .filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .firstOrNull { g.contains(" ${it.lowercase()} ") }
    }

    /** The actions taken so far, de-duplicated: for the rethink note (model) or the question (user). */
    private fun triedSoFar(scratchpad: List<String>, spoken: Boolean = false): String {
        val actions = scratchpad.mapNotNull { e -> e.substringAfter("Action: ", "").substringBefore('\n').takeIf { it.isNotBlank() } }
            .distinct().takeLast(8)
        if (actions.isEmpty()) return if (spoken) "not done anything yet" else "nothing"
        if (!spoken) return actions.joinToString("; ")
        // "tap(\"Continue\")" -> "tapped Continue", readable when spoken.
        return actions.takeLast(4).joinToString(", then ") { a ->
            val tool = a.substringBefore('(')
            val arg = Regex("\"([^\"]{1,40})").find(a)?.groupValues?.get(1).orEmpty()
            val verb = when (tool) {
                "tap" -> "tapped"; "type" -> "typed"; "open_app" -> "opened"; "scroll" -> "scrolled"; "back" -> "went back"
                "fill_field" -> "filled a field"; "list_apps" -> "listed your apps"; else -> tool.replace('_', ' ')
            }
            if (arg.isNotBlank() && tool != "back") "$verb $arg" else verb
        }
    }

    /** One spoken sentence about what blocked us, from the most recent failed observation. */
    private fun lastBlocker(scratchpad: List<String>): String {
        val obs = scratchpad.asReversed().firstNotNullOfOrNull { e ->
            e.substringAfter("Observation: ", "").takeIf { it.startsWith("failed") || it.contains("NO EFFECT") || it.contains("NOT INSTALLED") }
        } ?: return ""
        val plain = obs.removePrefix("failed: ").substringBefore(". TERMINAL").substringBefore(". Do NOT").substringBefore('\n').take(90)
        return if (plain.isBlank()) "" else "The last problem was: $plain."
    }

    /** A step in plain words for the card: "Opening WhatsApp", "Tapping “Send”", "Typing “I'm late”". */
    private fun stepTitle(step: AgentStep, state: ScreenState?): String {
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val label = id?.let { i -> state?.elements?.firstOrNull { it.id == i }?.label }?.take(40)
        fun quoted(s: String?) = s?.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty()
        return when (step.tool) {
            "tap" -> "Tapping ${quoted(label)}".trim()
            "long_press" -> "Long-pressing ${quoted(label)}".trim()
            "type" -> "Typing ${quoted(step.args["text"]?.take(40))}".trim()
            "enter" -> "Pressing enter"
            "scroll" -> "Scrolling ${step.args["direction"].orEmpty()}".trim()
            "back" -> "Going back"
            "home" -> "Going to the home screen"
            "list_apps" -> "Finding the app"
            "open_link" -> "Opening the link"
            "ask_user" -> "Asking you: ${step.args["question"].orEmpty()}".trimEnd(':', ' ')
            "ask_choice" -> "Asking you to choose"
            "fill_field" -> "Filling in the details"
            "finish" -> "Answering"
            else -> skills.get(step.tool)?.let { skill ->
                // Skill description plus its main argument: "Open an installed app by name · WhatsApp".
                val arg = skill.slots.firstNotNullOfOrNull { step.args[it.name]?.takeIf(String::isNotBlank) }
                listOfNotNull(skill.description.substringBefore('.').take(48), arg?.take(32)).joinToString(" · ")
            } ?: step.tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Taps the most useful "allow" answer on an Android runtime-permission prompt, without asking
     * the model or the user. Returns a description for the history, or null if none was found.
     */
    private suspend fun autoAllow(state: ScreenState): String? {
        val button = ALLOW_ANSWERS.firstNotNullOfOrNull { want ->
            state.elements.firstOrNull { it.clickable && it.label.trim().equals(want, ignoreCase = true) }
        } ?: ALLOW_ANSWERS.firstNotNullOfOrNull { want ->
            state.elements.firstOrNull { it.clickable && it.label.startsWith(want, ignoreCase = true) }
        } ?: return null
        val question = state.elements.firstOrNull { it.label.contains("allow", true) && it.label.endsWith("?") }?.label
        log("   permission prompt: ${question ?: "?"} -> auto-tapping \"${button.label}\"")
        val mark = skillContext.screen.mark()
        skillContext.ui.perform(UiAction.Tap(button.id), state)
        skillContext.screen.awaitSettled(mark, timeoutMs = 2_000)
        voice.speak("Allowed.")
        return "tapped \"${button.label}\" on \"${question ?: "permission prompt"}\""
    }

    /** A splash/loading screen has nothing to act on: wait (up to 10 s) instead of asking the model. */
    private suspend fun awaitContent(): ScreenState? {
        var state = skillContext.screen.capture()
        var waited = 0
        while (state != null && state.elements.isEmpty() && !isLauncher(state.packageName) && waited < LOADING_WAIT_MS) {
            kotlinx.coroutines.delay(500)
            waited += 500
            state = skillContext.screen.capture()
        }
        if (waited > 0) log("   waited ${waited} ms for ${state?.appLabel} to load")
        return state
    }

    private suspend fun run(step: AgentStep, state: ScreenState?): Outcome = when (step.tool) {
        "finish" -> Outcome.Stop(step.args["answer"].orEmpty().ifBlank { "Done." }, done = true)
        "ask_user" -> {
            val q = step.args["question"].orEmpty().ifBlank { "Could you say that again?" }
            val answer = voice.ask(q)
            if (answer == null) Outcome.Stop(NO_ANSWER)
            else Outcome.Observed("User said: \"$answer\"", ok = true)
        }
        "fill_field" -> fillField(step, state)
        "ask_choice" -> askChoice(step, state)
        in UI_TOOL_NAMES -> runUi(step, state)
        lastSkill -> Outcome.Observed(
            "$REPEAT_BLOCKED ${step.tool} was ALREADY done in the previous step and its result is above. " +
                "Do not call it again. Use that result: open_app a name from the list, act on the current screen, or finish.",
            ok = false,
        )
        else -> skills.get(step.tool)?.let { runSkill(it, step.args) }
            ?: Outcome.Observed("Unknown tool ${step.tool}", ok = false)
    }

    /**
     * Collects EVERY empty input on the screen before the agent moves on: the field the model
     * chose first, then the rest top to bottom, passwords last (typed by the user). An OTP is read
     * from the SMS inbox first; a detail the user asked us to remember is typed without asking.
     * Spoken answers are validated for the field's format and re-asked with the reason; the user
     * can say "skip" per field. A field holding a wrong or rejected value is cleared first.
     */
    private suspend fun fillField(step: AgentStep, state: ScreenState?, afterReset: Boolean = false, resendFirst: Boolean = false): Outcome {
        state ?: return Outcome.Observed("failed: screen not readable", ok = false)
        val chosenId = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        // RESET after failure: a field that already holds a value that is wrong for it (an invented
        // or rejected OTP, a 9-digit phone) or that the app flagged ("Invalid OTP") is cleared and
        // asked again; otherwise the retry would type on top of the bad value.
        val appSaysWrong = state.elements.any { e -> WRONG_INPUT.any { e.label.contains(it, ignoreCase = true) } }
        val splitOtp = otpBoxCount(state) in SPLIT_OTP_BOXES
        val wrong = state.elements.filter { e ->
            if (!e.editable || e.value.isNullOrEmpty() || e.inputKind == InputKind.PASSWORD) return@filter false
            val meaning = contextLabel(e, state)
            val otp = FieldValidator.isOtp(meaning)
            when {
                // One digit per box is expected there: only the app saying "invalid" resets them.
                otp && splitOtp -> appSaysWrong
                appSaysWrong && (e.id == chosenId || otp) -> true
                e.id == chosenId && otp -> true
                // Format check only on fields with a known format, after the same clean-up a spoken
                // answer gets ("98765 43210" / "+91 …" is a fine phone number).
                e.id == chosenId || otp || e.inputKind == InputKind.PHONE || e.inputKind == InputKind.EMAIL ->
                    FieldValidator.problem(FieldValidator.clean(e.value!!, e.inputKind, meaning), e.inputKind, meaning) != null
                else -> false
            }
        }
        if (afterReset) wrong.forEach { log("   reset: could not clear \"${it.value}\"") }
        for (e in wrong.takeIf { !afterReset }.orEmpty()) {
            log("   reset: clearing \"${e.value}\" from ${contextLabel(e, state).ifBlank { e.label }}")
            skillContext.ui.perform(UiAction.TypeText(e.id, ""), state)
        }
        if (wrong.isNotEmpty() && !afterReset) {
            kotlinx.coroutines.delay(400)
            return fillField(step, skillContext.screen.capture() ?: state, afterReset = true, resendFirst = otpNeedsResend(state))
        }
        val empties = state.elements.filter { it.editable && it.value.isNullOrEmpty() }
        if (empties.isEmpty()) {
            val filledNow = state.elements.filter { it.editable && !it.value.isNullOrEmpty() }
            val next = state.elements.firstOrNull { e -> e.clickable && NEXT_BUTTONS.any { e.label.lowercase().startsWith(it) } }
            return if (filledNow.isNotEmpty()) Outcome.Observed(
                "the inputs are already filled (" + filledNow.joinToString { "${fieldName(it.label)} = \"${it.value}\"" } + "). Do NOT fill again." +
                    (next?.let { " NEXT STEP: tap [${it.id}] \"${it.label}\"." } ?: ""),
                ok = true,
            ) else Outcome.Observed("failed: no input field on this screen. Do NOT repeat this.", ok = false)
        }
        val ordered = (empties.filter { it.id == chosenId } + empties.filter { it.id != chosenId })
            .sortedBy { it.inputKind == InputKind.PASSWORD }
        val labels = ordered.map { it.label }

        val filled = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((i, label) in labels.withIndex()) {
            // Ids can shift after typing (fields appear/disappear), so re-find the field by label.
            val now = if (i == 0) state else skillContext.screen.capture() ?: break
            val el = now.elements.firstOrNull { it.editable && it.label == label && it.value.isNullOrEmpty() } ?: continue
            val meaning = contextLabel(el, now)
            val name = fieldName(meaning)
            // OTP: read it from the SMS inbox first (on-device, like Android's autofill), then ask.
            if (FieldValidator.isOtp(meaning)) {
                var sinceMs = System.currentTimeMillis() - OTP_LOOKBACK_MS
                // Expired or rejected code: the old SMS is useless. Ask the app for a new one first.
                if (resendFirst || otpNeedsResend(now)) {
                    if (resendOtp(now)) sinceMs = System.currentTimeMillis() - 5_000
                    else voice.speak("The OTP has expired, and the resend button isn't ready yet.")
                }
                voice.speak("Checking your messages for the OTP.")
                val code = OtpReader.await(skillContext.android, sinceMs, now.appLabel, waitMs = OTP_WAIT_MS)
                if (code != null && FieldValidator.problem(code, el.inputKind, meaning) == null && typeCode(code, el, now)) {
                    voice.speak("Found it in your messages: ${code.toCharArray().joinToString(" ")}.")
                    filled += "OTP = \"$code\" (from SMS)"
                    continue
                }
                log("   otp: none in SMS, asking")
            }
            if (el.inputKind == InputKind.PASSWORD) {
                when (passwordHandOff(name)) {
                    true -> filled += "$name (typed by user)"
                    false -> skipped += name
                    null -> return Outcome.Stop("I stopped at the $name.")
                }
                continue
            }
            // A detail the user asked us to remember ("remember my phone is ...") is used without asking.
            rememberedFor(el.inputKind, name)?.let { saved ->
                val value = SpokenInput.normalize(saved, el.inputKind)
                val m = skillContext.screen.mark()
                if (value.isNotBlank() && skillContext.ui.perform(UiAction.TypeText(el.id, value), now) !is ActionResult.Failure) {
                    skillContext.screen.awaitSettled(m, timeoutMs = 2_000)
                    SpokenInput.readBack(value, el.inputKind)?.let { voice.speak("Using your saved $name, $it.") }
                    filled += "$name = \"$value\" (saved)"
                    continue
                }
            }
            val question = when {
                FieldValidator.isOtp(meaning) -> "What's the OTP you received?"
                i == 0 -> step.args["question"]?.takeIf { it.isNotBlank() && "otp" !in it.lowercase() } ?: "What's your $name?"
                else -> "What's your $name?"
            }
            // ASK → VALIDATE → (re-ask with the reason) → TYPE → VERIFY it is in the field.
            var accepted: String? = null
            var prompt = question
            for (attempt in 1..MAX_FIELD_ATTEMPTS) {
                // One re-ask on silence before giving up: people often answer a beat late.
                val spoken = voice.ask(prompt) ?: voice.ask("Sorry, I didn't catch that. $question")
                    ?: return Outcome.Stop(NO_ANSWER)
                val lower = spoken.lowercase().trim()
                if (lower in CANCEL_WORDS) return Outcome.Stop(CANCELLED)
                if (lower in SKIP_WORDS) break
                if (FieldValidator.isOtp(meaning) && RESEND_ASK.any { it in lower } && spoken.none(Char::isDigit)) {
                    val screenNow = skillContext.screen.capture() ?: now
                    prompt = if (resendOtp(screenNow)) {
                        val code = OtpReader.await(skillContext.android, System.currentTimeMillis() - 5_000, now.appLabel, waitMs = OTP_WAIT_MS)
                        if (code != null) { accepted = code; voice.speak("Found the new OTP in your messages."); break }
                        "I've sent a new OTP. What's the new code?"
                    } else "The resend button isn't ready yet; wait a few seconds. What's the OTP?"
                    continue
                }
                var value = FieldValidator.clean(SpokenInput.normalize(spoken, el.inputKind), el.inputKind, meaning)
                // People read numbers in chunks ("98765 … 43210") and the recognizer ends at the
                // pause. While a digits field is short, keep listening and append instead of rejecting.
                var more = 0
                while (more < 2 && FieldValidator.wantsMoreDigits(value, el.inputKind, meaning)) {
                    val rest = voice.ask("Go on.") ?: break
                    value = FieldValidator.clean(value + SpokenInput.normalize(rest, el.inputKind), el.inputKind, meaning)
                    more++
                }
                val problem = FieldValidator.problem(value, el.inputKind, meaning)
                log("   fill \"$name\" (${el.inputKind}) try $attempt: heard \"$spoken\" -> \"$value\"${problem?.let { " INVALID: $it" }.orEmpty()}")
                if (problem == null) { accepted = value; break }
                prompt = "$problem Please say your $name again."
            }
            val value = accepted ?: run { skipped += "$name (no valid answer)"; null } ?: continue
            if (FieldValidator.isOtp(meaning)) {
                // An OTP may go into one box or one digit per box.
                if (typeCode(value, el, now)) filled += "OTP = \"$value\"" else skipped += "$name (couldn't type)"
                continue
            }
            val mark = skillContext.screen.mark()
            if (skillContext.ui.perform(UiAction.TypeText(el.id, value), now) is ActionResult.Failure) {
                skipped += "$name (couldn't type)"; continue
            }
            skillContext.screen.awaitSettled(mark, timeoutMs = 2_000)
            // Verify: some apps (Zepto) format or reject SET_TEXT; check the field really holds it.
            val after = skillContext.screen.capture()
            val holds = after?.elements?.any { it.editable && (it.value?.filter(Char::isLetterOrDigit)?.contains(value.filter(Char::isLetterOrDigit).takeLast(6)) == true) } ?: false
            if (!holds) {
                log("   fill \"$name\": value not visible in the field after typing")
                skipped += "$name (typed, but the field doesn't show it)"; continue
            }
            SpokenInput.readBack(value, el.inputKind)?.let { voice.speak("Got $it.") }
            filled += "$name = \"$value\""
        }
        // Verify with the app: OTP/login screens say "invalid", "incorrect" or "expired" when they
        // reject a value. Report that as a failure so the next step resets and retries.
        if (filled.isNotEmpty()) {
            kotlinx.coroutines.delay(2_500)
            val after = skillContext.screen.capture()
            val complaint = after?.elements?.firstOrNull { e -> WRONG_INPUT.any { e.label.contains(it, ignoreCase = true) } }?.label
            if (complaint != null) {
                log("   fill rejected by the app: $complaint")
                voice.speak("The app says: $complaint.")
                return Outcome.Observed(
                    "failed: the app rejected what was filled: \"$complaint\". Use fill_field again: it clears the old value and, for an OTP, taps Resend first.",
                    ok = false,
                )
            }
        }
        val summary = buildString {
            if (filled.isNotEmpty()) append("filled ").append(filled.joinToString(", ")).append(". ")
            if (skipped.isNotEmpty()) append("skipped ").append(skipped.joinToString(", ")).append(". ")
            append("All inputs on this screen are handled.")
            // Point the model at the obvious next step so it doesn't re-fill the form.
            val next = skillContext.screen.capture()?.elements?.firstOrNull { e ->
                e.clickable && NEXT_BUTTONS.any { e.label.lowercase().startsWith(it) }
            }
            if (next != null && filled.isNotEmpty()) append(" NEXT STEP: tap [${next.id}] \"${next.label}\".")
            else append(" Continue with the next step of the goal.")
        }
        return Outcome.Observed(summary, ok = filled.isNotEmpty())
    }

    /**
     * The user, not the agent, picks between preference options (addresses, payment methods,
     * sizes, accounts). Reads out up to [MAX_CHOICES] short options, matches the spoken answer
     * ("home", "the second one", "option 1") and taps the chosen element.
     */
    private suspend fun askChoice(step: AgentStep, state: ScreenState?): Outcome {
        state ?: return Outcome.Observed("failed: screen not readable", ok = false)
        val ids = Regex("\\d+").findAll(step.args["options"].orEmpty()).map { it.value.toInt() }.toList()
        val options = ids.mapNotNull { id -> state.elements.firstOrNull { it.id == id } }.distinctBy { it.label }.take(MAX_CHOICES)
        if (options.size < 2) {
            return Outcome.Observed("failed: ask_choice needs 2+ option ids from the current screen in \"options\", e.g. \"14,16\"", ok = false)
        }
        val names = options.map { shortOption(it.label) }
        val question = step.args["question"]?.takeIf { it.isNotBlank() } ?: "Which one should I choose?"
        val prompt = question + " " + names.mapIndexed { i, n -> "Option ${i + 1}: $n." }.joinToString(" ")

        var picked: Int? = null
        for (attempt in 0..1) {
            val answer = voice.ask(if (attempt == 0) prompt else "Sorry, which option? Say the number or the name.")
                ?: continue
            if (answer.lowercase().trim() in CANCEL_WORDS) return Outcome.Stop(CANCELLED)
            picked = matchChoice(answer, names)
            log("   choice: heard \"$answer\" -> ${picked?.let { names[it] }}")
            if (picked != null) break
        }
        val index = picked ?: return Outcome.Stop("I couldn't tell which option you wanted, so I stopped.")
        val chosen = options[index]
        voice.speak("Okay, ${names[index]}.")
        val tap = step.copy(tool = "tap", args = mapOf("id" to chosen.id.toString()))
        val result = runUi(tap, state)
        return if (result is Outcome.Observed) result.copy(text = "user chose \"${names[index]}\"; ${result.text}") else result
    }

    /**
     * Default decision for a pop-up, without the model:
     * - part of the goal (its text mentions the goal) or consequential (pay, terms, OTP, delete): leave
     *   it to the normal flow, where consequential taps are confirmed with the user;
     * - known "get out of the way" answer (No thanks, Not now, Skip, Later, Close…): tap it;
     * - known "go ahead" answer that is harmless (OK, Got it, Allow, Enable location, Continue): tap it;
     * - anything else with a few buttons: ask the user right away which one, and tap it.
     * Returns what was done (for the history), or null if the pop-up was left alone.
     */
    private suspend fun handlePopup(state: ScreenState, goal: String): String? {
        val buttons = state.elements.filter { it.inOverlay && it.clickable && it.label.isNotBlank() }
        if (buttons.isEmpty()) return null
        val text = state.elements.filter { it.inOverlay }.joinToString(" ") { it.label.lowercase() }
        val goalWords = goal.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }
        if (goalWords.count { it in text } >= 2) return null // the pop-up IS the task (e.g. the item sheet)
        if (buttons.any { b -> CONSEQUENTIAL.any { b.label.lowercase().contains(it) } }) return null
        fun pick(words: List<String>) = words.firstNotNullOfOrNull { w -> buttons.firstOrNull { it.label.trim().equals(w, true) } }
            ?: words.firstNotNullOfOrNull { w -> buttons.firstOrNull { it.label.lowercase().startsWith(w.lowercase()) } }
        // Location is needed to go ahead in delivery/maps apps: enable it. Everything else: dismiss first.
        val wantsLocation = "location" in text
        val choice = if (wantsLocation) pick(POPUP_ACCEPT) ?: pick(POPUP_DISMISS)
        else pick(POPUP_DISMISS) ?: buttons.firstOrNull { it.closesOverlay } ?: pick(POPUP_ACCEPT)
        if (choice != null) {
            log("   pop-up ${state.overlay}: default -> \"${choice.label}\"")
            tapQuick(choice, state)
            return "tapped \"${choice.label}\" on ${state.overlay}"
        }
        // Unknown: ask now (short), with the real options.
        val options = buttons.distinctBy { it.label }.take(MAX_CHOICES)
        val names = options.map { shortOption(it.label) }
        val answer = voice.ask("A pop-up says: ${state.overlay?.substringAfter('"')?.substringBefore('"')?.take(60)}. " +
            names.mapIndexed { i, n -> "Option ${i + 1}: $n." }.joinToString(" ") + " Which one?") ?: return null
        val idx = matchChoice(answer, names) ?: return null.also { log("   pop-up: answer \"$answer\" matched nothing") }
        tapQuick(options[idx], state)
        return "user chose \"${names[idx]}\" on ${state.overlay}"
    }

    private suspend fun tapQuick(e: UiElement, state: ScreenState) {
        val m = skillContext.screen.mark()
        skillContext.ui.perform(UiAction.Tap(e.id, touch = true), state)
        skillContext.screen.awaitSettled(m, timeoutMs = 2_500)
    }

    /** "Home, 2653 3rd floor, C2 Vasant kunj, ..." -> "Home, 2653 3rd floor". */
    private fun shortOption(label: String) =
        label.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ").take(45)

    /** Ordinals ("second", "option 2", "number two") first, then the best word overlap with a name. */
    private fun matchChoice(answer: String, names: List<String>): Int? {
        val a = answer.lowercase()
        val ordinals = listOf("first|one|1st", "second|two|2nd", "third|three|3rd", "fourth|four|4th", "fifth|five|5th")
        for ((i, pattern) in ordinals.withIndex()) {
            if (i < names.size && Regex("\\b(option |number )?(${pattern}|${i + 1})\\b").containsMatchIn(a)) return i
        }
        val words = a.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val scores = names.map { n -> n.lowercase().split(Regex("[^a-z0-9]+")).count { it in words } }
        val best = scores.maxOrNull() ?: 0
        return if (best > 0 && scores.count { it == best } == 1) scores.indexOf(best) else null
    }

    /** true = user typed it, false = skipped, null = stop the task. Never heard or typed by us. */
    private suspend fun passwordHandOff(name: String): Boolean? {
        voice.speak("Please type your $name yourself, then say done.")
        repeat(3) {
            val heard = voice.listen(timeoutMs = 180_000)?.lowercase().orEmpty()
            if (listOf("done", "ok", "next", "finished").any { it in heard }) return true
            if (SKIP_WORDS.any { it in heard }) return false
            if (CANCEL_WORDS.any { it in heard }) return null
        }
        return null
    }

    /**
     * What an input is for, in words. Many apps leave OTP boxes unlabeled ("input (number, empty)")
     * and put the meaning in the heading ("OTP Verification"); without this the user is asked
     * "What's your this field?" and the OTP rules never apply.
     */
    private fun contextLabel(el: UiElement, state: ScreenState): String {
        if (el.label.isNotBlank()) return el.label
        val text = headings(state)
        val otpHeading = OTP_WORDS.any { it in text }
        return when {
            el.inputKind == InputKind.EMAIL -> "email"
            // A phone input on a login screen that "sends an OTP" is still the phone number.
            el.inputKind == InputKind.PHONE && ("phone" in text || "mobile" in text || !otpHeading) -> "phone number"
            otpHeading -> "OTP"
            else -> ""
        }
    }

    /** The screen's text, without buttons: a "Get OTP" button doesn't make a screen an OTP screen. */
    private fun headings(state: ScreenState) =
        state.elements.filter { !it.clickable && !it.editable }.joinToString(" ") { it.label.lowercase() }

    /** How many inputs on the screen are OTP boxes (one box, or one per digit). */
    private fun otpBoxCount(state: ScreenState) =
        state.elements.count { it.editable && FieldValidator.isOtp(contextLabel(it, state)) }

    /**
     * Types an OTP: into [el], or digit by digit when the screen has one box per digit (4–8 boxes,
     * as many as the code has digits). True if typed.
     */
    private suspend fun typeCode(code: String, el: UiElement, now: ScreenState): Boolean {
        val boxes = now.elements.filter { it.editable && FieldValidator.isOtp(contextLabel(it, now)) }
        val mark = skillContext.screen.mark()
        val ok = if (boxes.size in SPLIT_OTP_BOXES && boxes.size == code.length && boxes.any { it.id == el.id }) {
            log("   otp: ${boxes.size} boxes, one digit each")
            boxes.withIndex().all { (i, box) -> skillContext.ui.perform(UiAction.TypeText(box.id, code[i].toString()), now) !is ActionResult.Failure }
        } else {
            skillContext.ui.perform(UiAction.TypeText(el.id, code), now) !is ActionResult.Failure
        }
        if (ok) skillContext.screen.awaitSettled(mark, timeoutMs = 2_000)
        return ok
    }

    /** The code on screen is expired or was rejected ("OTP expired", "Invalid OTP"). */
    private fun otpNeedsResend(state: ScreenState): Boolean {
        val text = state.elements.joinToString(" ") { it.label.lowercase() }
        return listOf("expired", "invalid", "incorrect", "wrong otp", "try again").any { it in text }
    }

    /** Taps "Resend OTP" / "Resend code" if it is available (not a countdown). True if tapped. */
    private suspend fun resendOtp(state: ScreenState): Boolean {
        val button = state.elements.firstOrNull { e ->
            e.clickable && e.label.lowercase().let { l -> ("resend" in l || "send again" in l || "get new" in l || l == "send sms" || l == "send otp" || l == "get otp") && !Regex("\\d+\\s*s\\b| in \\d").containsMatchIn(l) }
        } ?: return false
        log("   otp: expired/rejected -> tapping \"${button.label}\"")
        val m = skillContext.screen.mark()
        skillContext.ui.perform(UiAction.Tap(button.id, touch = true), state)
        skillContext.screen.awaitSettled(m, timeoutMs = 2_000)
        voice.speak("That OTP expired, so I asked for a new one.")
        return true
    }

    private fun isOtpScreen(state: ScreenState) = headings(state).let { t -> OTP_WORDS.any { it in t } }

    /** A saved personal detail for this kind of field (see the remember skill), if there is one. */
    private fun rememberedFor(kind: InputKind?, name: String): String? {
        val ctx = skillContext.android
        return when {
            kind == InputKind.PHONE || "phone" in name || "mobile" in name ->
                RememberedFacts.find(ctx, "phone", "phone_number", "mobile", "mobile_number", "number")
            kind == InputKind.EMAIL || "email" in name -> RememberedFacts.find(ctx, "email", "email_address")
            "name" in name && "user" !in name -> RememberedFacts.find(ctx, "name", "full_name")
            else -> null
        }
    }

    /** "Country code, none selected" -> "country code"; "Enter your phone number" -> "phone number". */
    private fun fieldName(label: String) =
        label.substringBefore(',').substringBefore(" · ").trim().lowercase()
            .replace(Regex("^(enter|type|input|provide|add|your)\\s+(your\\s+)?"), "")
            .ifBlank { "this field" }

    private suspend fun runSkill(skill: Skill, given: Map<String, String>): Outcome {
        val args = given.filterValues { it.isNotBlank() }
        val missing = skill.slots.filter { it.required && args[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return Outcome.Observed(
                "not run: missing ${missing.joinToString { it.name }}. Ask the user with ask_user (e.g. \"${missing.first().question}\").",
                ok = false,
            )
        }
        val mark = skillContext.screen.mark()
        return when (val r = skill.execute(skillContext, args)) {
            is ActionResult.Success -> {
                // An app launch: wait until that app is actually in front and drawn, so the
                // next step sees ITS screen, not the launcher it came from.
                val landed = r.openedPackage?.let { pkg ->
                    val settle = skillContext.screen.awaitSettled(mark, expectPackage = pkg, timeoutMs = 6_000)
                    val now = skillContext.screen.capture()
                    " " + landing(settle, now)
                }.orEmpty()
                val next = r.followUpGoal?.let { " Next: $it" }.orEmpty()
                val data = r.observation?.let { "\n$it" }.orEmpty()
                Outcome.Observed(
                    "ok. ${r.message}.$landed$next$data".replace("ok. .", "ok.").trim(),
                    ok = true, spoken = r.message, doneWhen = r.doneWhen, followUp = r.followUpGoal,
                )
            }
            is ActionResult.Failure -> Outcome.Observed("failed: ${terminalize(r.reason)}", ok = false)
        }
    }

    /**
     * Permanent failures (nothing the model can do differently on this device) are marked TERMINAL
     * so the prompt's stop condition applies. Classified here, once, so every skill benefits.
     */
    private fun terminalize(reason: String): String {
        if (reason.contains("TERMINAL")) return reason
        val l = reason.lowercase()
        val permanent = PERMANENT_FAILURES.any { l.contains(it) }
        return if (permanent) "$reason. TERMINAL: do not retry this; use finish to tell the user what is needed." else reason
    }

    private suspend fun runUi(step: AgentStep, state: ScreenState?): Outcome {
        if (state == null) {
            return Outcome.Stop("Screen control is off, so I can't use apps. Please turn it on in accessibility settings.")
        }
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val action = when (step.tool) {
            "tap" -> id?.let { UiAction.Tap(it) }
            "long_press" -> id?.let { UiAction.LongPress(it) }
            "type" -> id?.let { UiAction.TypeText(it, step.args["text"].orEmpty()) }
            "enter" -> UiAction.PressEnter
            "scroll" -> UiAction.Scroll(id, direction(step.args["direction"]))
            "back" -> UiAction.Back
            "home" -> UiAction.Home
            else -> null
        } ?: return Outcome.Observed("failed: ${step.tool} needs \"id\" = a NUMBER from the current screen list", ok = false)

        val mark = skillContext.screen.mark()
        val result = skillContext.ui.perform(action, state)   // returns once the action/gesture callback fired
        if (result is ActionResult.Failure) {
            return Outcome.Observed("failed: ${result.reason}. Do NOT repeat this; pick a different element or action.", ok = false)
        }
        var settle = skillContext.screen.awaitSettled(mark)   // UI reacted, then went quiet
        var after = skillContext.screen.capture()
        // Some apps accept the accessibility click but ignore it, and a spinner makes the UI
        // look like it reacted. Judge by the element list: if nothing changed, touch for real.
        if (action is UiAction.Tap && after != null && after.elements == state.elements) {
            log("   tap had no visible effect; retrying as a real touch")
            val retryMark = skillContext.screen.mark()
            skillContext.ui.perform(action.copy(touch = true), state)
            settle = skillContext.screen.awaitSettled(retryMark)
            after = skillContext.screen.capture()
        }
        val target = id?.let { i -> state.elements.firstOrNull { it.id == i }?.label }?.let { " \"$it\"" }.orEmpty()
        // Typing: some apps (Zepto) raise no UI events for SET_TEXT, so "no events" is not "no
        // effect". Look at the field itself; if it now holds the text, the type worked.
        if (action is UiAction.TypeText) {
            val want = action.text.filter(Char::isLetterOrDigit).lowercase()
            val latest = skillContext.screen.capture() ?: after
            val holds = want.isNotEmpty() && latest?.elements?.any { e ->
                e.editable && e.value?.filter(Char::isLetterOrDigit)?.lowercase()?.let { v -> v.isNotEmpty() && (want.startsWith(v) || v.contains(want)) } == true
            } == true
            if (holds) {
                val shown = latest?.elements?.firstOrNull { it.editable && !it.value.isNullOrEmpty() }?.value
                return Outcome.Observed("type$target done; the field now shows \"$shown\". Now press enter or tap the matching suggestion or the Continue/Search button.", ok = true)
            }
        }
        val unchanged = settle == Settle.UNCHANGED || (after != null && after.elements == state.elements)
        return if (unchanged) Outcome.Observed("${step.tool}$target had NO EFFECT, screen unchanged", ok = false)
        else {
            val hint = when {
                step.tool == "type" -> " Now press enter or tap the matching suggestion."
                state.overlay != null && after?.overlay == null -> " The overlay is closed."
                state.overlay != null && after?.overlay != null -> " The overlay ${after.overlay} is still open."
                state.overlay == null && after?.overlay != null -> " An overlay opened: ${after.overlay}."
                else -> ""
            }
            Outcome.Observed("${step.tool}$target done. ${landing(settle, after, before = state)}$hint", ok = true)
        }
    }

    /** Where we ended up, in words the model can use: "Now in Google Play Store (new screen)." */
    private fun landing(settle: Settle, now: ScreenState?, before: ScreenState? = null): String {
        val app = now?.appLabel ?: now?.packageName ?: "unknown app"
        val where = when {
            before != null && now != null && now.packageName != before.packageName -> "Switched to $app"
            else -> "Now in $app"
        }
        return when (settle) {
            Settle.OPENED -> "$app is open."
            Settle.CHANGED -> "$where (screen changed)."
            Settle.TIMEOUT -> "$where (still loading or animating)."
            Settle.UNCHANGED -> "$where (nothing changed)."
        }
    }

    private fun isLauncher(pkg: String): Boolean {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val launcher = skillContext.android.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        return pkg == launcher || pkg == skillContext.android.packageName
    }

    private fun direction(s: String?) =
        UiAction.Direction.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UiAction.Direction.DOWN

    /**
     * History shows WHAT was acted on, never the numeric id: ids are only valid for the
     * screen they came from, and a small model will happily reuse a stale one.
     */
    private fun describe(step: AgentStep, state: ScreenState?): String {
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val label = id?.let { i -> state?.elements?.firstOrNull { it.id == i }?.label }
        if (step.tool !in UI_TOOL_NAMES || id == null) return step.tool + formatArgs(step.args)
        val rest = step.args.filterKeys { it != "id" }
        val target = "\"${label ?: "missing element"}\""
        return when (step.tool) {
            "type" -> "type(\"${rest["text"].orEmpty()}\" into $target)"
            else -> "${step.tool}($target${if (rest.isEmpty()) "" else ", " + rest.entries.joinToString { "${it.key}=${it.value}" }})"
        }
    }

    private fun formatArgs(args: Map<String, String>) =
        if (args.isEmpty()) "()" else args.entries.joinToString(", ", "(", ")") { "${it.key}=\"${it.value}\"" }

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        private const val TAG = "Assistant"
        private const val MAX_STEPS = 40
        /** Wall-clock budget for one request, not counting time spent waiting for the user. */
        private const val REQUEST_DEADLINE_MS = 600_000L
        /** The same tool+args this many times in one request is a loop. */
        private const val MAX_SAME_ACTION = 3
        /** The same-skill-twice guard firing this often means the model can't move on. */
        private const val MAX_REPEAT_BLOCKS = 2
        /** Consecutive failed observations: tell the model it's stuck, then rethink. */
        private const val STALL_HINT_AT = 3
        private const val STALL_STOP_AT = 5
        /** "Stuck → new approach" rounds before asking the user what to do. */
        private const val MAX_RETHINKS = 3
        /** Prefix of the same-skill-twice observation (counted by the loop guard). */
        private const val REPEAT_BLOCKED = "not run (repeat):"
        /** Leading filler STT hears in spoken requests; removed from the goal line. */
        private val FILLER = listOf(
            "can you", "could you", "would you", "will you", "please", "i want to", "i want you to",
            "i need you to", "i'd like to", "i would like to", "hey", "um", "uh", "so",
        )
        /** Words that make "open X ..." a task rather than just opening an app. */
        private val TASK_WORDS = setOf("and", "then", "to", "for", "search", "with", "on", "in", "about", "play", "send", "call", "message", "find")
        /** Substrings of skill failure reasons that mean "nothing to retry on this device". */
        private val PERMANENT_FAILURES = listOf(
            "not installed", "couldn't find an app", "no app can handle", "couldn't find", "in your contacts",
            "isn't available on this device", "no flashlight", "doesn't seem to be installed", "couldn't find a calendar app",
            "couldn't find an email app", "no app available",
        )

        private fun p(name: String, desc: String, required: Boolean = true) = SlotSpec(name, desc, required, question = "")

        val UI_TOOLS = listOf(
            ToolSpec("tap", "tap a screen element", listOf(p("id", "element id"))),
            ToolSpec("long_press", "long-press a screen element", listOf(p("id", "element id"))),
            ToolSpec("type", "type text into an input", listOf(p("id", "input element id"), p("text", "text to type"))),
            ToolSpec("enter", "press enter/search on the focused input"),
            ToolSpec("scroll", "scroll the screen", listOf(p("direction", "up, down, left or right"), p("id", "list id", required = false))),
            ToolSpec("back", "go back"),
            ToolSpec("home", "go to the home screen"),
        )
        val TALK_TOOLS = listOf(
            ToolSpec(
                "ask_choice",
                "when the screen offers several options that depend on the user's preference (addresses, payment methods, sizes, variants, accounts), ask the user which one and tap it",
                listOf(p("question", "short spoken question, e.g. Which address should I use?"), p("options", "comma-separated ids of the choices, e.g. 14,16")),
            ),
            ToolSpec(
                "fill_field",
                "fill ALL empty inputs on this screen (name, phone, email, OTP, address, password): saved details and SMS codes are used first, otherwise the user is asked by voice",
                listOf(p("id", "input element id"), p("question", "short spoken question, e.g. What's your phone number?")),
            ),
            ToolSpec("ask_user", "ask the user a question (missing info, or confirm before send/pay/delete)", listOf(p("question", "short spoken question"))),
            ToolSpec("finish", "end the request; also use it to answer questions yourself", listOf(p("answer", "short spoken reply"))),
        )
        private val UI_TOOL_NAMES = UI_TOOLS.map { it.name }.toSet()
        /** Tools that wait for the user to answer (shown as "waiting for you" on the card). */
        private val WAITS_FOR_USER = setOf("ask_user", "ask_choice", "fill_field")
        /** Tools that may legitimately repeat with identical args. */
        private val CYCLE_EXEMPT = WAITS_FOR_USER + setOf("finish", "scroll")
        private val ENTRY_TOOLS = setOf("list_apps", "open_link")
        /** Filling a form is never the end of a task: the next step submits it. */
        private val FORM_TOOLS = setOf("fill_field", "type")
        private const val LOADING_WAIT_MS = 10_000
        private const val MAX_CHOICES = 5
        private const val MAX_COMPLETION_REJECTS = 3
        private const val MAX_VERIFICATIONS = 2
        private const val VERIFY_MS = 3_000L
        private const val NOT_FINISHED = "Screen: %s\nThought: %s\nAction: finish\n" +
            "Observation: NOT finished: after your last action the screen changed. Look at the current screen and continue."
        private const val NO_ANSWER = "I didn't hear an answer, so I stopped."
        private const val CANCELLED = "Okay, cancelled."
        /** Pop-up buttons with a real effect: the pop-up policy never taps these on its own. */
        private val CONSEQUENTIAL = listOf(
            "agree", "accept", "send otp", "get otp", "verify", "pay", "place order", "buy now",
            "confirm order", "subscribe", "checkout", "proceed to pay",
        )
        /** Pop-up answers that just get it out of the way (tried first). Never "Cancel": it can cancel an order. */
        private val POPUP_DISMISS = listOf(
            "No, thanks", "No thanks", "Not now", "Maybe later", "Later", "Remind me later", "Skip", "Skip for now",
            "Close", "Dismiss", "Not interested", "No", "Don't allow", "Deny",
        )
        /** Harmless "go ahead" answers, used when there is no dismiss option. */
        private val POPUP_ACCEPT = listOf("OK", "Okay", "Got it", "Continue", "Allow", "Enable", "Turn on", "While using the app", "Done")
        /** Re-asks for one form field before giving up on it. */
        private const val MAX_FIELD_ATTEMPTS = 3
        /** User telling us the OTP is unusable: tap Resend instead of treating it as a wrong answer. */
        private val RESEND_ASK = listOf("expire", "resend", "send again", "didn't get", "did not get", "not received", "didn't receive", "new otp", "new code")
        /** On-screen text meaning the last input was rejected, so it must be cleared and redone. */
        private val WRONG_INPUT = listOf("invalid", "incorrect", "wrong otp", "wrong code", "try again", "not valid", "doesn't match", "expired")
        /** OTP SMS must be newer than this (the code was sent when the previous screen's button was tapped). */
        private const val OTP_LOOKBACK_MS = 180_000L
        private const val OTP_WAIT_MS = 20_000L
        private val OTP_WORDS = listOf("otp", "verification code", "one time password")
        /** One input per digit: 4 to 8 boxes. */
        private val SPLIT_OTP_BOXES = 4..8
        private val PERSONAL_KINDS = setOf(InputKind.PHONE, InputKind.EMAIL, InputKind.PASSWORD)
        private val PERSONAL_WORDS = listOf("phone", "mobile", "email", "otp", "code", "password", "pin", "name", "address")
        private val NEXT_BUTTONS = listOf("continue", "next", "get otp", "send otp", "verify", "submit", "proceed", "login", "log in", "sign in")
        private val PERMISSION_CONTROLLERS = setOf(
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        )
        /** Best "allow" answer first. */
        private val ALLOW_ANSWERS = listOf("While using the app", "Allow", "Only this time", "Allow all", "Allow access")
        private val SKIP_WORDS = setOf("skip", "skip it", "leave it", "no", "none", "not needed", "next one")
        private val CANCEL_WORDS = setOf("cancel", "stop", "stop it")
    }
}
