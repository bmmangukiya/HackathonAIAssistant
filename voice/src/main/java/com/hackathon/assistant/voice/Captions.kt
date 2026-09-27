package com.hackathon.assistant.voice

/**
 * What the card shows. [heard] is the user's words (grey until [heardFinal]); [reply] is what
 * Jarvis says, with [spokenUpTo] characters already spoken (-1: not speaking); [notice] is a
 * short status line such as "Didn't catch that".
 */
data class Captions(
    val heard: String = "",
    val heardFinal: Boolean = false,
    /** Tentative words after [heard], still being recognised (shown lighter). */
    val pending: String = "",
    /** The recognizer hears a voice right now (its own speech detection, not loudness). */
    val userSpeaking: Boolean = false,
    val reply: String = "",
    val spokenUpTo: Int = -1,
    val notice: String = "",
)
