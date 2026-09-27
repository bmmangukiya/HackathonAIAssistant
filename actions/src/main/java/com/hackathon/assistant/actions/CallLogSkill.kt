package com.hackathon.assistant.actions

import android.provider.CallLog
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/**
 * Skill: read the recent call log, optionally only missed calls.
 *
 * Queries [CallLog.Calls.CONTENT_URI] for the most recent entries (newest first)
 * and returns a short, speakable summary. The Assistant layer reads the returned
 * [ActionResult.Success.message] aloud via text-to-speech.
 *
 * Supported queries (natural language -> slot mapping by the LLM):
 *   "who called me"        -> recent calls, all types
 *   "any missed calls"     -> filter = "missed"
 *   "show my last 3 calls" -> limit override
 */
class CallLogSkill : Skill {

    override val id = "read_call_log"
    override val description = "Read recent phone calls, optionally only missed ones"
    override val risk = Risk.SAFE

    override val slots = listOf(
        SlotSpec(
            name = "filter",
            description = "set to \"missed\" to only report missed calls, or omit for all calls",
            required = false,
            question = "",
        ),
        SlotSpec(
            name = "limit",
            description = "max number of calls to report, default is 5",
            required = false,
            question = "",
        ),
    )

    override val examples = listOf(
        "any missed calls",
        "who called me",
        "show my recent calls",
        "did I miss any calls",
        "read my last 3 calls",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val missedOnly = args["filter"]?.lowercase()?.contains("miss") == true
        val limit = args["limit"]?.trim()?.toIntOrNull()?.coerceIn(1, 50) ?: 5

        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null

        val entries = mutableListOf<String>()
        ctx.android.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            null,
            // NOTE: the CallLog provider rejects a "LIMIT n" suffix in the sort order
            // ("Invalid token LIMIT"). We sort newest-first and cap in the loop below instead.
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            while (c.moveToNext() && entries.size < limit) {
                val name = nameIdx.takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
                val number = numberIdx.takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
                val who = name ?: number ?: "an unknown number"
                val type = typeIdx.takeIf { it >= 0 }?.let { c.getInt(it) } ?: -1
                entries += when (type) {
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing call to $who"
                    CallLog.Calls.MISSED_TYPE -> "Missed call from $who"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming call from $who"
                    else -> "Call from $who"
                }
            }
        }

        if (entries.isEmpty()) {
            ActionResult.Success(if (missedOnly) "No missed calls." else "No recent calls.")
        } else {
            val kind = if (missedOnly) "missed" else "recent"
            val header = "You have ${entries.size} $kind " +
                "call${if (entries.size > 1) "s" else ""}."
            ActionResult.Success("$header " + entries.joinToString(". ") + ".")
        }
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't read the call log: ${e.message}")
    }
}
