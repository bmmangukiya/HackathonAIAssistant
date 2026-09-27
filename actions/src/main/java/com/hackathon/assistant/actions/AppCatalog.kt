package com.hackathon.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.Build
import android.util.Log

/**
 * Every launchable app on the device and how to deep-link into it:
 * - web domains the app claims (App Links, from DomainVerificationManager, Android 12+),
 * - custom URL schemes it handles, found by probing a list of common ones.
 * Built once and cached; the agent uses it to pick which app can reach the user's goal.
 */
internal object AppCatalog {
    data class App(val label: String, val pkg: String, val domains: List<String>, val schemes: List<String>)

    @Volatile private var cached: List<App>? = null
    @Volatile private var sequence = 0

    /** Common custom schemes; the resolver tells us which app (if any) owns each. */
    private val PROBE_SCHEMES = listOf(
        "whatsapp", "spotify", "vnd.youtube", "instagram", "fb", "snapchat", "linkedin", "twitter",
        "tg", "upi", "phonepe", "paytmmp", "tez", "gpay", "market", "geo", "google.navigation",
        "tel", "sms", "smsto", "mailto", "netflix", "zomato", "swiggy", "uber", "olacabs",
        "amazon", "flipkart", "myntra", "googlechrome", "intent",
    )

    /** Rebuilt whenever an app was installed, updated or removed since the last build. */
    fun apps(context: Context): List<App> {
        val changes = context.packageManager.getChangedPackages(sequence)
        if (changes != null) {
            sequence = changes.sequenceNumber
            cached = null
        }
        return cached ?: build(context).also { cached = it }
    }

    private fun build(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }

        val schemesByPkg = mutableMapOf<String, MutableList<String>>()
        for (scheme in PROBE_SCHEMES) {
            val probe = Intent(Intent.ACTION_VIEW, uri("$scheme://x"))
            pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.distinct().forEach {
                schemesByPkg.getOrPut(it) { mutableListOf() } += "$scheme:"
            }
        }

        val dvm = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(DomainVerificationManager::class.java) else null
        return launchable.map { (pkg, label) ->
            val domains = runCatching {
                dvm?.getDomainVerificationUserState(pkg)?.hostToStateMap?.keys
                    ?.map { it.removePrefix("*.").removePrefix("www.") }?.distinct()?.sortedBy { it.length }?.take(3)
            }.getOrNull().orEmpty()
            App(label, pkg, domains, schemesByPkg[pkg].orEmpty().filter { it !in GENERIC_SCHEMES }.take(3))
        }.sortedBy { it.label.lowercase() }.also { list ->
            Log.i("AppCatalog", "${list.size} apps")
            list.forEach { Log.i("AppCatalog", line(it)) }
        }
    }

    /**
     * In-app search links ({q} = the search words). Data, not per-app code: lets the agent jump
     * straight to results (e.g. one Zepto search per grocery item) instead of hunting for a search box.
     */
    private val SEARCH_LINKS = mapOf(
        "com.zeptoconsumerapp" to "https://www.zeptonow.com/search?query={q}",
        "com.google.android.youtube" to "https://www.youtube.com/results?search_query={q}",
        "com.android.vending" to "market://search?q={q}&c=apps",
        "com.google.android.apps.maps" to "geo:0,0?q={q}",
        "com.spotify.music" to "spotify:search:{q}",
        "com.android.chrome" to "https://www.google.com/search?q={q}",
        "com.google.android.googlequicksearchbox" to "https://www.google.com/search?q={q}",
        "in.amazon.mShop.android.shopping" to "https://www.amazon.in/s?k={q}",
        "com.flipkart.android" to "https://www.flipkart.com/search?q={q}",
        "com.grofers.customerapp" to "https://blinkit.com/s/?q={q}",
        "com.application.zomato" to "https://www.zomato.com/search?q={q}",
    )

    fun searchLink(pkg: String): String? = SEARCH_LINKS[pkg]

    /** Schemes nearly every browser/app answers; listing them per app is noise. */
    private val GENERIC_SCHEMES = setOf("intent:")

    fun line(a: App): String = buildString {
        append(a.label).append(" (").append(a.pkg).append(')')
        val links = a.domains.map { "https://$it" } + a.schemes
        if (links.isNotEmpty()) append(": ").append(links.joinToString(", "))
        SEARCH_LINKS[a.pkg]?.let { append(" | search: ").append(it) }
    }
}
