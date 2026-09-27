package com.hackathon.assistant.actions

import android.provider.CalendarContract
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reads the user's calendar events for a day (today by default, or tomorrow). */
class CalendarSkill : Skill {

    override val id = "read_calendar"
    override val description = "Read the user's calendar events for a day (today or tomorrow)"

    override val slots = listOf(
        SlotSpec(
            name = "day",
            description = "which day to read: today or tomorrow, defaults to today",
            required = false,
            question = "",
        ),
    )

    override val examples = listOf(
        "what's on my calendar today",
        "do I have any meetings",
        "what's my schedule tomorrow",
        "what are my events today",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val tomorrow = args["day"]?.trim()?.lowercase() == "tomorrow"
        val dayWord = if (tomorrow) "tomorrow" else "today"

        // Midnight of the target day (inclusive) to midnight of the next day (exclusive).
        val startCal = Calendar.getInstance().apply {
            if (tomorrow) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val beginMs = startCal.timeInMillis
        val endMs = (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        // begin time, title, optional location
        val events = mutableListOf<Triple<Long, String, String?>>()
        CalendarContract.Instances.query(ctx.android.contentResolver, projection, beginMs, endMs)
            ?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled event"
                    val start = c.getLong(1)
                    val location = c.getString(3)?.trim()?.takeIf { it.isNotEmpty() }
                    events += Triple(start, title, location)
                }
            }
        events.sortBy { it.first }

        if (events.isEmpty()) {
            return ActionResult.Success("You have no events $dayWord.")
        }

        val header = "You have ${events.size} event${if (events.size == 1) "" else "s"} $dayWord."
        val body = events.joinToString(" ") { (start, title, location) ->
            val where = location?.let { ", at $it" } ?: ""
            "At ${formatTime(start)}, $title$where."
        }
        ActionResult.Success("$header $body")
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't read your calendar: ${e.message}")
    }

    /** "10 AM" on the hour, otherwise "2:30 PM" — natural for text-to-speech. */
    private fun formatTime(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val pattern = if (cal.get(Calendar.MINUTE) == 0) "h a" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    }
}
