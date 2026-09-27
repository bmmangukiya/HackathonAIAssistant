package com.hackathon.assistant.actions

import android.content.ClipData
import android.content.ClipboardManager
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Reads what is currently on the clipboard, or copies spoken text onto it.
 *
 * NOTE: On Android 10+ only the FOREGROUND app may read the clipboard. When the
 * assistant is in the background a read can legitimately come back null/empty, so
 * that case is handled gracefully as "clipboard is empty" rather than an error.
 */
class ClipboardSkill : Skill {
    override val id = "clipboard"
    override val description = "Read what's on the clipboard, or copy text onto it"
    override val slots = listOf(
        SlotSpec(
            "action",
            "What to do: 'read' the clipboard or 'copy' text onto it. Defaults to read.",
            required = false,
            question = "Should I read the clipboard or copy something?",
        ),
        SlotSpec(
            "text",
            "The text to copy onto the clipboard (only needed when copying).",
            required = false,
            question = "What should I copy?",
        ),
    )
    override val examples = listOf("what's in my clipboard", "what did I copy", "copy this text")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val cm = ctx.android.getSystemService(ClipboardManager::class.java)
            ?: return ActionResult.Failure("The clipboard isn't available on this device")
        val action = args["action"]?.trim()?.lowercase() ?: "read"
        val isCopy = action.contains("copy") || action.contains("write") || action.contains("set")

        if (isCopy) {
            val text = args["text"]?.trim()
            if (text.isNullOrEmpty()) {
                return ActionResult.Failure("I don't have any text to copy")
            }
            cm.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
            ActionResult.Success("Copied that to your clipboard")
        } else {
            val clip = cm.primaryClip
            val copied = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()?.trim()
            } else {
                null
            }
            if (clip == null) {
                // Android 10+ hands the clipboard only to the app in front (or the keyboard), so null
                // here usually means "not allowed", not "empty".
                ActionResult.Failure("I can't read the clipboard from here: Android only lets the app on screen read it")
            } else if (copied.isNullOrEmpty()) {
                ActionResult.Success("Your clipboard is empty")
            } else {
                ActionResult.Success("Your clipboard says: $copied")
            }
        }
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't access the clipboard: ${e.message}")
    }
}
