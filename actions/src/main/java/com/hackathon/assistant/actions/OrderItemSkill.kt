package com.hackathon.assistant.actions

import android.content.Intent
import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.UiElement
import kotlinx.coroutines.delay

/**
 * Adds N of an item to the cart in a quick-commerce app (Blinkit, Zepto), programmatically:
 * open app → search → pick the matching product card (not an ad) → ADD → pick the single pack if
 * a variant sheet opens → "Increase quantity" until the stepper shows N → View cart.
 *
 * It stops at the cart and never pays: checkout (login, address, payment) is handed back with a
 * follow-up goal, where the agent's consent rules ask the user before every consequential tap.
 * A long task like "order 2 diet coke on blinkit" is ONE request instead of one command per step.
 * Screen shapes verified on Blinkit (26 Sep 2026): "Increase quantity", "quantity 2", "View cart".
 */
class OrderItemSkill : Skill {
    override val id = "order_item"
    override val description = "Add an item (with quantity) to the cart in Blinkit or Zepto and open the cart"
    override val slots = listOf(
        SlotSpec("item", "the product, e.g. diet coke", question = "What should I order?"),
        SlotSpec("quantity", "how many, a number; 1 if not said", required = false, question = ""),
        SlotSpec("app", "blinkit or zepto", required = false, question = ""),
    )
    override val examples = listOf("order 2 diet coke on blinkit", "add milk to my zepto cart")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val item = args["item"]?.trim()?.takeIf { it.isNotBlank() } ?: return ActionResult.Failure("No item")
        val qty = (args["quantity"]?.let(::parseQty) ?: 1).coerceIn(1, 20)
        val pm = ctx.android.packageManager
        val app = APPS.entries.firstOrNull { (name, _) -> args["app"].orEmpty().contains(name, true) }
            ?: APPS.entries.firstOrNull { (_, pkg) -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
            ?: return ActionResult.Failure("Neither Blinkit nor Zepto is installed. Say \"install Blinkit\" first. TERMINAL")
        val (appName, pkg) = app
        val launch = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return ActionResult.Failure("${appName.cap()} is not installed on this phone. Say \"install $appName\" first. TERMINAL")
        ctx.android.startActivity(launch)
        ctx.voice.speak("Searching ${appName.cap()} for $item.")

        val words = item.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        // 1. Search via the app's own search link. Typing with accessibility sets the box's text, but
        // Blinkit doesn't run the search for it (verified on device), so the link is the reliable path.
        SEARCH_LINK[appName]?.let { link ->
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link + android.net.Uri.encode(item))).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.android.startActivity(i) }
        }
        // 2. Results: a product card that names the item (fallback: type + enter / tap the suggestion).
        val list = waitFor(ctx, pkg, 12_000) { s -> productCard(s, words) != null } ?: typeSearch(ctx, pkg, item, words)
            ?: return ActionResult.Failure("I couldn't find $item in ${appName.cap()}")
        val card = productCard(list, words)!!
        Log.i(TAG, "product: ${card.label}")
        val add = addButtonFor(list, card)
        val alreadyInCart = inside(list, card).any { it.label.contains("increase quantity", true) }
        when {
            add != null -> {
                tap(ctx, list, add, "ADD")
                // 3. Variant sheet ("ADD · Pack of 1" / "ADD · Pack of 6"): the single pack, since we set the count.
                ctx.screen.capture()?.let { s ->
                    val variants = s.elements.filter { it.clickable && it.inOverlay && it.label.startsWith("ADD ·", true) }
                    val single = variants.firstOrNull { "pack of 1" in it.label.lowercase() } ?: variants.firstOrNull()
                    if (single != null) tap(ctx, s, single, "variant")
                }
            }
            alreadyInCart -> Log.i(TAG, "already in the cart; adjusting its quantity")
            else -> return ActionResult.Failure("No ADD button for ${card.label.take(40)}")
        }

        // 4. Quantity: press "Increase quantity" until the stepper shows N.
        var shown = 0
        repeat(qty + 3) {
            val s = ctx.screen.capture() ?: return@repeat
            // The stepper that belongs to OUR item: the variant sheet if open, else the product card.
            val scope = s.elements.filter { it.inOverlay && "view cart" !in it.label.lowercase() }
                .takeIf { o -> o.any { it.label.contains("increase quantity", true) } }
                ?: productCard(s, words)?.let { inside(s, it) } ?: s.elements
            shown = scope.firstNotNullOfOrNull { e -> QTY.find(e.label.lowercase())?.groupValues?.get(1)?.toIntOrNull() } ?: shown
            if (shown >= qty) return@repeat
            val plus = scope.firstOrNull { it.clickable && it.label.contains("increase quantity", true) } ?: return@repeat
            tap(ctx, s, plus, "increase")
        }
        if (shown < qty) Log.w(TAG, "stepper shows $shown, wanted $qty")

        // 5. Cart.
        ctx.ui.perform(UiAction.Back, ctx.screen.capture() ?: list) // close the variant sheet, if any
        delay(800)
        val withCart = waitFor(ctx, pkg, 4_000) { s -> s.elements.any { it.clickable && it.label.contains("view cart", true) } }
        withCart?.let { s -> tap(ctx, s, s.elements.first { it.clickable && it.label.contains("view cart", true) }, "view cart") }
        delay(1_500)
        val cart = ctx.screen.capture()
        val needsLogin = cart?.elements?.any { it.label.contains("login", true) || it.label.contains("log in", true) } == true
        val name = card.label.substringBefore(" is available").substringBefore(",").take(50)
        val count = if (shown > 0) shown else qty
        val msg = "Added $count × $name to your ${appName.cap()} cart"
        return ActionResult.Success(
            message = msg + if (needsLogin) ". Checkout needs you to log in." else ".",
            followUpGoal = if (needsLogin) "Log in to ${appName.cap()} to check out (ask the user for the phone number and OTP)."
            else "The cart is open. Ask the user before placing the order or paying.",
            openedPackage = pkg,
            observation = "Cart has $count of \"$name\". Do NOT search or add again.",
        )
    }

    /** Fallback: tap the search bar, type, press enter, or tap the matching suggestion. */
    private suspend fun typeSearch(ctx: SkillContext, pkg: String, item: String, words: List<String>): ScreenState? {
        val home = waitFor(ctx, pkg, 6_000) { s -> s.elements.any { isSearchEntry(it) } || s.elements.any { it.editable } } ?: return null
        if (home.elements.none { it.editable }) tap(ctx, home, home.elements.first { isSearchEntry(it) }, "search bar")
        val searchScreen = waitFor(ctx, pkg, 5_000) { s -> s.elements.any { it.editable } } ?: return null
        val input = searchScreen.elements.first { it.editable }
        ctx.ui.perform(UiAction.TypeText(input.id, item), searchScreen)
        delay(1_200)
        ctx.ui.perform(UiAction.PressEnter, ctx.screen.capture() ?: searchScreen)
        waitFor(ctx, pkg, 6_000) { s -> productCard(s, words) != null }?.let { return it }
        val s = ctx.screen.capture() ?: return null
        val suggestion = s.elements.firstOrNull { it.clickable && !it.editable && "₹" !in it.label && words.all { w -> w in it.label.lowercase() } } ?: return null
        tap(ctx, s, suggestion, "suggestion")
        return waitFor(ctx, pkg, 8_000) { r -> productCard(r, words) != null }
    }

    /** Elements drawn inside [card] (its ADD button or quantity stepper). */
    private fun inside(s: ScreenState, card: UiElement) = s.elements.filter { e ->
        e !== card && e.bounds.centerX in card.bounds.left..card.bounds.right && e.bounds.centerY in card.bounds.top..card.bounds.bottom
    }

    private fun isSearchEntry(e: UiElement) = e.clickable && e.label.contains("search", true) && !e.label.equals("voice search", true)

    /** First card that names every word of the item and carries a price; ads skipped. */
    private fun productCard(s: ScreenState, words: List<String>) = s.elements.firstOrNull { e ->
        val l = e.label.lowercase()
        e.clickable && ("₹" in l || "available for" in l) && words.all { it in l } && !l.startsWith("ad") && "sponsored" !in l
    }

    /** The ADD button drawn inside [card]'s bounds (product grids put it at the card's bottom-right). */
    private fun addButtonFor(s: ScreenState, card: UiElement) = s.elements.filter { e ->
        e.clickable && e.label.startsWith("ADD", ignoreCase = false) &&
            e.bounds.centerX in card.bounds.left..card.bounds.right && e.bounds.centerY in card.bounds.top..card.bounds.bottom
    }.minByOrNull { it.bounds.top }

    private suspend fun tap(ctx: SkillContext, s: ScreenState, e: UiElement, what: String) {
        Log.i(TAG, "tap $what: ${e.label.take(60)}")
        val m = ctx.screen.mark()
        ctx.ui.perform(UiAction.Tap(e.id, touch = true), s)
        ctx.screen.awaitSettled(m, timeoutMs = 3_000)
    }

    private suspend fun waitFor(ctx: SkillContext, pkg: String, ms: Long, ok: (ScreenState) -> Boolean): ScreenState? {
        var t = 0L
        while (t <= ms) {
            val s = ctx.screen.capture()
            if (s != null) {
                if (s.packageName == pkg && ok(s)) return s
                // Pop-ups in front of the app (notification opt-in, permission prompt, promo sheet):
                // clear them and keep waiting instead of failing on "no search bar".
                dismissBlocker(ctx, s)?.let { t -= 400 } // don't count the dismissal against the wait
            }
            delay(400); t += 400
        }
        return null
    }

    /** Taps the "no thanks" answer of a blocking pop-up, or "allow" on an Android permission prompt. Returns what it tapped. */
    private suspend fun dismissBlocker(ctx: SkillContext, s: ScreenState): String? {
        val permission = s.packageName.endsWith("permissioncontroller")
        val wanted = if (permission) PERMISSION_ALLOW else DISMISS
        val b = wanted.firstNotNullOfOrNull { w -> s.elements.firstOrNull { it.clickable && it.label.trim().equals(w, ignoreCase = true) } } ?: return null
        tap(ctx, s, b, "dismiss pop-up")
        return b.label
    }

    private fun parseQty(s: String): Int? = s.filter(Char::isDigit).toIntOrNull()
        ?: WORD_NUMBERS.entries.firstOrNull { s.lowercase().contains(it.key) }?.value

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private companion object {
        const val TAG = "OrderItem"
        val APPS = linkedMapOf("blinkit" to "com.grofers.customerapp", "zepto" to "com.zeptoconsumerapp")
        /** Safe "get out of the way" answers; never "Cancel" (could cancel an order). */
        val DISMISS = listOf("No, thanks", "No thanks", "Not now", "Maybe later", "Skip", "Later", "Close", "Dismiss", "Got it", "OK, got it")
        val PERMISSION_ALLOW = listOf("While using the app", "Allow", "Only this time")
        val SEARCH_LINK = mapOf("blinkit" to "https://blinkit.com/s/?q=", "zepto" to "https://www.zeptonow.com/search?query=")
        val QTY = Regex("quantity (\\d+)")
        val WORD_NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "a couple" to 2)
    }
}
