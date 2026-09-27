package com.hackathon.assistant.actions

import android.content.Intent
import android.net.Uri
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Opens the email composer pre-filled with recipient, subject and body; the user sends it manually. */
class EmailSkill : Skill {
    override val id = "compose_email"
    override val description = "Open the email composer pre-filled with a recipient, subject or body"
    override val slots = listOf(
        SlotSpec("to", "Recipient email address", required = false, question = ""),
        SlotSpec("subject", "Email subject line", required = false, question = ""),
        SlotSpec("body", "Email message body", required = false, question = ""),
    )
    override val examples = listOf(
        "email john@example.com saying I'll be late",
        "compose an email to my manager",
        "draft an email about the meeting",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
        val subject = args["subject"]?.trim()?.takeIf { it.isNotEmpty() }
        val body = args["body"]?.trim()?.takeIf { it.isNotEmpty() }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (to != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body != null) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(ctx.android.packageManager) == null) {
            return ActionResult.Failure("I couldn't find an email app to open.")
        }

        ctx.android.startActivity(intent)
        ActionResult.Success("Opening an email" + if (to != null) " to $to" else "")
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't open the email composer: ${e.message}")
    }
}
