package com.hackathon.assistant.core

/** Raw on-device text generation. Implemented by :llm. */
interface LocalLlm {
    val isLoaded: Boolean
    suspend fun load()
    /** If [jsonSchema] is set, decoding is constrained so the output always matches it. */
    suspend fun generate(prompt: String, maxTokens: Int = 256, jsonSchema: String? = null): String

    /**
     * A long-lived conversation whose [system] prompt is processed once and kept in the model's
     * KV cache; each [LlmChat.send] then only processes the new text.
     */
    suspend fun openChat(system: String, maxTokens: Int, jsonSchema: String?): LlmChat
    fun close()
}

interface LlmChat {
    /** [jsonSchema] overrides the chat's default output format for this one message. */
    suspend fun send(text: String, jsonSchema: String? = null): String
    /** Tokens held in the conversation so far (system + all turns). */
    val tokenCount: Int
    fun close()
}

/** Anything the agent can call: a skill, a screen action, or a conversation move. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<SlotSpec> = emptyList(),
    /** Real-world effect (call, send, pay): the model must ask_user before using it. */
    val asksFirst: Boolean = false,
)

/**
 * One ReAct step: what the model understands the current screen to be, why it acts, which
 * tool, with what, and whether this finishes the request.
 */
data class AgentStep(
    val screen: String,
    val thought: String,
    /** The model's running memory: facts and progress it needs later (rewritten every step). */
    val memory: String = "",
    val tool: String,
    val args: Map<String, String>,
    val final: Boolean,
)

/** Chooses the next ReAct step. Implemented by :llm on top of [LocalLlm]. */
interface Planner {
    /**
     * @param scratchpad earlier "Thought / Action / Observation" lines, oldest first.
     * @param screen the translated current screen, or null if unavailable.
     * @return null if the model produced nothing usable (even after a retry).
     */
    suspend fun next(
        goal: String,
        tools: List<ToolSpec>,
        scratchpad: List<String>,
        screen: String?,
        /** The model's memory from its previous step; shown back every step. */
        memory: String = "",
    ): AgentStep?

    /**
     * Before accepting "finish": is the goal really complete? Judged from the goal, the memory,
     * the last action and its result, and the current screen.
     */
    suspend fun checkDone(goal: String, memory: String, lastAction: String, lastResult: String, screen: String?): Completion

    /** Idle-time warm-up for the next request (e.g. pre-process the fixed system prompt). */
    suspend fun prepare(tools: List<ToolSpec>) {}
}

data class Completion(val done: Boolean, val missing: List<String>, val evidence: String)
