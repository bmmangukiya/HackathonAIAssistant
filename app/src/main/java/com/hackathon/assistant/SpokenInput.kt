package com.hackathon.assistant

import com.hackathon.assistant.core.InputKind

/** Turns what speech recognition returns into what a form field accepts. */
object SpokenInput {

    private val DIGITS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "to" to "2", "too" to "2",
        "three" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9",
    )
    private val REPEAT = mapOf("double" to 2, "triple" to 3)

    fun normalize(spoken: String, kind: InputKind?): String = when (kind) {
        InputKind.PHONE, InputKind.NUMBER -> digits(spoken)
        InputKind.EMAIL -> email(spoken)
        else -> spoken.trim()
    }

    /** "nine eight double seven 6" -> "98776"; keeps a leading + for country codes. */
    fun digits(spoken: String): String {
        val out = StringBuilder()
        if (spoken.trim().startsWith("+") || spoken.lowercase().startsWith("plus")) out.append('+')
        var repeat = 1
        for (token in spoken.lowercase().split(Regex("[\\s,.-]+"))) {
            when {
                token in REPEAT -> { repeat = REPEAT.getValue(token); continue }
                token in DIGITS -> out.append(DIGITS.getValue(token).repeat(repeat))
                token.any { it.isDigit() } -> {
                    val d = token.filter { it.isDigit() }
                    out.append(if (repeat > 1 && d.length == 1) d.repeat(repeat) else d)
                }
            }
            repeat = 1
        }
        return out.toString()
    }

    /** "Vishnu dot P at gmail dot com" -> "vishnu.p@gmail.com". */
    fun email(spoken: String): String = spoken.lowercase()
        .replace(Regex("\\s+at the rate\\s+|\\s+at\\s+"), "@")
        .replace(Regex("\\s+dot\\s+|\\s+point\\s+"), ".")
        .replace(Regex("\\s+underscore\\s+"), "_")
        .replace(Regex("\\s+(dash|hyphen)\\s+"), "-")
        .replace(Regex("\\s+"), "")

    /** How to say a value back so the user can catch mistakes: digits in groups, email as is. */
    fun readBack(value: String, kind: InputKind?): String? = when (kind) {
        InputKind.PHONE, InputKind.NUMBER -> value.chunked(5).joinToString(", ") { it.toCharArray().joinToString(" ") }
        InputKind.EMAIL -> value.replace("@", " at ").replace(".", " dot ")
        else -> null
    }
}
