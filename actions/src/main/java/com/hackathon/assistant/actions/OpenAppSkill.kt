package com.hackathon.assistant.actions

import android.content.Intent
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Launches an installed app by its spoken name. Reference pattern for new skills.
 *
 * Tolerates what a small model actually sends: `"YouTube"`, `"YouTube (com.google.android.youtube)"`
 * (copied from `list_apps`), or a bare package name. When the app is not installed the failure is
 * marked TERMINAL so the agent stops trying (see `Assistant.kt` and the prompt's stop conditions).
 */
class OpenAppSkill : Skill {
    override val id = "open_app"
    override val description = "Open an installed app by name"
    override val slots = listOf(
        SlotSpec("app", "Name of the app, e.g. YouTube", question = "Which app should I open?"),
    )
    override val examples = listOf("open youtube", "launch camera", "start whatsapp")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val raw = args["app"]?.trim() ?: return ActionResult.Failure("No app name")
        val (name, pkgHint) = split(raw)
        val pm = ctx.android.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
        val match = pkgHint?.let { hint -> candidates.firstOrNull { it.first.equals(hint, ignoreCase = true) } }
            ?: candidates.minByOrNull { (pkg, label) -> matchScore(label.lowercase(), pkg, name) }
                ?.takeIf { (pkg, label) -> matchScore(label.lowercase(), pkg, name) < NO_MATCH }
            ?: return ActionResult.Failure(
                "$raw is NOT INSTALLED on this phone. TERMINAL: do not try to open it again. " +
                    "Ask the user with ask_user whether to install it; only if they agree, call install_app. Otherwise finish and tell them.",
            )
        val intent = pm.getLaunchIntentForPackage(match.first)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionResult.Failure("${match.second} can't be opened. TERMINAL.")
        ctx.android.startActivity(intent)
        val search = AppCatalog.searchLink(match.first)?.let { "Search link for ${match.second}: $it (replace {q})" }
        return ActionResult.Success("Opening ${match.second}", openedPackage = match.first, observation = search)
    }

    /** `"Zepto (com.zepto.app)"` → ("zepto", "com.zepto.app"); `"com.whatsapp"` → ("com.whatsapp", "com.whatsapp"). */
    private fun split(raw: String): Pair<String, String?> {
        val paren = Regex("""^(.*?)\s*\(([\w.]+)\)\s*$""").find(raw)
        if (paren != null) return paren.groupValues[1].trim().lowercase() to paren.groupValues[2]
        val looksLikePackage = raw.count { it == '.' } >= 1 && raw.none { it.isWhitespace() } && raw.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        return raw.lowercase() to (if (looksLikePackage) raw else null)
    }

    /**
     * Lower is better: exact label, then prefix, then substring, then package-segment match.
     * Spaces are ignored ("phone pe" is PhonePe, not Phone). Extra words beyond the label only
     * count when they are filler or a brand ("google maps" is Maps, "whatsapp messenger" is
     * WhatsApp); "youtube music" is NOT YouTube, so an app that isn't installed is reported as such.
     */
    private fun matchScore(label: String, pkg: String, wanted: String): Int {
        val l = label.replace(" ", "")
        val w = wanted.replace(" ", "")
        val core = wanted.split(' ').filter { it.isNotBlank() && it !in FILLER && it !in BRANDS }.joinToString("")
        return when {
            l == w -> 0
            l.startsWith(w) -> 1
            (w.length >= 3 && l.contains(w)) || (core.length >= 3 && l == core) -> 2
            // "zepto" vs com.zepto.app: any package segment equals the wanted name.
            pkg.lowercase().split('.').any { it == w || (core.length >= 3 && it == core) } -> 3
            else -> NO_MATCH
        }
    }

    private companion object {
        const val NO_MATCH = 99
        val FILLER = setOf("app", "application", "messenger", "the")
        val BRANDS = setOf("google", "samsung", "vivo", "iqoo", "microsoft")
    }
}
