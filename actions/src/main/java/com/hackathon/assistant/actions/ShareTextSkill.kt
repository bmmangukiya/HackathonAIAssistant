package com.hackathon.assistant.actions

import android.content.Intent
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Shares text to any app via the system share sheet. */
class ShareTextSkill : Skill {
    override val id = "share_text"
    override val description = "Share text to any app via the system share sheet"
    override val slots = listOf(
        SlotSpec(
            name = "text",
            description = "The text to share",
            required = true,
            question = "What should I share?",
        ),
    )
    override val examples = listOf(
        "share this: meeting moved to 4pm",
        "share my address with someone",
        "send this text to a friend",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val text = args["text"]
        if (text.isNullOrBlank()) {
            ActionResult.Failure("I don't have any text to share.")
        } else {
            val sendIntent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent.createChooser(sendIntent, "Share via")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (chooser.resolveActivity(ctx.android.packageManager) == null) {
                ActionResult.Failure("There's no app available to share with.")
            } else {
                ctx.android.startActivity(chooser)
                ActionResult.Success("Opening the share sheet")
            }
        }
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't open the share sheet: ${e.message}")
    }
}
