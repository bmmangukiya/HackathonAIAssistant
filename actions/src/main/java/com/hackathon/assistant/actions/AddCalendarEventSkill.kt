package com.hackathon.assistant.actions

import android.content.Intent
import android.provider.CalendarContract
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import java.util.Calendar

/**
 * Opens the calendar's new-event editor pre-filled with a title (and optional time/location).
 * The user reviews and saves, so no calendar write permission is needed — this is just an intent.
 */
class AddCalendarEventSkill : Skill {

    override val id = "create_calendar_event"
    override val description = "Open the calendar's new-event editor pre-filled with a title, time and location"

    override val slots = listOf(
        SlotSpec(
            name = "title",
            description = "what the event is, e.g. standup or lunch with Sam",
            required = true,
            question = "What's the event?",
        ),
        SlotSpec(
            name = "time",
            description = "start time today, 24h HH:MM or like '3 pm'; omitted if not given",
            required = false,
            question = "",
        ),
        SlotSpec(
            name = "location",
            description = "where the event is, optional",
            required = false,
            question = "",
        ),
    )

    override val examples = listOf(
        "add a meeting at 3 pm called standup",
        "create a calendar event for lunch tomorrow",
        "schedule dentist at 17:30",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val title = args["title"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ActionResult.Failure("I need a name for the event.")

        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        args["location"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }

        args["time"]?.let { parseTime(it) }?.let { beginMs ->
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginMs + ONE_HOUR_MS)
        }

        if (intent.resolveActivity(ctx.android.packageManager) == null) {
            return ActionResult.Failure("I couldn't find a calendar app to add the event.")
        }

        ctx.android.startActivity(intent)
        ActionResult.Success("Opening your calendar to add $title")
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't open the calendar: ${e.message}")
    }

    /**
     * Parses "17:30", "3 pm", "3:30pm" etc. into today's epoch millis at that hour:minute.
     * Returns null when the text isn't a recognisable time, so the caller just omits the start.
     */
    private fun parseTime(raw: String): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val isPm = s.contains("pm")
        val isAm = s.contains("am")
        val core = s.replace("am", "").replace("pm", "").trim()
        if (core.isEmpty()) return null

        val parts = core.split(":", ".")
        val hourRaw = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

        var hour = hourRaw
        when {
            isPm && hour < 12 -> hour += 12
            isAm && hour == 12 -> hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null

        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
