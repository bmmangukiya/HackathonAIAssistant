package com.hackathon.assistant.actions

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Sets the phone's ringer mode to silent, vibrate, or normal.
 *
 * On API 23+ switching *into* silent or vibrate requires Do Not Disturb policy
 * access, so we guard on [android.app.NotificationManager.isNotificationPolicyAccessGranted]
 * and ask the user to grant it before failing rather than throwing a SecurityException.
 */
class RingerControlSkill : Skill {
    override val id = "set_ringer"
    override val description = "Set the phone's ringer mode to silent, vibrate, or normal."
    override val slots = listOf(
        SlotSpec(
            name = "mode",
            description = "Desired ringer mode: silent, vibrate, or normal.",
            required = true,
            question = "Silent, vibrate or normal?",
        ),
    )
    override val examples = listOf(
        "put my phone on silent",
        "set ringer to vibrate",
        "turn the ringer back to normal",
        "silence my phone",
    )
    override val risk = Risk.SAFE

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val requested = args["mode"]?.trim()?.lowercase()
        if (requested.isNullOrEmpty()) {
            return ActionResult.Failure("I didn't catch which ringer mode you wanted.")
        }

        // "silent mode", "put it on vibrate", "ringer normal": match the word, not the whole phrase.
        val (targetMode, label) = when {
            "vibrat" in requested -> android.media.AudioManager.RINGER_MODE_VIBRATE to "vibrate"
            "silent" in requested || "mute" in requested -> android.media.AudioManager.RINGER_MODE_SILENT to "silent"
            "normal" in requested || "loud" in requested || "ring" in requested || "sound" in requested ->
                android.media.AudioManager.RINGER_MODE_NORMAL to "normal"
            else -> return ActionResult.Failure(
                "I can set the ringer to silent, vibrate or normal, but not \"$requested\".",
            )
        }

        return try {
            // Only silent needs DND access (it turns Do Not Disturb on); vibrate and normal don't.
            val needsDndAccess = targetMode == android.media.AudioManager.RINGER_MODE_SILENT
            if (needsDndAccess) {
                val nm = ctx.android.getSystemService(android.app.NotificationManager::class.java)
                if (nm == null || !nm.isNotificationPolicyAccessGranted) {
                    return ActionResult.Failure(
                        "I need Do Not Disturb access first. Please enable it for this app in " +
                            "Settings > Notification access / DND access.",
                    )
                }
            }

            val am = ctx.android.getSystemService(android.media.AudioManager::class.java)
                ?: return ActionResult.Failure("I couldn't reach the audio controls on this device.")
            am.ringerMode = targetMode
            ActionResult.Success("Phone set to $label")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change the ringer: ${e.message}")
        }
    }
}
