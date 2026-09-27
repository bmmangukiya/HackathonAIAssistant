package com.hackathon.assistant.actions

import android.media.AudioManager
import android.view.KeyEvent
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Controls whatever app is currently playing media by dispatching hardware media key events. */
class MediaControlSkill : Skill {
    override val id = "media_control"
    override val description = "Pause, resume or skip media that is ALREADY playing (to start a new song use play_music)"
    override val slots = listOf(
        SlotSpec(
            name = "action",
            description = "What to do with playback: play, pause, next or previous",
            question = "Play, pause, next or previous?",
        ),
    )
    override val examples = listOf(
        "pause the music",
        "next song",
        "resume playback",
        "previous track",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val requested = args["action"].orEmpty().trim().lowercase()
        val (keyCode, spoken) = when {
            requested.contains("next") || requested.contains("skip") || requested.contains("forward") ->
                KeyEvent.KEYCODE_MEDIA_NEXT to "Skipping to next"
            requested.contains("prev") || requested.contains("back") ->
                KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Going to the previous track"
            // Explicit keys, not PLAY_PAUSE: "pause" must never start paused music, nor "resume" pause it.
            requested.contains("pause") || requested.contains("stop") ->
                KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused"
            requested.contains("play") || requested.contains("resume") || requested.contains("start") ->
                KeyEvent.KEYCODE_MEDIA_PLAY to "Resuming playback"
            requested.contains("toggle") || requested.isEmpty() ->
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Toggled playback"
            else ->
                return ActionResult.Failure("I didn't catch that. Say play, pause, next or previous.")
        }
        return try {
            val audioManager = ctx.android.getSystemService(AudioManager::class.java)
                ?: return ActionResult.Failure("Media control isn't available on this device")
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ActionResult.Success(spoken)
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't control media playback: ${e.message}")
        }
    }
}
