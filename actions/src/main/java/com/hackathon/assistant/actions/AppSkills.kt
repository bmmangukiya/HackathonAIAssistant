package com.hackathon.assistant.actions

import android.content.Intent
import com.hackathon.assistant.core.ActionResult

/** App discovery and deep linking: the agent decides WHICH app reaches the goal and HOW to enter it. */
internal object AppSkills {

    val listApps = SimpleSkill(
        "list_apps", "List installed apps with their deep links and search links; use it to decide which app can do the goal",
        emptyList(), listOf("which app can I use to pay", "what apps do I have"),
    ) {
        val apps = AppCatalog.apps(android)
        ActionResult.Success(
            message = "",
            observation = "Installed apps (name (package): deep links):\n" + apps.joinToString("\n") { AppCatalog.line(it) },
        )
    }

    // Installing from the Play Store is InstallAppSkill ("install_app"): it taps Install itself and
    // confirms the install with the package manager, so there is one install tool, not two.

    val openLink = SimpleSkill(
        "open_link", "Open a deep link or web link in the right app, e.g. https://www.youtube.com/results?search_query=lofi or market://details?id=com.whatsapp",
        listOf(
            slot("url", "the full deep link / URL", "Which link should I open?"),
            slot("package", "package name of the app that should open it", "", required = false),
        ),
        listOf("open the whatsapp page on play store"),
    ) { args ->
        // Spoken search words arrive with spaces ("surf excel"); URLs can't contain them.
        val url = args.getValue("url").trim().replace(" ", "%20")
        val intent = Intent(Intent.ACTION_VIEW, uri(url))
        args["package"]?.takeIf { it.contains('.') }?.let { intent.setPackage(it.trim()) }
        launch(intent, "")
    }
}
