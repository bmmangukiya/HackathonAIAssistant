package com.hackathon.assistant.core

/** Generic, app-agnostic UI operations. Element ids come from the latest [ScreenState]. */
sealed interface UiAction {
    /** [touch]: skip the accessibility click and send a real touch at the element's center. */
    data class Tap(val elementId: Int, val touch: Boolean = false) : UiAction
    data class LongPress(val elementId: Int) : UiAction
    data class TypeText(val elementId: Int, val text: String) : UiAction
    data class Scroll(val elementId: Int?, val direction: Direction) : UiAction
    /** Keyboard enter/search on the focused field. */
    data object PressEnter : UiAction
    data object Back : UiAction
    data object Home : UiAction
    data object OpenNotifications : UiAction

    enum class Direction { UP, DOWN, LEFT, RIGHT }
}

/** Executes [UiAction]s against the foreground app. Implemented by :perception. */
interface UiController {
    suspend fun perform(action: UiAction, on: ScreenState): ActionResult
}

sealed interface ActionResult {
    /**
     * [followUpGoal]: the skill got the user to the right screen (e.g. search results) and the
     * rest ("tap the first video") is handed to UI navigation. [doneWhen] lets the skill end
     * that navigation early from real device state (e.g. music started), which the model can't see.
     */
    data class Success(
        val message: String = "",
        val followUpGoal: String? = null,
        val doneWhen: (() -> Boolean)? = null,
        /** Package the skill brought to the front, so the agent can wait for it to appear. */
        val openedPackage: String? = null,
        /** Data for the model only (e.g. the app list); [message] is what gets spoken. */
        val observation: String? = null,
    ) : ActionResult
    data class Failure(val reason: String) : ActionResult
}
