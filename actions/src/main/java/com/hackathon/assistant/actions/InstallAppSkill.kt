package com.hackathon.assistant.actions

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.UiAction
import kotlinx.coroutines.delay

/**
 * Installs an app from the Play Store, programmatically (no model steps):
 * search → tap the "Install · <name>" button whose name matches (skipping sponsored rows like
 * Blinkit for "zepto") → wait until the app is actually installed on the phone.
 *
 * Success is judged from the package manager (a new launchable app whose label matches), never from
 * "the Install button was tapped". [Risk.CONFIRM]: the agent asks the user first (it downloads to the device),
 * unless the user asked for the install in so many words ("install zepto").
 */
class InstallAppSkill : Skill {
    override val id = "install_app"
    override val description = "Install an app from the Play Store by name"
    override val slots = listOf(SlotSpec("app", "Name of the app, e.g. Zepto", question = "Which app should I install?"))
    override val examples = listOf("install zepto", "download the swiggy app")
    override val risk = Risk.CONFIRM

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val name = args["app"]?.trim()?.replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "")?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("No app name")
        installedPackage(ctx, name)?.let { return ActionResult.Success("$name is already installed", observation = "$name is already installed; open it with open_app.") }

        // Explicit component: a plain market:// intent shows a chooser (Play Store / V-Appstore) on this phone.
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name) + "&c=apps"))
            .setComponent(ComponentName(VENDING, "com.google.android.finsky.activities.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.android.startActivity(search) }.isFailure) {
            return ActionResult.Failure("The Play Store isn't available on this phone. TERMINAL")
        }

        var tapped: String? = null
        for (i in 0 until 40) { // ~15 s to find and tap the button
            val s = ctx.screen.capture()
            if (s != null && s.packageName == VENDING) {
                if (s.elements.any { it.label.contains("Sign in", true) && it.clickable }) {
                    return ActionResult.Failure("The Play Store isn't signed in, so I can't install apps. TERMINAL")
                }
                val button = installButton(s, name)
                if (button != null) {
                    Log.i(TAG, "tapping ${button.label}")
                    val mark = ctx.screen.mark()
                    ctx.ui.perform(UiAction.Tap(button.id, touch = true), s)
                    ctx.screen.awaitSettled(mark, timeoutMs = 3_000)
                    tapped = button.label
                    break
                }
                // Already installed but not detected by label, or the result isn't there.
                if (s.elements.any { it.label.startsWith("Open ·", true) && it.label.contains(name, true) }) {
                    return ActionResult.Success("$name is already installed")
                }
            }
            delay(400)
        }
        tapped ?: return ActionResult.Failure("I couldn't find an Install button for $name in the Play Store")

        ctx.voice.speak("Installing $name. This can take a minute.")
        repeat(INSTALL_WAIT_S / 2) {
            delay(2_000)
            installedPackage(ctx, name)?.let { pkg ->
                return ActionResult.Success("$name is installed", observation = "$name ($pkg) is installed now; open it with open_app if the goal needs it.")
            }
        }
        return ActionResult.Failure("$name is still downloading. I tapped Install; check the Play Store in a moment")
    }

    /** "Install · Zepto: Groceries in minutes Zepto Market": the Install button of the row whose title starts with [name]. */
    private fun installButton(s: ScreenState, name: String) = s.elements.firstOrNull { e ->
        // Spaces ignored: "phone pe" (as speech writes it) is "PhonePe".
        e.clickable && e.label.startsWith("Install", ignoreCase = true) &&
            e.label.substringAfter("·", "").replace(" ", "").startsWith(name.replace(" ", ""), ignoreCase = true)
    } ?: s.elements.firstOrNull { e -> e.clickable && e.label.equals("Install", ignoreCase = true) } // details page

    /** Package of an installed launchable app whose label starts with / equals [name], if any. */
    private fun installedPackage(ctx: SkillContext, name: String): String? {
        val pm = ctx.android.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val n = name.lowercase()
        val compact = n.replace(" ", "")
        // Package segments only for distinctive names: "google" or "android" is in every package.
        val bySegment = compact.length >= 4 && compact !in GENERIC_SEGMENTS
        return pm.queryIntentActivities(launcher, 0).firstOrNull { r ->
            val l = r.loadLabel(pm).toString().lowercase()
            l == n || l.replace(" ", "") == compact || l.startsWith("$n ") || l.startsWith("$n:") ||
                (bySegment && r.activityInfo.packageName.lowercase().split('.').any { it == compact || it == "${compact}consumerapp" })
        }?.activityInfo?.packageName
    }

    private companion object {
        const val TAG = "InstallApp"
        const val VENDING = "com.android.vending"
        const val INSTALL_WAIT_S = 120
        val GENERIC_SEGMENTS = setOf("com", "android", "google", "apps", "app", "mobile", "client", "vivo", "iqoo")
    }
}
