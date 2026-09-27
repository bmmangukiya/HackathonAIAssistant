package com.hackathon.assistant.actions

import android.content.Intent
import android.net.Uri
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Compact skill definition: most skills are "build an intent from args and start it". */
internal class SimpleSkill(
    override val id: String,
    override val description: String,
    override val slots: List<SlotSpec>,
    override val examples: List<String>,
    override val risk: Risk = Risk.SAFE,
    private val run: suspend SkillContext.(Map<String, String>) -> ActionResult,
) : Skill {
    override suspend fun execute(ctx: SkillContext, args: Map<String, String>) = ctx.run(args)
}

internal fun slot(name: String, description: String, question: String, required: Boolean = true) =
    SlotSpec(name, description, required, question)

/** Starts [intent] from a non-activity context; Failure if nothing handles it. */
internal fun SkillContext.launch(
    intent: Intent,
    success: String,
    followUp: String? = null,
    doneWhen: (() -> Boolean)? = null,
): ActionResult {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val target = intent.resolveActivity(android.packageManager)
        ?: return ActionResult.Failure("No app can handle that")
    android.startActivity(intent)
    return ActionResult.Success(success, followUp, doneWhen, openedPackage = target.packageName)
}

internal fun uri(s: String): Uri = Uri.parse(s)
internal fun enc(s: String): String = Uri.encode(s)
