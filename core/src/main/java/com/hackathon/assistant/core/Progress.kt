package com.hackathon.assistant.core

/** One visible step of a task, for the card's stepper ("Opening WhatsApp", "Tapping “Send”"). */
data class TaskStep(
    val id: Int,
    val title: String,
    val detail: String = "",
    val status: StepStatus = StepStatus.RUNNING,
)

enum class StepStatus { RUNNING, DONE, FAILED, WAITING_FOR_USER }

/**
 * Where a task reports its steps. The agent and the answer pipeline both write here; the card
 * shows it. [step] starts a new running step and completes the previous running one.
 */
interface TaskProgress {
    /** A new task begins (clears the previous steps). */
    fun start(request: String)

    /** Starts a step; returns its id for [update]. */
    fun step(title: String, detail: String = "", status: StepStatus = StepStatus.RUNNING): Int

    /** Changes a step's status, and optionally its title/detail. */
    fun update(id: Int, status: StepStatus, title: String? = null, detail: String? = null)

    /** The task ended; any running step is completed ([success]) or failed. */
    fun finish(success: Boolean)

    /** Reports nothing (for callers without a card). */
    object None : TaskProgress {
        override fun start(request: String) = Unit
        override fun step(title: String, detail: String, status: StepStatus) = 0
        override fun update(id: Int, status: StepStatus, title: String?, detail: String?) = Unit
        override fun finish(success: Boolean) = Unit
    }
}
