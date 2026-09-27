package com.hackathon.assistant.actions

import android.content.Context

/**
 * A small, on-device store of personal facts the assistant may reuse across tasks — name, phone,
 * email, home/work address and the like.
 *
 * Deliberately LIMITED: it holds at most [MAX_FACTS] short entries, lives only in the app's private
 * [android.content.SharedPreferences] (never synced, never sent anywhere), and stores nothing unless
 * the user explicitly asks ("remember my email is …"). The plain [get] is the hook a form-filler can
 * use to auto-complete a field it already knows, instead of asking the user again.
 */
internal class PersonalMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Canonical key, whoever saved it (a routed command or the model): "Home Address" / "home_address"
     * are one fact, and "my mobile no", "phone number", "email id" map to phone / email / name, the
     * keys the form filler looks up.
     */
    private fun norm(key: String): String {
        val k = key.trim().lowercase().removePrefix("my ").removePrefix("the ").replace(Regex("[\\s_]+"), " ").trim()
        return when (k) {
            "number", "phone", "phone number", "phone no", "mobile", "mobile number", "mobile no", "contact number", "cell", "cell number" -> "phone"
            "email", "email id", "email address", "e mail", "mail", "mail id" -> "email"
            "name", "full name", "my name" -> "name"
            else -> k.replace(' ', '_')
        }.take(KEY_LIMIT)
    }

    fun get(key: String): String? = prefs.getString(norm(key), null)

    /** Saves a fact. Returns false (storing nothing) if the value is blank or the store is full. */
    fun put(key: String, value: String): Boolean {
        val k = norm(key)
        val v = value.trim().take(MAX_VALUE)
        if (k.isEmpty() || v.isEmpty()) return false
        if (k !in prefs.all.keys && prefs.all.size >= MAX_FACTS) return false
        prefs.edit().putString(k, v).apply()
        return true
    }

    /** Removes one fact; returns false if it wasn't stored. */
    fun forget(key: String): Boolean {
        val k = norm(key)
        if (k !in prefs.all.keys) return false
        prefs.edit().remove(k).apply()
        return true
    }

    fun clear() = prefs.edit().clear().apply()

    /** All saved facts as canonical-key -> value. */
    fun all(): Map<String, String> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    companion object {
        const val FILE = "personal_memory"
        const val MAX_FACTS = 20
        const val MAX_VALUE = 200
        private const val KEY_LIMIT = 40
    }
}
