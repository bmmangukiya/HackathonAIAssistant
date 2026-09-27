package com.hackathon.assistant.perception

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hackathon.assistant.core.Role
import com.hackathon.assistant.core.ScreenReader
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Settle
import kotlinx.coroutines.delay

/** Accessibility tree -> [ScreenState] translation layer. */
class AccessibilityScreenReader : ScreenReader {

    override suspend fun capture(): ScreenState? {
        val service = AssistantAccessibilityService.instance ?: return null
        val metrics = service.resources.displayMetrics
        // Right after an app launch the window can briefly have no content; give it a moment.
        var frame = foreground(service)
        repeat(EMPTY_RETRIES) {
            if (frame != null) return@repeat
            delay(300)
            frame = foreground(service)
        }
        val (pkg, roots) = frame ?: return ScreenState(service.packageName, null, emptyList(), SystemClock.uptimeMillis())
        val result = ScreenTranslator(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)).translate(roots)
        val label = runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        val state = ScreenState(pkg, label, result.elements, SystemClock.uptimeMillis(), result.overlay, result.hidden)
        Snapshots.put(state, result.nodes)
        return state
    }

    /**
     * The active app window plus app windows stacked above it (dialogs, sheets, permission
     * prompts), top-most first, and the active window's package. Header and elements come
     * from the SAME window list, so the model never sees one app's elements under another's
     * name. System decor (status/nav bar, OEM bubbles) and the keyboard are skipped.
     */
    private fun foreground(service: AssistantAccessibilityService): Pair<String, List<AccessibilityNodeInfo>>? {
        val all = runCatching { service.windows }.getOrNull().orEmpty()
        val active = all.firstOrNull { it.isActive }
        val activeRoot = active?.root ?: service.rootInActiveWindow ?: return null
        val pkg = activeRoot.packageName?.toString() ?: return null
        val roots = if (active == null) listOf(activeRoot) else all
            .filter { it == active || (it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.layer > active.layer) }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName != service.packageName }
        return if (roots.isEmpty() || activeRoot.childCount == 0) null else pkg to roots
    }

    /** The package whose window is currently active (in front). */
    private fun activePackage(service: AssistantAccessibilityService): String? =
        (runCatching { service.windows }.getOrNull()?.firstOrNull { it.isActive }?.root ?: service.rootInActiveWindow)
            ?.packageName?.toString()

    /** Debug: every window and node as the service sees it (adb ... --ez raw true). */
    fun rawDump(): String = buildString {
        val service = AssistantAccessibilityService.instance ?: return "not connected"
        for (w in runCatching { service.windows }.getOrNull().orEmpty()) {
            appendLine("WINDOW type=${w.type} layer=${w.layer} active=${w.isActive} pkg=${w.root?.packageName}")
            fun walk(n: AccessibilityNodeInfo, d: Int) {
                val r = Rect().also(n::getBoundsInScreen)
                appendLine("  ".repeat(d.coerceAtMost(30)) + "${n.className?.toString()?.substringAfterLast('.')} " +
                    "t=${n.text} d=${n.contentDescription} vis=${n.isVisibleToUser} clk=${n.isClickable} $r")
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
            }
            w.root?.let { walk(it, 1) }
        }
    }

    override fun mark(): Long = AssistantAccessibilityService.instance?.eventCount ?: 0L

    override suspend fun awaitSettled(mark: Long, expectPackage: String?, timeoutMs: Long): Settle {
        val service = AssistantAccessibilityService.instance ?: return Settle.TIMEOUT
        val start = SystemClock.uptimeMillis()
        val deadline = start + timeoutMs
        fun now() = SystemClock.uptimeMillis()

        // 1. Did the UI react at all? Taps usually react within ~100 ms; app launches later.
        val reactBy = start + if (expectPackage != null) timeoutMs else REACT_MS
        while (service.eventCount == mark && now() < reactBy) delay(POLL_MS)
        if (service.eventCount == mark) return Settle.UNCHANGED

        // 2. If an app launch was expected, wait until it is actually in front.
        if (expectPackage != null) {
            while (activePackage(service) != expectPackage && now() < deadline) delay(POLL_MS)
            if (activePackage(service) != expectPackage) return Settle.TIMEOUT
        }

        // 3. Wait for the UI to go quiet (animations, list loading).
        while (now() - service.lastChangeAt < QUIET_MS && now() < deadline) delay(POLL_MS)
        return when {
            now() - service.lastChangeAt < QUIET_MS -> Settle.TIMEOUT
            expectPackage != null -> Settle.OPENED
            else -> Settle.CHANGED
        }
    }

    override fun toPrompt(state: ScreenState): String = buildString {
        appendLine("App: ${state.appLabel ?: state.packageName} (${state.packageName})")
        state.overlay?.let {
            appendLine("OVERLAY ON TOP: $it (${state.hiddenBehindOverlay} elements behind it are hidden).")
            appendLine("If you need this app's screen for the goal, close it first: tap the element marked (closes overlay), or use back if none is marked.")
        }
        for (e in state.elements) {
            append('[').append(e.id).append("] ").append(e.role.name.lowercase())
            if (e.label.isNotEmpty()) append(" \"").append(e.label).append('"')
            e.value?.let { append(" = \"").append(it).append('"') }
            val flags = buildList {
                if (e.role == Role.LIST || (e.scrollable && e.role != Role.LIST)) add("scrollable")
                e.checked?.let { add(if (it) "on" else "off") }
                if (e.selected) add("selected")
                if (e.inOverlay) add("in overlay")
                if (e.closesOverlay) add("closes overlay")
                e.inputKind?.let { k ->
                    add(k.name.lowercase())
                    if (e.value.isNullOrEmpty()) add("empty")
                }
                if (e.focused && e.editable) add("focused")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString()).append(')')
            appendLine()
        }
    }.trimEnd()

    private companion object {
        const val EMPTY_RETRIES = 5
        const val REACT_MS = 1_200L
        const val QUIET_MS = 400L
        const val POLL_MS = 40L
    }
}

/** Latest snapshot's id -> node map, so the controller can act on ids the planner chose. */
internal object Snapshots {
    @Volatile private var capturedAt = -1L
    @Volatile private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    fun put(state: ScreenState, map: Map<Int, AccessibilityNodeInfo>) {
        capturedAt = state.capturedAtMs
        nodes = map
    }

    /** Null if [state] is not the latest snapshot; callers fall back to its bounds. */
    fun node(state: ScreenState, id: Int): AccessibilityNodeInfo? =
        if (state.capturedAtMs == capturedAt) nodes[id]?.takeIf { it.refresh() } else null
}
