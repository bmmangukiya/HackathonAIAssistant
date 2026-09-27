package com.hackathon.assistant.actions

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.net.Uri
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Send a WhatsApp message to a contact.
 *
 * Strategy:
 *  1. Resolve contact name → phone number via [Contacts].
 *  2. Deep-link into WhatsApp with message pre-filled using the official wa.me URI scheme.
 *  3. Hand a follow-up goal to the navigator to tap the Send button — this way we never
 *     auto-send without the user seeing it (good for a CONFIRM-class skill) but still drive
 *     the last tap automatically.
 *
 * Why a dedicated class (not SimpleSkill): contact resolution adds a Failure path, and the
 * number-formatting logic is non-trivial enough to deserve named functions and tests.
 */
class WhatsAppSkill : Skill {

    override val id = "whatsapp_message"
    override val description = "Send a WhatsApp message to a contact"
    override val risk = Risk.CONFIRM  // reads back "Sending to <name>: <message>" before acting

    override val slots = listOf(
        SlotSpec(
            name = "contact",
            description = "contact name or phone number to send to",
            required = true,
            question = "Who should I WhatsApp?",
        ),
        SlotSpec(
            name = "message",
            description = "the text of the message to send",
            required = true,
            question = "What should the message say?",
        ),
    )

    override val examples = listOf(
        "whatsapp mom I'll be late",
        "send a whatsapp to Rahul saying happy birthday",
        "message dad on whatsapp that I reached safely",
        "text Priya on whatsapp",
        "whatsapp my boss I am working from home today",
    )

    /**
     * Only when WhatsApp is installed AND logged in. WhatsApp registers an Android account of
     * type "com.whatsapp" after login and syncs contacts under it; either signal counts.
     */
    override fun isAvailable(context: Context): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0) }.isSuccess
        if (!installed) return false
        val account = runCatching {
            AccountManager.get(context).getAccountsByType(WHATSAPP_PACKAGE).isNotEmpty()
        }.getOrDefault(false)
        if (account) return true
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?", arrayOf(WHATSAPP_PACKAGE), null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val contactArg = args["contact"]?.trim()
            ?: return ActionResult.Failure("No contact specified")
        val message = args["message"]?.trim()
            ?: return ActionResult.Failure("No message specified")

        // Resolve name → number using the shared Contacts helper.
        val match = Contacts.find(ctx.android, contactArg)
            ?: return ActionResult.Failure(
                "I couldn't find \"$contactArg\" in your contacts. " +
                "Try saying their full name or number."
            )

        // wa.me requires E.164 format: strip non-digits, ensure country code.
        val number = formatForWaMe(match.number)
            ?: return ActionResult.Failure(
                "I found ${match.name} but couldn't format their number (${match.number}). " +
                "Try providing the number directly with country code."
            )

        // Build the official WhatsApp deep link: opens WA directly to the compose screen
        // with the message pre-filled. The user (or our navigator) just taps Send.
        val uri = Uri.parse("https://wa.me/$number?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Fallback: if WhatsApp isn't installed, try without setPackage (opens browser / other).
        val canOpen = intent.resolveActivity(ctx.android.packageManager) != null
        if (!canOpen) {
            // Try without forcing the WhatsApp package (lets the OS choose).
            val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fallback.resolveActivity(ctx.android.packageManager) == null) {
                return ActionResult.Failure("WhatsApp doesn't seem to be installed")
            }
            ctx.android.startActivity(fallback)
            return ActionResult.Success(
                "Opened WhatsApp compose for ${match.name}. Tap Send to confirm.",
                followUpGoal = "Tap the Send button to send the WhatsApp message. Then done.",
            )
        }

        ctx.android.startActivity(intent)
        return ActionResult.Success(
            "Ready to send to ${match.name}. Tapping Send now.",
            followUpGoal = "Tap the green Send button or the arrow button to send the message. Then done.",
        )
    }

    // ── number formatting ─────────────────────────────────────────────────

    /**
     * Converts any stored number to the wa.me-compatible format (digits only, with country code).
     *
     * Examples:
     *   "+91 98765 43210" → "919876543210"
     *   "9876543210"      → "919876543210"  (assumes India +91)
     *   "0987654321"      → "91987654321"   (strips leading 0, adds +91)
     */
    private fun formatForWaMe(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return null  // too short to be a real number

        return when {
            // Already has a full country code (11+ digits starting with a valid CC).
            digits.length >= 11 && !digits.startsWith("0") -> digits
            // Indian mobile: 10 digits starting with 6-9.
            digits.length == 10 && digits[0].isDigit() && digits[0] >= '6' -> "91$digits"
            // Indian with leading 0 (landline style): strip 0, add 91.
            digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
            // Already looks like it has a country code prefix.
            digits.length > 10 -> digits
            else -> null
        }
    }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}
