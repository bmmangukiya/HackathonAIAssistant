package com.hackathon.assistant.core

import android.content.Context

/**
 * A registered, deterministic capability ("open app", "call contact", "set alarm").
 *
 * Skills are tried before free-form UI navigation: they are faster, cheaper and far more
 * reliable than asking a small model to drive the screen. Missing required slots are asked
 * for by voice before [execute] runs.
 */
interface Skill {
    /** snake_case, unique, shown to the model. */
    val id: String
    /** One line the model reads to decide whether this skill fits. */
    val description: String
    val slots: List<SlotSpec>
    /** Sample utterances; used in the routing prompt and as test cases. */
    val examples: List<String>
    /** CONFIRM skills are read back to the user and need a spoken "yes" before running. */
    val risk: Risk get() = Risk.SAFE

    suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult

    /**
     * Whether this skill can work on this device right now (app installed, logged in...).
     * Unavailable skills are hidden from the model for that request.
     */
    fun isAvailable(context: Context): Boolean = true
}

data class SlotSpec(
    val name: String,
    val description: String,
    val required: Boolean = true,
    /** Spoken when the slot is missing, e.g. "Who should I call?" */
    val question: String,
)

enum class Risk { SAFE, CONFIRM }

class SkillContext(
    val android: Context,
    val screen: ScreenReader,
    val ui: UiController,
    val voice: VoiceIO,
)

/** Lookup for all skills. Implemented by :actions. */
interface SkillRegistry {
    fun all(): List<Skill>
    fun get(id: String): Skill?
}
