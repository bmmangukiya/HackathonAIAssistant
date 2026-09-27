package com.hackathon.assistant.actions

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Spoken name -> phone number, tolerant of "mom" vs "Mom ❤️" vs "Amma". */
internal object Contacts {
    data class Match(val name: String, val number: String)

    private val ALIASES = mapOf(
        "mom" to listOf("mom", "mummy", "mum", "mother", "amma", "maa", "ma"),
        "dad" to listOf("dad", "daddy", "papa", "father", "appa", "pappa"),
    )

    fun find(context: Context, spoken: String): Match? {
        val wanted = spoken.lowercase().trim()
        if (wanted.any { it.isDigit() }) return Match(spoken, wanted.filter { it.isDigit() || it == '+' })
        val candidates = ALIASES.entries.firstOrNull { wanted in it.value }?.value ?: listOf(wanted)
        val all = runCatching { load(context) }.getOrDefault(emptyList())
        return candidates.firstNotNullOfOrNull { c -> all.firstOrNull { norm(it.name) == c } }
            ?: candidates.firstNotNullOfOrNull { c -> all.firstOrNull { norm(it.name).split(" ").contains(c) } }
            ?: all.firstOrNull { norm(it.name).startsWith(wanted) }
            ?: all.firstOrNull { norm(it.name).contains(wanted) }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

    private fun load(context: Context): List<Match> {
        val out = mutableListOf<Match>()
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out += Match(c.getString(0) ?: continue, c.getString(1) ?: continue)
        }
        return out
    }
}
