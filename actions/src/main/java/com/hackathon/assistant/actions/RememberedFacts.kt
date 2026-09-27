package com.hackathon.assistant.actions

import android.content.Context

/**
 * Read-only view of what the user explicitly asked the assistant to remember (see [PersonalMemory]),
 * so the form filler can type a saved phone number or email instead of asking again.
 */
object RememberedFacts {
    /** First saved value among [keys] ("phone", "phone_number", "mobile" ...), or null. */
    fun find(context: Context, vararg keys: String): String? {
        val m = PersonalMemory(context)
        return keys.firstNotNullOfOrNull { k -> m.get(k)?.takeIf { it.isNotBlank() } }
    }
}
