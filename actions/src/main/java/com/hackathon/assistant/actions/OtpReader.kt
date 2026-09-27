package com.hackathon.assistant.actions

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Finds a fresh OTP in the SMS inbox, the way Android's own OTP autofill does, entirely on-device.
 * Only messages newer than [sinceMs] count, and the body must look like an OTP message (mentions
 * OTP / code / verification). If [appHint] is given ("Zepto"), a message naming it wins.
 */
object OtpReader {
    private val CODE = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val OTP_WORDS = listOf("otp", "one time password", "verification code", "code is", "is your code", "login code")

    /** Polls the inbox for up to [waitMs]; the code, or null. Needs READ_SMS (granted by install.sh). */
    suspend fun await(context: Context, sinceMs: Long, appHint: String?, waitMs: Long = 20_000): String? {
        var waited = 0L
        while (true) {
            find(context, sinceMs, appHint)?.let { return it }
            if (waited >= waitMs) return null
            delay(1_000); waited += 1_000
        }
    }

    fun find(context: Context, sinceMs: Long, appHint: String?): String? {
        val msgs = runCatching { SmsRepository.query(context, limit = 10) }.getOrDefault(emptyList())
            .filter { it.timestampMs >= sinceMs && OTP_WORDS.any { w -> it.body.contains(w, ignoreCase = true) } }
        val best = appHint?.let { h -> msgs.firstOrNull { it.body.contains(h, ignoreCase = true) || it.sender.contains(h, ignoreCase = true) } }
            ?: msgs.firstOrNull()
            ?: return null
        return codeIn(best.body)
    }

    /**
     * The OTP in a message, not the other numbers in it ("Rs.2499", "card xx4021", "a/c 1234"):
     * runs right after money/account words are skipped; the run nearest the OTP keyword wins, and
     * a 6-digit run beats others at the same distance.
     */
    internal fun codeIn(body: String): String? {
        val lower = body.lowercase()
        val keyword = OTP_WORDS.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: 0
        return CODE.findAll(body)
            .filter { m -> !NOT_OTP_BEFORE.containsMatchIn(lower.substring(maxOf(0, m.range.first - 8), m.range.first)) }
            .minByOrNull { m -> kotlin.math.abs(m.range.first - keyword) * 2 + if (m.value.length == 6) 0 else 1 }
            ?.value
    }

    private val NOT_OTP_BEFORE = Regex("(rs\\.?|inr|₹|xx+|x{2,}|a/c|acct|card|ending|no\\.?)\\s*[:.-]?\\s*$")
}
