package com.hackathon.assistant.actions

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk

/**
 * Skills over the limited on-device [PersonalMemory]: save, look up and delete personal details.
 * Everything stays on the phone. See [PersonalMemory] for the storage limits and privacy notes.
 */
internal object MemorySkills {

    val remember = SimpleSkill(
        "remember",
        "Save a personal detail on-device for later reuse (name, email, phone, home/work address, etc.)",
        listOf(
            slot("key", "what the detail is, e.g. email, phone, home address", "What should I call this?"),
            slot("value", "the detail to store", "And what should I save for it?"),
        ),
        listOf(
            "remember my email is bansi@example.com",
            "my home address is 42 MG Road Bengaluru",
            "save my phone number as 9876543210",
        ),
    ) { args ->
        val key = args.getValue("key")
        if (PersonalMemory(android).put(key, args.getValue("value"))) {
            ActionResult.Success("Saved your ${spoken(key)}.")
        } else {
            ActionResult.Failure(
                "I couldn't save that — my memory is full (${PersonalMemory.MAX_FACTS} items). " +
                    "Ask me to forget something first.",
            )
        }
    }

    val recall = SimpleSkill(
        "recall",
        "Look up a personal detail you asked me to remember; leave key empty to list everything saved",
        listOf(slot("key", "which detail, e.g. email; empty to list all", "", required = false)),
        listOf("what's my email", "what's my home address", "what do you know about me"),
    ) { args ->
        val mem = PersonalMemory(android)
        val key = args["key"].orEmpty().trim()
        when {
            key.isEmpty() -> {
                val all = mem.all()
                if (all.isEmpty()) ActionResult.Success("I haven't saved anything about you yet.")
                else ActionResult.Success(
                    "I remember: " + all.entries.joinToString(", ") { "${it.key.replace('_', ' ')} is ${it.value}" },
                )
            }
            else -> mem.get(key)?.let { ActionResult.Success("Your ${spoken(key)} is $it.") }
                ?: ActionResult.Success("I don't have your ${spoken(key)} saved.")
        }
    }

    val forget = SimpleSkill(
        "forget",
        "Delete a saved personal detail; say 'everything' to clear all of them",
        listOf(slot("key", "which detail to forget, or 'everything'", "What should I forget?")),
        listOf("forget my address", "forget everything about me"),
        Risk.CONFIRM,
    ) { args ->
        val mem = PersonalMemory(android)
        val key = args.getValue("key").trim()
        when {
            key.lowercase() in setOf("everything", "all", "all of it", "everything about me") -> {
                mem.clear(); ActionResult.Success("Cleared everything I had saved about you.")
            }
            mem.forget(key) -> ActionResult.Success("Forgot your ${spoken(key)}.")
            else -> ActionResult.Failure("I didn't have your ${spoken(key)} saved.")
        }
    }

    val all = listOf(remember, recall, forget)

    /** A snake_case key read aloud naturally: "home_address" -> "home address". */
    private fun spoken(key: String) = key.trim().replace('_', ' ')
}
