package com.hackathon.assistant.core

/**
 * A compact, LLM-friendly view of what is on screen.
 *
 * Produced by the perception layer from the raw accessibility tree. Only elements a user
 * could read or interact with survive; wrappers, invisible nodes and duplicates are dropped.
 * [UiElement.id] is a small integer that is stable only within one [ScreenState] snapshot —
 * the planner refers to elements by this id and the [UiController] resolves it back.
 */
data class ScreenState(
    val packageName: String,
    val appLabel: String?,
    val elements: List<UiElement>,
    val capturedAtMs: Long = System.currentTimeMillis(),
    /** "bottom sheet \"Offers\"" / "dialog \"Allow…\"": what is drawn on top of the app, if anything. */
    val overlay: String? = null,
    /** How many elements the overlay hides. */
    val hiddenBehindOverlay: Int = 0,
)

data class UiElement(
    val id: Int,
    val role: Role,
    /** Best human-readable label: text, else content description, else hint, else resource name. */
    val label: String,
    /** Current text of an input when it differs from its label (e.g. typed text vs. hint). */
    val value: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val focused: Boolean = false,
    /** Part of the sheet/dialog drawn on top of the app. */
    val inOverlay: Boolean = false,
    /** Tapping this closes the overlay (close/✕/Not now/backdrop, or has a dismiss action). */
    val closesOverlay: Boolean = false,
    /** For inputs: what the field expects, from Android's input type. */
    val inputKind: InputKind? = null,
    val bounds: Bounds,
)

enum class InputKind { TEXT, PHONE, NUMBER, EMAIL, PASSWORD }

enum class Role { BUTTON, TEXT, INPUT, IMAGE, CHECKBOX, SWITCH, LIST, TAB, LINK, OTHER }

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** How the UI responded to an action, as observed by [ScreenReader.awaitSettled]. */
enum class Settle {
    /** UI changed and has stopped changing. */
    CHANGED,
    /** The expected app came to the front and settled. */
    OPENED,
    /** No UI event at all after the action. */
    UNCHANGED,
    /** UI kept changing (video, animation) or the expected app never appeared. */
    TIMEOUT,
}

/** Reads the current screen. Implemented by :perception. */
interface ScreenReader {
    /** Snapshot of the foreground window, or null if the accessibility service is not connected. */
    suspend fun capture(): ScreenState?

    /** Take a marker BEFORE acting; pass it to [awaitSettled] after. */
    fun mark(): Long

    /**
     * Suspends until the UI has reacted to the action taken after [mark] and then gone quiet.
     * With [expectPackage], also waits until that app's window is in front.
     */
    suspend fun awaitSettled(mark: Long, expectPackage: String? = null, timeoutMs: Long = 5_000): Settle

    /** Renders a snapshot as the compact text the planner prompt consumes. */
    fun toPrompt(state: ScreenState): String
}
