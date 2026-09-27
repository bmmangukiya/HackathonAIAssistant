package com.hackathon.assistant

import com.hackathon.assistant.core.InputKind

/**
 * Checks a spoken form answer against what the field expects, so a bad answer is re-asked with a
 * reason ("That's 8 digits; a phone number has 10.") instead of being typed and failing later.
 * The field's label adds context the input type doesn't carry: OTP, PIN code, age, name.
 */
object FieldValidator {
    private val EMAIL = Regex("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")

    /** Small, safe clean-ups before validating: Indian mobile numbers said with +91 / 0 prefixes. */
    fun clean(value: String, kind: InputKind?, label: String): String {
        val v = value.trim()
        if (isPhone(kind, label)) {
            val d = v.filter(Char::isDigit)
            return when {
                d.length == 12 && d.startsWith("91") -> d.drop(2)
                d.length == 11 && d.startsWith("0") -> d.drop(1)
                else -> d
            }
        }
        return v
    }

    /** Null if [value] fits the field; otherwise one spoken sentence saying what's wrong. */
    fun problem(value: String, kind: InputKind?, label: String): String? {
        val l = label.lowercase()
        if (value.isBlank()) return "I didn't get a value."
        return when {
            "otp" in l || "verification code" in l || "one time" in l -> {
                val d = value.filter(Char::isDigit)
                if (d.length !in 4..8 || d.length != value.length) "An OTP is 4 to 8 digits; I heard ${spokenLen(d)}." else null
            }
            isPhone(kind, label) -> {
                val d = value.filter(Char::isDigit)
                when {
                    d.length != value.length -> "A phone number should only have digits."
                    d.length != 10 -> "That's ${spokenLen(d)}; a phone number has 10."
                    d.first() !in '6'..'9' -> "Indian mobile numbers start with 6, 7, 8 or 9."
                    else -> null
                }
            }
            "pin code" in l || "pincode" in l || "postal" in l || "zip" in l ->
                if (value.filter(Char::isDigit).length != 6) "A PIN code has 6 digits." else null
            kind == InputKind.EMAIL || "email" in l -> if (!EMAIL.matches(value.lowercase())) "That doesn't sound like an email address." else null
            kind == InputKind.NUMBER -> if (value.none(Char::isDigit)) "I need a number." else null
            "name" in l && "user" !in l -> if (value.none(Char::isLetter) || value.any(Char::isDigit)) "A name shouldn't have numbers." else null
            else -> null
        }
    }

    fun isOtp(label: String): Boolean {
        val l = label.lowercase()
        return "otp" in l || "verification code" in l || "one time" in l
    }

        /** A digits-only field that is still too short: the user probably paused mid-number. */
    fun wantsMoreDigits(value: String, kind: InputKind?, label: String): Boolean {
        val d = value.filter(Char::isDigit)
        if (d.isEmpty() || d.length != value.length) return false
        val l = label.lowercase()
        return when {
            "otp" in l || "verification code" in l -> d.length < 4
            isPhone(kind, label) -> d.length < 10
            else -> false
        }
    }

        private fun isPhone(kind: InputKind?, label: String): Boolean {
        val l = label.lowercase()
        return kind == InputKind.PHONE || "phone" in l || "mobile" in l
    }

    private fun spokenLen(d: String) = if (d.length == 1) "1 digit" else "${d.length} digits"
}
