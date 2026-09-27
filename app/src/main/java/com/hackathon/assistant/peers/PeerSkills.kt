package com.hackathon.assistant.peers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import java.time.LocalDate
import java.time.ZoneId

/** Agent tools for talking to the same app on another phone (see [PeerLink]). */
class PeerSkills(
    private val link: PeerLink,
    /** Hides/shows our floating button so it isn't in the screenshot. */
    private val hideButton: (Boolean) -> Unit = {},
) {

    private abstract class PeerSkill(
        override val id: String,
        override val description: String,
        override val slots: List<SlotSpec>,
    ) : Skill {
        override val examples = emptyList<String>()
    }

    private fun slot(name: String, desc: String, required: Boolean = true) = SlotSpec(name, desc, required, question = "")

    val all: List<Skill> = listOf(
        object : PeerSkill(
            "pair_device", "Connect to another person's phone (e.g. \"connect to Vishnu's phone\"), which runs this same assistant. " +
                "This is THE way to connect phones: never use Bluetooth or Wi-Fi settings for it",
            listOf(slot("device", "whose phone, e.g. Bansi")),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val device = args.getValue("device")
                if (link.connectedNames().any { it.contains(device, true) }) return ActionResult.Success("Already connected to $device")
                val (name, code) = link.pair(device)
                    ?: return ActionResult.Failure("No phone called $device found nearby. Is the assistant open on it?")
                // Always ask about the code here (the model tends to skip it); the model reads the answer.
                val spoken = code.toCharArray().joinToString(" ")
                val answer = ctx.voice.ask("The pairing code is $spoken. Is the same code showing on $name's phone?")
                return ActionResult.Success(
                    "",
                    observation = "Pairing with $name, code $code. The user was asked if $name's phone shows the same code " +
                        "and said: \"${answer ?: "(nothing)"}\". Now call pair_reply with accept=yes if they confirmed, else no.",
                )
            }
        },
        object : PeerSkill(
            "pair_reply", "Accept or reject the pending phone pairing after the user checked the code",
            listOf(slot("accept", "yes if the user confirmed the code matches, else no")),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val yes = args["accept"].orEmpty().lowercase().startsWith("y") || args["accept"] == "true"
                if (!yes) { link.reply(false); return ActionResult.Success("Okay, not connecting") }
                return if (link.reply(true)) ActionResult.Success("Connected. The phones trust each other now")
                else ActionResult.Failure("The other phone didn't accept, or the connection timed out")
            }
        },
        object : PeerSkill(
            "share_photos", "Send photos taken between two dates (e.g. a trip) to another person's phone (connect it with pair_device first)",
            listOf(
                slot("device", "whose phone, e.g. Bansi"),
                slot("from_date", "first day, YYYY-MM-DD"),
                slot("to_date", "last day, YYYY-MM-DD"),
            ),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val from = runCatching { LocalDate.parse(args.getValue("from_date").trim()) }.getOrNull()
                val to = runCatching { LocalDate.parse(args.getValue("to_date").trim()) }.getOrNull() ?: from
                if (from == null || to == null) return ActionResult.Failure("Dates must be YYYY-MM-DD")
                val photos = photosBetween(ctx.android, from, to)
                if (photos.isEmpty()) return ActionResult.Failure("No photos taken between $from and $to")
                val sent = link.sendPhotos(args.getValue("device"), photos)
                return if (sent < 0) ActionResult.Failure("Not connected to ${args["device"]}. Use pair_device first.")
                else ActionResult.Success("Sending $sent photos to ${args["device"]}")
            }
        },
        object : PeerSkill(
            "send_screenshot", "Take a screenshot of the current screen and send it to another person's phone (e.g. \"take a screenshot and send it to Rakshit\")",
            listOf(slot("device", "whose phone, e.g. Rakshit")),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val service = com.hackathon.assistant.perception.AssistantAccessibilityService.instance
                    ?: return ActionResult.Failure("Screen control is off")
                hideButton(true)
                kotlinx.coroutines.delay(300)   // let the button disappear from the frame
                val bitmap = service.screenshot()
                hideButton(false)
                bitmap ?: return ActionResult.Failure("Couldn't take the screenshot")
                val uri = saveScreenshot(ctx.android, bitmap) ?: return ActionResult.Failure("Couldn't save the screenshot")
                val sent = link.sendPhotos(args.getValue("device"), listOf(uri))
                return if (sent < 0) ActionResult.Failure("Screenshot saved, but not connected to ${args["device"]}. Use pair_device first.")
                else ActionResult.Success("Screenshot sent to ${args["device"]}")
            }
        },
        object : PeerSkill(
            "share_latest", "Send your most recent screenshot or photos to another person's phone (e.g. \"send my screenshot to Rakshit\")",
            listOf(
                slot("device", "whose phone, e.g. Rakshit"),
                slot("what", "screenshot or photo", required = false),
                slot("count", "how many, default 1", required = false),
            ),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val screenshots = args["what"].orEmpty().contains("screen", ignoreCase = true) || args["what"].isNullOrBlank()
                val count = args["count"]?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(1, MAX_PHOTOS) ?: 1
                val latest = latestImages(ctx.android, screenshots, count)
                val kind = if (screenshots) "screenshot" else "photo"
                if (latest.isEmpty()) return ActionResult.Failure("No ${kind}s found on this phone")
                val sent = link.sendPhotos(args.getValue("device"), latest)
                return if (sent < 0) ActionResult.Failure("Not connected to ${args["device"]}. Use pair_device first.")
                else ActionResult.Success("Sent your latest ${if (sent == 1) kind else "$sent ${kind}s"} to ${args["device"]}")
            }
        },
        object : PeerSkill(
            "ask_agent", "Ask the assistant on another person's connected phone to do or answer something (e.g. what's on their calendar); returns its reply",
            listOf(slot("device", "whose phone"), slot("message", "the request, e.g. what's on your calendar today")),
        ) {
            override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
                val reply = link.ask(args.getValue("device"), args.getValue("message"))
                    ?: return ActionResult.Failure("No reply from ${args["device"]}'s phone. Is it connected?")
                return ActionResult.Success("${args["device"]}'s phone says: $reply")
            }
        },
    )

    /** Saves like a normal screenshot (Pictures/Screenshots), so it's in the gallery too. */
    private fun saveScreenshot(context: Context, bitmap: android.graphics.Bitmap): Uri? = runCatching {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_assistant_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        uri
    }.getOrNull()

    /** Newest images first: screenshots (by folder or file name) or camera photos. */
    private fun latestImages(context: Context, screenshots: Boolean, count: Int): List<Uri> {
        val path = MediaStore.Images.Media.RELATIVE_PATH
        val name = MediaStore.Images.Media.DISPLAY_NAME
        val where = if (screenshots) "($path LIKE '%Screenshot%' OR $name LIKE 'Screenshot%')"
        else "($path LIKE '%DCIM%' AND $path NOT LIKE '%Screenshot%')"
        val out = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            where, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> while (c.moveToNext() && out.size < count) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        return out
    }

    private fun photosBetween(context: Context, from: LocalDate, to: LocalDate): List<Uri> {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            // Screenshots/downloads often have no "date taken": fall back to when the file was added.
            // Numbers are inlined: bound args are text, and an expression (unlike a column) won't convert them.
            "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_ADDED} * 1000) >= $start AND " +
                "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_ADDED} * 1000) < $end",
            null, "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c -> while (c.moveToNext() && out.size < MAX_PHOTOS) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        return out
    }

    private companion object { const val MAX_PHOTOS = 30 }
}
