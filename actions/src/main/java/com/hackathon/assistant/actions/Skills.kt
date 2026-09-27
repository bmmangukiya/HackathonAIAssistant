package com.hackathon.assistant.actions

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Risk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Deterministic skills: intents and deep links, with UI navigation only for the last mile. */
internal object Skills {

    val callContact = SimpleSkill(
        "call_contact", "Phone call a contact or number",
        listOf(slot("contact", "contact name or number", "Who should I call?")),
        listOf("call mom", "call 9876543210"), Risk.CONFIRM,
    ) { args ->
        val m = Contacts.find(android, args.getValue("contact"))
            ?: return@SimpleSkill ActionResult.Failure("I couldn't find ${args["contact"]} in your contacts")
        launch(Intent(Intent.ACTION_CALL, uri("tel:${m.number}")), "Calling ${m.name}")
    }

    val sendSms = SimpleSkill(
        "send_sms", "Send a text message (SMS)",
        listOf(
            slot("contact", "contact name or number", "Who should I text?"),
            slot("message", "the message text", "What should the message say?"),
        ),
        listOf("text dad I reached home"), Risk.CONFIRM,
    ) { args ->
        val m = Contacts.find(android, args.getValue("contact"))
            ?: return@SimpleSkill ActionResult.Failure("I couldn't find ${args["contact"]} in your contacts")
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) android.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(m.number, null, sms.divideMessage(args.getValue("message")), null, null)
        ActionResult.Success("Sending your message to ${m.name}")
    }

    val setAlarm = SimpleSkill(
        "set_alarm", "Set an alarm",
        listOf(
            slot("time", "24-hour HH:MM, e.g. 06:30 or 19:00", "What time should I set it for?"),
            slot("label", "what the alarm is for", "", required = false),
        ),
        listOf("wake me up at 6 30", "alarm for 7 pm"),
    ) { args ->
        val (h, m) = parseTime(args.getValue("time"))
            ?: return@SimpleSkill ActionResult.Failure("I didn't understand the time ${args["time"]}")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args["label"]?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        launch(intent, "Alarm set for ${spokenTime(h, m)}")
    }

    val setTimer = SimpleSkill(
        "set_timer", "Start a countdown timer",
        listOf(slot("seconds", "duration in seconds, e.g. 600 for 10 minutes", "For how long?")),
        listOf("timer for 10 minutes"),
    ) { args ->
        val secs = durationSeconds(args.getValue("seconds"))
            ?: return@SimpleSkill ActionResult.Failure("I didn't get the duration")
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Timer started for ${spokenDuration(secs)}",
        )
    }

    val flashlight = SimpleSkill(
        "flashlight", "Turn the flashlight (torch) on or off",
        listOf(slot("state", "on or off", "On or off?")),
        listOf("turn on the torch", "flashlight off"),
    ) { args ->
        val on = args.getValue("state").lowercase().let { "on" in it || "start" in it }
        val cm = android.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return@SimpleSkill ActionResult.Failure("No flashlight on this phone")
        cm.setTorchMode(id, on)
        ActionResult.Success(if (on) "Torch on" else "Torch off")
    }

    val takePhoto = SimpleSkill(
        "take_photo", "Open the camera and take a photo or selfie",
        listOf(slot("camera", "front for selfie, else back", "", required = false)),
        listOf("take a selfie", "click a photo"),
    ) { args ->
        val front = args["camera"]?.contains("front") == true
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", if (front) 1 else 0)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
        launch(intent, "Say cheese", followUp = if (front) "Switch to the front camera if needed, then tap the shutter button once. Then done." else "Tap the shutter button once. Then done.")
    }

    val openSettings = SimpleSkill(
        "open_settings", "Open a settings page: wifi, bluetooth, internet, display, battery, sound, location, apps",
        listOf(slot("page", "which settings page", "Which settings?")),
        listOf("open wifi settings", "bluetooth settings"),
    ) { args ->
        val page = args.getValue("page").lowercase()
        val action = SETTINGS_PAGES.entries.firstOrNull { page.contains(it.key) }?.value ?: Settings.ACTION_SETTINGS
        val name = page.removeSuffix(" settings").removeSuffix("settings").trim()
        launch(Intent(action), if (name.isEmpty()) "Opening settings" else "Opening $name settings")
    }

    val volume = SimpleSkill(
        "volume", "Change media volume: up, down, mute, max",
        listOf(slot("level", "up, down, mute, unmute or max", "Up or down?")),
        listOf("volume up", "mute the phone"),
    ) { args ->
        val am = android.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val level = args.getValue("level").lowercase()
        when {
            "unmute" in level -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            "mute" in level || "silent" in level -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            "max" in level || "full" in level -> am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
            "down" in level || "lower" in level || "decrease" in level -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0) }
            else -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0) }
        }
        ActionResult.Success("Done")
    }

    val battery = SimpleSkill(
        "battery_status", "Tell the battery level", emptyList(), listOf("how much battery is left"),
    ) {
        val bm = android.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ActionResult.Success("Battery is at $pct percent${if (bm.isCharging) " and charging" else ""}")
    }

    val dateTime = SimpleSkill(
        "date_time", "Tell the current time or date", emptyList(), listOf("what time is it", "what's the date today"),
    ) {
        ActionResult.Success("It's " + SimpleDateFormat("h:mm a, EEEE d MMMM", Locale("en", "IN")).format(Date()))
    }

    val all = listOf(
        callContact, sendSms, setAlarm, setTimer, flashlight, takePhoto, openSettings, volume, battery, dateTime,
    )


    private val SETTINGS_PAGES = linkedMapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "internet" to Settings.ACTION_WIRELESS_SETTINGS,
        "mobile data" to Settings.ACTION_DATA_ROAMING_SETTINGS, "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "brightness" to Settings.ACTION_DISPLAY_SETTINGS, "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "sound" to Settings.ACTION_SOUND_SETTINGS, "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "app" to Settings.ACTION_APPLICATION_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    )

    /** "06:30", "6:30 am", "7 pm", "19" -> (h, m). */
    internal fun parseTime(raw: String): Pair<Int, Int>? {
        val s = raw.lowercase()
        val m = Regex("(\\d{1,2})(?:[:. ](\\d{2}))?").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if ("pm" in s && h < 12) h += 12
        if ("am" in s && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private fun spokenTime(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12${if (m > 0) ":%02d".format(m) else ""} ${if (h < 12) "AM" else "PM"}"
    }

    private fun spokenDuration(secs: Int) = when {
        secs % 3600 == 0 -> "${secs / 3600} hour${if (secs >= 7200) "s" else ""}"
        secs % 60 == 0 -> "${secs / 60} minute${if (secs >= 120) "s" else ""}"
        else -> "$secs seconds"
    }

    /**
     * "600" (seconds, as the slot asks), or spoken units the model passes through: "10 minutes",
     * "1 hour 30 minutes", "90 sec". Null if there is no number.
     */
    internal fun durationSeconds(raw: String): Int? {
        val t = raw.lowercase()
        val parts = Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\b").findAll(t).toList()
        if (parts.isEmpty()) return t.filter { it.isDigit() }.toIntOrNull()
        return parts.sumOf { p ->
            val n = p.groupValues[1].toDouble()
            when (p.groupValues[2].first()) { 'h' -> n * 3600; 'm' -> n * 60; else -> n }.toInt()
        }.takeIf { it > 0 }
    }
}
