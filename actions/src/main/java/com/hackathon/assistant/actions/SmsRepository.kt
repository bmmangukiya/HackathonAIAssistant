package com.hackathon.assistant.actions

import android.content.Context
import android.provider.Telephony

/**
 * Reads SMS from the device inbox via ContentResolver.
 *
 * Kept as a plain data-access object so any skill (or future code) can call it
 * without going through the Skill interface. The Skill layer decides *what* to
 * fetch; this layer only decides *how* to read it from the OS.
 */
internal object SmsRepository {

    data class Sms(
        val sender: String,   // phone number or contact name if resolved
        val body: String,
        val timestampMs: Long,
        val isRead: Boolean,
        val threadId: Long,
    )

    /** Inbox folders the OS exposes. */
    enum class Folder(val uri: android.net.Uri) {
        INBOX(Telephony.Sms.Inbox.CONTENT_URI),
        SENT(Telephony.Sms.Sent.CONTENT_URI),
        ALL(Telephony.Sms.CONTENT_URI),
    }

    /**
     * DEBUG/DEMO test seam. When non-null, [query] returns these messages instead of
     * hitting the real SMS provider. Null in production, so real inboxes are unaffected.
     *
     * This exists because a SIM-less test device has no real SMS and Android forbids
     * writing to the SMS provider from adb (only the default SMS app may write). Seeding
     * here lets us test formatting/filtering and gives a repeatable demo.
     */
    @Volatile
    var demoOverride: List<Sms>? = null

    /**
     * Genuine-looking Indian SMS for testing and the demo — real DLT header formats
     * (VM-/AD-/BP- prefixes), authentic OTP and transaction phrasing, real merchants.
     * These read exactly like a real inbox so the demo is credible to judges.
     */
    fun sampleMessages(): List<Sms> {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val hr = 3_600_000L
        return listOf(
            Sms("VM-HDFCBK", "482910 is the OTP for txn of Rs.2,499.00 at Amazon on your HDFC Bank Credit Card xx4021. Valid for 5 mins. Do NOT share this OTP. -HDFC Bank", now - 4 * min, isRead = false, threadId = 1),
            Sms("AD-ICICIB", "Dear Customer, Rs.749.00 debited from A/c XX2910 on 26-Sep-25 for SWIGGY. Avl Bal: Rs.18,204.55. Not you? Call 18001234. -ICICI Bank", now - 55 * min, isRead = false, threadId = 2),
            Sms("+919876543210", "Reached office na? Call me when you're free, need to discuss the weekend plan", now - 2 * hr, isRead = true, threadId = 3),
            Sms("BP-AMAZON", "Your Amazon order (Sony WH-1000XM5) has been shipped and will be delivered by tomorrow 7 PM. Track: amzn.in/d/8kL2mQ", now - 5 * hr, isRead = false, threadId = 4),
            Sms("JX-ZOMATO", "Your order from Paradise Biryani is on the way! Arjun is arriving in 12 mins. Track live: zoma.to/x92h", now - 7 * hr, isRead = true, threadId = 5),
            Sms("VK-SBIINB", "Your A/c XX8830 credited by Rs.45,000.00 on 25-Sep-25 (Salary). Avl Bal Rs.63,204.55. -SBI", now - 27 * hr, isRead = true, threadId = 6),
            Sms("AX-JIOTEL", "Your Jio recharge of Rs.349 is successful. Validity 28 days, 2GB/day. Enjoy unlimited calls. -Jio", now - 50 * hr, isRead = true, threadId = 7),
        )
    }

    /**
     * Returns messages sorted newest-first.
     *
     * @param folder  which mailbox to read
     * @param limit   max messages to return (keep small — model context is finite)
     * @param onlyUnread  when true, filters to unread messages only
     * @param senderFilter  when non-null, keeps only messages from senders whose
     *                      address contains this substring (case-insensitive)
     */
    fun query(
        context: Context,
        folder: Folder = Folder.INBOX,
        limit: Int = 30,
        onlyUnread: Boolean = false,
        senderFilter: String? = null,
    ): List<Sms> {
        // Debug/demo seam: apply the same filters to seeded data, so the demo path
        // exercises identical filtering/sorting logic as the real provider path.
        demoOverride?.let { seeded ->
            return seeded.asSequence()
                .filter { !onlyUnread || !it.isRead }
                .filter { senderFilter == null || it.sender.contains(senderFilter, ignoreCase = true) }
                .sortedByDescending { it.timestampMs }
                .take(limit)
                .toList()
        }

        // ADDRESS is a phone number or a sender ID ("AX-ZEPTO"), never a name: "mom" becomes Mom's
        // number (last 10 digits, so +91/0 prefixes still match); sender IDs are matched as given.
        val addressFilter = senderFilter?.trim()?.let { f ->
            if (f.any(Char::isDigit)) f.filter(Char::isDigit).takeLast(10)
            else Contacts.find(context, f)?.number?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.isNotEmpty() } ?: f
        }
        val selection = buildList<String> {
            if (onlyUnread) add("${Telephony.Sms.READ} = 0")
            if (addressFilter != null) add("${Telephony.Sms.ADDRESS} LIKE ?")
        }.joinToString(" AND ").ifEmpty { null }

        val selectionArgs = buildList<String> {
            if (addressFilter != null) add("%$addressFilter%")
        }.toTypedArray().ifEmpty { null }

        val out = mutableListOf<Sms>()
        context.contentResolver.query(
            folder.uri,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            ),
            selection,
            selectionArgs,
            // No "LIMIT" in the sort order: strict providers reject it. The loop stops at [limit].
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (out.size < limit && cursor.moveToNext()) {
                out += Sms(
                    sender = cursor.getString(addrIdx) ?: "Unknown",
                    body = cursor.getString(bodyIdx) ?: "",
                    timestampMs = cursor.getLong(dateIdx),
                    isRead = cursor.getInt(readIdx) == 1,
                    threadId = cursor.getLong(threadIdx),
                )
            }
        }
        return out
    }

    /**
     * Format a list of SMS messages into a compact prompt-friendly string.
     * Rakshit's speaking tool reads this text aloud; the LLM uses it to answer
     * questions about messages.
     *
     * Example output:
     *   [1] From: +91 98765 43210 | Today 10:42 AM
     *       "Your OTP is 482910. Do not share."
     *   [2] From: Mom | Yesterday 6:15 PM
     *       "Call me when you reach"
     */
    fun format(messages: List<Sms>, resolveNames: Boolean = true, context: Context? = null): String {
        if (messages.isEmpty()) return "No messages found."
        val contacts: Map<String, String> = if (resolveNames && context != null) {
            runCatching { loadContactMap(context) }.getOrDefault(emptyMap())
        } else emptyMap()

        val now = System.currentTimeMillis()
        return messages.mapIndexed { i, sms ->
            val name = contacts[normalizeNumber(sms.sender)] ?: sms.sender
            val time = relativeTime(sms.timestampMs, now)
            val preview = sms.body.take(120).replace('\n', ' ')
            "[${i + 1}] From: $name | $time\n    \"$preview\""
        }.joinToString("\n")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun normalizeNumber(raw: String) = raw.filter { it.isDigit() }.takeLast(10)

    private fun loadContactMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = normalizeNumber(c.getString(1) ?: continue)
                if (number.isNotEmpty()) map[number] = name
            }
        }
        return map
    }

    private fun relativeTime(ts: Long, now: Long): String {
        val diff = now - ts
        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val fullSdf = java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.getDefault())
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000}m ago"
            diff < 24 * 3_600_000L -> "Today ${sdf.format(java.util.Date(ts))}"
            diff < 48 * 3_600_000L -> "Yesterday ${sdf.format(java.util.Date(ts))}"
            else -> fullSdf.format(java.util.Date(ts))
        }
    }
}
