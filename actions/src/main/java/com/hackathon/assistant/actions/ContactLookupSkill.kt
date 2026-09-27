package com.hackathon.assistant.actions

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Looks up a contact and reads their phone number aloud. */
class ContactLookupSkill : Skill {
    override val id = "lookup_contact"
    override val description = "Tell a contact's phone number"
    override val slots = listOf(
        SlotSpec(
            name = "contact",
            description = "contact name or number to look up",
            question = "Whose number do you want?",
        ),
    )
    override val examples = listOf(
        "what's mom's number",
        "give me Rahul's phone number",
        "look up dad's contact",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val wanted = args["contact"].orEmpty().trim()
        if (wanted.isEmpty()) {
            ActionResult.Failure("I didn't catch whose number you wanted.")
        } else {
            val match = Contacts.find(ctx.android, wanted)
            if (match == null) {
                ActionResult.Failure("I couldn't find $wanted in your contacts.")
            } else {
                ActionResult.Success("${match.name}'s number is ${readable(match.number)}")
            }
        }
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't look up that contact: ${e.message}")
    }

    /** Group digits so text-to-speech reads the number in short, clear chunks. */
    private fun readable(number: String): String {
        val prefix = if (number.trim().startsWith("+")) "+" else ""
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return number.trim()
        val groups = digits.chunked(3).toMutableList()
        if (groups.size >= 2 && groups.last().length == 1) {
            val tail = groups.removeAt(groups.lastIndex)
            groups[groups.lastIndex] = groups.last() + tail
        }
        return prefix + groups.joinToString(" ")
    }
}
