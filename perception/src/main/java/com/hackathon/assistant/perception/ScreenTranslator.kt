package com.hackathon.assistant.perception

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.hackathon.assistant.core.Bounds
import com.hackathon.assistant.core.InputKind
import com.hackathon.assistant.core.Role
import com.hackathon.assistant.core.UiElement

/**
 * Translates a raw accessibility tree into the few dozen elements a model can reason about.
 *
 * - Actionable nodes (click/edit/check/scroll) become one element each. Their label is their
 *   own text, or else the text of their non-actionable descendants ("Mom, Call me, 10:42").
 * - Text that is not inside an actionable node becomes a TEXT element.
 * - Invisible, zero-size and off-screen nodes, and unlabeled icons, are dropped.
 */
internal class ScreenTranslator(private val screen: Rect) {

    class Result(
        val elements: List<UiElement>,
        val nodes: Map<Int, AccessibilityNodeInfo>,
        val overlay: String? = null,
        val hidden: Int = 0,
    )

    /**
     * A subtree drawn on top of earlier content: [start, end) index range into raws.
     * [strength]: 3 = own window, 2 = dismiss/collapse action or pane title, 1 = shape heuristic.
     */
    private data class Panel(val start: Int, val end: Int, val rect: Rect, val kind: String, val title: String?, val strength: Int)
    private val panels = mutableListOf<Panel>()

    private data class Raw(
        val node: AccessibilityNodeInfo,
        val role: Role,
        val label: String,
        val value: String?,
        val rect: Rect,
        val inOverlay: Boolean = false,
        val closesOverlay: Boolean = false,
    )

    /** Visible text anywhere on screen, for labelling buttons whose text is a sibling overlay. */
    private val overlayTexts = mutableListOf<Pair<Rect, String>>()

    fun translate(roots: List<AccessibilityNodeInfo>): Result {
        overlayTexts.clear()
        panels.clear()
        roots.forEach { collectTexts(it, depth = 0) }
        val all = mutableListOf<Raw>()
        // Roots are top-most first. With more than one, the top one is a dialog/sheet WINDOW over
        // the app: visit the app first (drawing order), then the dialog, so it counts as on top.
        roots.drop(1).reversed().forEach { visit(it, all, depth = 0) }
        if (roots.size > 1) {
            val start = all.size
            visit(roots.first(), all, depth = 0)
            val r = Rect().also(roots.first()::getBoundsInScreen)
            panels += Panel(start, all.size, r, "dialog", roots.first().paneTitle?.toString(), strength = 3)
        } else {
            visit(roots.first(), all, depth = 0)
        }
        val (raws, overlayTitle, hidden) = removeCovered(all)
        val unique = withCardContext(raws).distinctBy { Triple(it.role, it.label, it.rect) }
            .sortedWith(compareBy({ it.rect.top / ROW_BUCKET_PX }, { it.rect.left }))
            .take(MAX_ELEMENTS)
        val elements = ArrayList<UiElement>(unique.size)
        val nodes = HashMap<Int, AccessibilityNodeInfo>(unique.size)
        unique.forEachIndexed { i, r ->
            val id = i + 1
            val n = r.node
            elements += UiElement(
                id = id,
                role = r.role,
                label = r.label,
                value = r.value,
                clickable = n.isClickable || n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                checked = if (n.isCheckable) n.isChecked else null,
                selected = n.isSelected,
                focused = n.isFocused,
                inOverlay = r.inOverlay,
                closesOverlay = r.closesOverlay,
                inputKind = if (n.isEditable) inputKind(n) else null,
                bounds = Bounds(r.rect.left, r.rect.top, r.rect.right, r.rect.bottom),
            )
            nodes[id] = n
        }
        return Result(elements, nodes, overlayTitle, hidden)
    }

    /**
     * The last-drawn panel is on top. Elements drawn BEFORE it and lying under it are covered,
     * so they are left out: the model must deal with the sheet/dialog first. With a backdrop
     * (scrim) everything drawn before the panel is covered.
     */
    private fun removeCovered(all: List<Raw>): Triple<List<Raw>, String?, Int> {
        // Strongest evidence wins; among equals, the one drawn last is on top. Then widen to the
        // outermost panel enclosing it, so a sheet's inner list doesn't stand in for the sheet.
        var top = panels.maxWithOrNull(compareBy<Panel>({ it.strength }, { it.start })) ?: return Triple(all, null, 0)
        panels.filter { it.strength >= top.strength.coerceAtMost(2) && it.start <= top.start && it.end >= top.end }
            .minByOrNull { it.start }?.let { top = it }
        val inPanel = all.subList(top.start, top.end)
        val backdrop = top.kind == "dialog" || inPanel.any { r -> BACKDROP_WORDS.any { r.label.contains(it, ignoreCase = true) } } ||
            all.subList(0, top.start).any { r -> BACKDROP_WORDS.any { r.label.contains(it, ignoreCase = true) } }
        // A sticky bar ("View cart, 2 items") often sits in a full-screen transparent container, so its
        // rect looks like a sheet covering everything. With no backdrop, few elements and a huge rect,
        // it is a footer, not an overlay: hiding the 54 elements behind it blinded the agent on Blinkit.
        val area = top.rect.width().toLong() * top.rect.height()
        val screenArea = screen.width().toLong() * screen.height()
        if (!backdrop && top.kind != "dialog" && inPanel.size <= 3 && area > screenArea / 2) return Triple(all, null, 0)
        val covered = all.withIndex().filter { (i, r) ->
            i < top.start && (backdrop || top.rect.contains(r.rect.centerX(), r.rect.centerY()))
        }.map { it.index }.toSet()
        if (covered.isEmpty() && top.strength < 3) return Triple(all, null, 0)
        val title = top.title ?: inPanel.firstOrNull { it.role == Role.TEXT }?.label ?: inPanel.firstOrNull()?.label
        val marked = all.mapIndexedNotNull { i, r ->
            when {
                i in covered -> null
                i in top.start until top.end -> r.copy(inOverlay = true, closesOverlay = isCloser(r))
                // A backdrop drawn just before the panel dismisses it when tapped.
                BACKDROP_WORDS.any { r.label.contains(it, ignoreCase = true) } -> r.copy(inOverlay = true, closesOverlay = true)
                else -> r
            }
        }
        val kind = if (top.kind == "dialog") "dialog" else if (top.rect.bottom >= screen.bottom * 0.9) "bottom sheet" else "pop-up"
        return Triple(marked, "$kind \"${title?.take(50).orEmpty()}\"", covered.size)
    }

    private fun isCloser(r: Raw): Boolean {
        if (r.role == Role.TEXT) return false
        val l = r.label.lowercase()
        val dismissible = r.node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
        // Short symbols ("x", "✕") only as the whole label: "x" is inside "approximate".
        return dismissible || CLOSE_WORDS.any { w ->
            if (w.length <= 2) l.trim() == w else l == w || l.startsWith("$w ") || l.contains(w.replace(" ", ""))
        }
    }

    /** Material sheets/dialogs expose dismiss/collapse, or announce themselves with a pane title. */
    private fun declaresPanel(n: AccessibilityNodeInfo): String? {
        val actions = n.actionList.map { it.id }
        return when {
            n.paneTitle != null -> n.paneTitle.toString()
            AccessibilityNodeInfo.ACTION_DISMISS in actions -> ""
            AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id in actions -> ""
            else -> null
        }
    }

    /** Sheet/dialog shape: nearly full width, a real chunk of height, but not the whole screen. */
    private fun isPanelShape(r: Rect) =
        r.width() >= screen.width() * 0.85 && r.height() >= screen.height() * 0.2 && r.height() <= screen.height() * 0.92

    private fun visit(node: AccessibilityNodeInfo, out: MutableList<Raw>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        // Containers can report "not visible" while their children are (Play Store does), so
        // only skip emitting such nodes; always keep walking into their children.
        if (!node.isVisibleToUser || rect.isEmpty || !Rect.intersects(rect, screen)) {
            visitChildren(node, out, depth)
            return
        }
        // Half off screen (a row scrolled under the app bar): keep only the visible part, so a tap at
        // the element's centre lands on it and never at negative coordinates.
        rect.intersect(screen)

        if (node.isActionable()) {
            // For inputs the text is the VALUE; the label is the hint/description ("Phone number").
            val own = if (node.isEditable) inputLabel(node) else ownLabel(node)
            val label = (
                own ?: descendantText(node).takeIf { it.isNotBlank() } ?: overlayText(rect) ?: resourceName(node)
                )?.let(::clean)
            val value = node.text?.toString()?.takeIf { node.isEditable && !node.isShowingHintText && it.isNotBlank() && it != label }
            if (label != null || node.isEditable || node.isScrollable) {
                out += Raw(node, roleOf(node), (label ?: "").take(MAX_LABEL), value?.take(MAX_LABEL), rect)
            } else if (node.isClickable && rect.width() * rect.height() < screen.width() * screen.height() * ICON_MAX_AREA) {
                // An unlabeled icon (search, cart, close...) is still tappable: describe where it is.
                out += Raw(node, Role.BUTTON, "icon, ${position(rect)}", null, rect)
            }
        } else if (!hasActionableAncestor(node)) {
            ownLabel(node)?.let(::clean)?.let { out += Raw(node, textRole(node), it.take(MAX_LABEL), null, rect) }
        }
        visitChildren(node, out, depth)
    }

    /** Children in drawing order (getDrawingOrder); a later child overlapping an earlier one is on top. */
    private fun visitChildren(node: AccessibilityNodeInfo, out: MutableList<Raw>, depth: Int) {
        val children = (0 until node.childCount).mapNotNull { node.getChild(it) }.sortedBy { it.drawingOrder }
        val earlier = mutableListOf<Rect>()
        for (c in children) {
            val r = Rect().also(c::getBoundsInScreen)
            val start = out.size
            visit(c, out, depth + 1)
            val end = out.size
            // On top of something = overlaps a sibling drawn earlier, OR covers content emitted
            // earlier anywhere in the tree (Zepto's sheet covers home content from a cousin subtree).
            val stacked = earlier.any { Rect.intersects(it, r) } ||
                out.subList(0, start).any { r.contains(it.rect.centerX(), it.rect.centerY()) }
            val hasControls = end > start && out.subList(start, end).any { it.role != Role.TEXT }
            val declared = declaresPanel(c)
            // Only real evidence makes an overlay: a dismiss/collapse action or pane title here, or a
            // backdrop/scrim drawn with it. Shape alone flagged Zepto's product carousels and
            // WhatsApp's search bar as "sheets" and hid the real screen from the model.
            val backdropInside = hasControls &&
                out.subList(start, end).any { raw -> BACKDROP_WORDS.any { raw.label.contains(it, ignoreCase = true) } }
            when {
                declared != null && hasControls && r.height() >= screen.height() * 0.12 ->
                    panels += Panel(start, end, r, "sheet", declared.ifBlank { null }, strength = 2)
                stacked && backdropInside && isPanelShape(r) ->
                    panels += Panel(start, end, r, "sheet", null, strength = 2)
            }
            if (!r.isEmpty) earlier += r
        }
    }

    /**
     * A list of cards flattens into many identical `button "Install"` lines that the model
     * cannot tell apart (it installed a sponsored app that way). Generic or repeated button
     * labels get the title of their own card: `button "Install · WhatsApp Messenger"`.
     */
    private fun withCardContext(raws: List<Raw>): List<Raw> {
        val counts = raws.groupingBy { it.label.lowercase() }.eachCount()
        return raws.map { r ->
            val generic = r.label.lowercase() in GENERIC_LABELS || (counts.getValue(r.label.lowercase()) > 1 && r.label.length <= 24)
            if (r.role == Role.TEXT || r.label.isEmpty() || !generic) r
            else cardTitle(r.node, r.label)?.let { r.copy(label = "${r.label} · $it".take(MAX_LABEL + 20)) } ?: r
        }
    }

    /** First text in the nearest enclosing container, outside the button itself. */
    private fun cardTitle(button: AccessibilityNodeInfo, ownLabel: String): String? {
        var child = button
        var parent = button.parent
        repeat(CARD_LEVELS) {
            val p = parent ?: return null
            for (i in 0 until p.childCount) {
                val c = p.getChild(i) ?: continue
                if (c == child) continue
                // A neighbouring short button ("Always" next to "Just once") is not a card title.
                if (c.isClickable && (ownLabel(c)?.split(' ')?.size ?: 0) <= 2) continue
                val text = (ownLabel(c) ?: descendantText(c)).takeIf { it.isNotBlank() && !it.equals(ownLabel, true) }
                if (text != null) return clean(text)?.take(40)
            }
            child = p
            parent = p.parent
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && !node.isClickable) ownLabel(node)?.let { overlayTexts += rect to it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, depth + 1) }
    }

    /**
     * Play Store style: an empty clickable Button with a separate TextView drawn on top of it
     * ("Sign in"). Borrow text whose center lies inside the button and that fits within it.
     */
    private fun overlayText(button: Rect): String? =
        overlayTexts.filter { (r, _) -> button.contains(r.centerX(), r.centerY()) && r.width() <= button.width() * 1.1 }
            .take(2).joinToString(", ") { it.second }.takeIf { it.isNotBlank() }

    private fun AccessibilityNodeInfo.isActionable() =
        isClickable || isLongClickable || isEditable || isCheckable || isScrollable

    private fun hasActionableAncestor(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        var hops = 0
        while (p != null && hops++ < MAX_DEPTH) {
            if (p.isClickable || p.isLongClickable || p.isCheckable) return true
            p = p.parent
        }
        return false
    }

    private fun inputLabel(n: AccessibilityNodeInfo): String? =
        listOf(n.hintText, n.contentDescription, n.paneTitle)
            .firstOrNull { !it.isNullOrBlank() && it.toString() != "null" }?.toString()?.trim()?.replace(WHITESPACE, " ")
            ?: n.text?.toString()?.takeIf { !n.isShowingHintText && it.isBlank() }

    private fun ownLabel(n: AccessibilityNodeInfo): String? =
        listOf(n.text, n.contentDescription, n.hintText)
            .firstOrNull { !it.isNullOrBlank() && it.toString() != "null" }?.toString()?.trim()?.replace(WHITESPACE, " ")

    /** Joins text of non-actionable descendants: a list row's title, subtitle and time. */
    private fun descendantText(n: AccessibilityNodeInfo, depth: Int = 0, acc: MutableList<String> = mutableListOf()): String {
        if (depth > 6 || acc.size >= 4) return acc.joinToString(", ")
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser || c.isClickable || c.isEditable) continue
            ownLabel(c)?.let { if (it !in acc) acc += it }
            descendantText(c, depth + 1, acc)
        }
        return acc.joinToString(", ")
    }

    /**
     * "com.whatsapp:id/menuitem_search" -> "menuitem search". Layout plumbing ids
     * ("vcommonlayout_toptargetview") say nothing to the model and are dropped.
     */
    private fun resourceName(n: AccessibilityNodeInfo): String? {
        val words = n.viewIdResourceName?.substringAfter(":id/")
            ?.split('_', '.', '-')?.filter { it.isNotBlank() } ?: return null
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { w -> LAYOUT_WORDS.any { w.contains(it, ignoreCase = true) } }) return null
        return words.joinToString(" ")
    }

    /** "top-right", "middle-left", "bottom-center": where an unlabeled icon sits on screen. */
    private fun position(r: Rect): String {
        val v = when {
            r.centerY() < screen.height() / 4 -> "top"
            r.centerY() > screen.height() * 3 / 4 -> "bottom"
            else -> "middle"
        }
        val h = when {
            r.centerX() < screen.width() / 3 -> "left"
            r.centerX() > screen.width() * 2 / 3 -> "right"
            else -> "center"
        }
        return "$v-$h"
    }

    /** Drops stray separators and repeated segments: ",A,, A" -> "A". */
    private fun clean(label: String): String? =
        label.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(", ").takeIf { it.isNotEmpty() }

    private fun roleOf(n: AccessibilityNodeInfo): Role {
        val cls = n.className?.toString().orEmpty()
        return when {
            n.isEditable || cls.endsWith("EditText") -> Role.INPUT
            cls.endsWith("Switch") || cls.endsWith("ToggleButton") -> Role.SWITCH
            n.isCheckable || cls.endsWith("CheckBox") || cls.endsWith("RadioButton") -> Role.CHECKBOX
            n.isScrollable && !n.isClickable -> Role.LIST
            cls.contains("Tab") -> Role.TAB
            else -> Role.BUTTON
        }
    }

    private fun inputKind(n: AccessibilityNodeInfo): InputKind {
        if (n.isPassword) return InputKind.PASSWORD
        val t = n.inputType
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when {
            cls == InputType.TYPE_CLASS_PHONE -> InputKind.PHONE
            cls == InputType.TYPE_CLASS_NUMBER && (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) -> InputKind.PASSWORD
            cls == InputType.TYPE_CLASS_NUMBER -> InputKind.NUMBER
            cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS -> InputKind.PASSWORD
            cls == InputType.TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS -> InputKind.EMAIL
            else -> InputKind.TEXT
        }
    }

    /**
     * React Native / Compose apps often draw buttons as plain text with the click handler on a
     * node that isn't flagged clickable (Zepto's "Continue"). Short action words become buttons;
     * a tap on them falls back to a real touch at their bounds.
     */
    private fun textRole(n: AccessibilityNodeInfo): Role = when {
        n.className?.toString()?.endsWith("ImageView") == true -> Role.IMAGE
        ownLabel(n)?.lowercase()?.trim() in ACTION_WORDS -> Role.BUTTON
        else -> Role.TEXT
    }

    private companion object {
        const val MAX_DEPTH = 40
        const val MAX_ELEMENTS = 80
        const val MAX_LABEL = 60
        const val ROW_BUCKET_PX = 24
        val WHITESPACE = Regex("\\s+")
        const val CARD_LEVELS = 3
        /** Unlabeled clickables bigger than this share of the screen are containers, not icons. */
        const val ICON_MAX_AREA = 0.04
        val BACKDROP_WORDS = listOf("backdrop", "scrim", "touch outside")
        val CLOSE_WORDS = listOf(
            "close", "✕", "×", "x", "dismiss", "not now", "no thanks", "no, thanks", "skip", "later",
            "maybe later", "cancel", "got it", "bottomsheetclose",
        )
        val ACTION_WORDS = setOf(
            "continue", "next", "submit", "done", "ok", "okay", "proceed", "login", "log in", "sign in",
            "sign up", "get otp", "verify", "add", "add to cart", "skip", "allow", "confirm", "save", "search",
        )
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val EMAIL_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        val GENERIC_LABELS = setOf(
            "install", "open", "update", "uninstall", "cancel", "buy", "get", "add", "follow", "play",
            "download", "share", "like", "delete", "remove", "more options", "more", "view", "select", "join",
        )
        val LAYOUT_WORDS = listOf("layout", "container", "view", "root", "content", "wrapper", "frame", "panel", "holder")
    }
}
