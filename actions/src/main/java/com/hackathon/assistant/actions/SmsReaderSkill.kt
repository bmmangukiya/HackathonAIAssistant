package com.hackathon.assistant.actions

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Skill: read, filter and surface SMS messages.
 *
 * The skill returns a formatted text block. The Assistant layer passes that text
 * to Rakshit's VoiceIO.speak() so the user hears the summary.
 *
 * Supported queries (all via natural language → slot mapping by the LLM):
 *   "summarize my SMS"         → recent 10 inbox messages, all senders
 *   "read unread messages"     → unread inbox messages only
 *   "messages from HDFC"       → filter by sender substring "HDFC"
 *   "last 5 messages"          → limit override
 */
class SmsReaderSkill : Skill {

    override val id = "read_sms"
    override val description = "Read, filter, or summarize SMS messages from the inbox"
    override val risk = Risk.SAFE

    override val slots = listOf(
        SlotSpec(
            name = "filter",
            description = "sender name or keyword to filter by, or empty for all",
            required = false,
            question = "Messages from a specific person or all messages?",
        ),
        SlotSpec(
            name = "unread_only",
            description = "true to show only unread messages, false or omit for all",
            required = false,
            question = "",
        ),
        SlotSpec(
            name = "limit",
            description = "max number of messages to return, default is 10",
            required = false,
            question = "",
        ),
    )

    override val examples = listOf(
        "summarize my SMS",
        "read my unread messages",
        "read messages from HDFC",
        "show me my last 5 texts",
        "do I have any new messages",
        "what did mom text me",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val filter = args["filter"]?.trim()?.takeIf { it.isNotBlank() }
        val unreadOnly = args["unread_only"]?.trim()?.lowercase() == "true"
        val limit = args["limit"]?.trim()?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val messages = runCatching {
            SmsRepository.query(
                context = ctx.android,
                folder = SmsRepository.Folder.INBOX,
                limit = limit,
                onlyUnread = unreadOnly,
                senderFilter = filter,
            )
        }.getOrElse { e ->
            return ActionResult.Failure("Couldn't read SMS: ${e.message}")
        }

        if (messages.isEmpty()) {
            val qualifier = buildString {
                if (unreadOnly) append("unread ")
                if (filter != null) append("from $filter ")
            }
            return ActionResult.Success("No ${qualifier}messages found.")
        }

        val summary = buildString {
            val qualifier = buildString {
                if (unreadOnly) append("unread ")
                append("message${if (messages.size > 1) "s" else ""}")
                if (filter != null) append(" from $filter")
            }
            appendLine("Here are your ${messages.size} latest $qualifier:")
            appendLine()
            append(SmsRepository.format(messages, resolveNames = true, context = ctx.android))
        }

        // Return the formatted text as the success message.
        // The Assistant will pass this to VoiceIO.speak() — Rakshit's responsibility.
        return ActionResult.Success(summary)
    }
}
