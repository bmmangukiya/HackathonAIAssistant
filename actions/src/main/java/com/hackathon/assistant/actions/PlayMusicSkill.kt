package com.hackathon.assistant.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.UiAction
import kotlinx.coroutines.delay

/**
 * Plays a song / artist / genre, fully programmatically (no model steps).
 *
 * YouTube: open the search results, pick the first REAL video row (skipping ads, the channel card,
 * Shorts, "Install" cards), tap it with a real touch, and succeed only when the full **watch page**
 * is open (like/dislike, comments, progress "N minutes of M"). Audio alone is NOT success: YouTube's
 * results feed auto-plays an inline preview (often an ad), which makes the music stream active while
 * no video has been opened. Verified on the test phone.
 *
 * Spotify: only when the user names it; a logged-out Spotify accepts the intent and plays nothing,
 * so it must also be confirmed by sustained audio.
 */
class PlayMusicSkill : Skill {
    override val id = "play_music"
    override val description = "Start playing a NEW song, artist, playlist or genre (YouTube, or Spotify if named)"
    override val slots = listOf(
        SlotSpec("query", "what to play; use \"popular songs\" if the user didn't say", required = false, question = ""),
        SlotSpec("app", "spotify or youtube, only if the user named one", required = false, question = ""),
    )
    override val examples = listOf("play some song", "play arijit singh", "play lofi on youtube")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val query = args["query"]?.trim()?.takeIf { it.isNotBlank() && it.lowercase() !in GENERIC } ?: "popular songs"
        val wanted = args["app"]?.lowercase().orEmpty()
        val pm = ctx.android.packageManager
        fun installed(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        val audio = ctx.android.getSystemService(AudioManager::class.java)
        val playing = { audio?.isMusicActive == true }
        if (playing()) { mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE); delay(600) }

        if ("spotify" in wanted) {
            // The user named Spotify: never substitute another app.
            if (!installed(SPOTIFY)) return ActionResult.Failure("Spotify is not installed on this phone. TERMINAL")
            return if (playOnSpotify(ctx, query, playing)) {
                ActionResult.Success("Playing $query on Spotify", openedPackage = SPOTIFY, doneWhen = playing)
            } else {
                ActionResult.Failure("I opened Spotify but couldn't start $query. Is Spotify logged in? TERMINAL")
            }
        }
        if (!installed(YOUTUBE)) return ActionResult.Failure("No music app (YouTube or Spotify) is installed. TERMINAL")

        repeat(2) { attempt ->
            // Attempt 1: play-from-search (lands on results, sometimes straight on the watch page).
            // Attempt 2: the results URL. Both end on the results feed; we open a video ourselves.
            val intent = if (attempt == 0) playFromSearch(YOUTUBE, query)
            else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.android.startActivity(intent) }.isFailure) return@repeat
            if (openVideo(ctx, query)) {
                ensurePlaying(audio, playing)
                return ActionResult.Success("Playing $query on YouTube", openedPackage = YOUTUBE, doneWhen = playing)
            }
        }
        return ActionResult.Failure("I opened YouTube but couldn't start a video for $query")
    }

    /**
     * Waits for YouTube to show results (or a watch page), then taps the best video row with a real
     * touch and waits for the watch page. Tries up to 3 rows and one scroll.
     */
    private suspend fun openVideo(ctx: SkillContext, query: String): Boolean {
        var state: ScreenState? = null
        for (i in 0 until 40) { // up to ~12 s for a cold start
            state = ctx.screen.capture()
            if (state != null && state.packageName == YOUTUBE) {
                if (onWatchPage(state)) return true
                if (candidates(state, query, emptySet()).isNotEmpty()) break
            }
            delay(300)
        }
        state ?: return false
        val tried = mutableSetOf<String>()
        repeat(2) { pass ->
            val now = ctx.screen.capture() ?: return false
            if (onWatchPage(now)) return true
            for (row in candidates(now, query, tried).take(3)) {
                tried += row.label
                Log.i(TAG, "tapping video: ${row.label}")
                val mark = ctx.screen.mark()
                val current = ctx.screen.capture() ?: now
                val target = current.elements.firstOrNull { it.label == row.label } ?: continue
                // Real touch: YouTube rows often ignore the accessibility click.
                ctx.ui.perform(UiAction.Tap(target.id, touch = true), current)
                ctx.screen.awaitSettled(mark, timeoutMs = 4_000)
                if (waitForWatchPage(ctx)) return true
                // Opened a channel/playlist page instead: go back to the results and try the next row.
                val after = ctx.screen.capture()
                if (after != null && candidates(after, query, tried).isEmpty()) {
                    val m = ctx.screen.mark(); ctx.ui.perform(UiAction.Back, after); ctx.screen.awaitSettled(m, timeoutMs = 2_000)
                }
            }
            if (pass == 0) {
                val s = ctx.screen.capture() ?: return false
                val m = ctx.screen.mark()
                ctx.ui.perform(UiAction.Scroll(null, UiAction.Direction.DOWN), s)
                ctx.screen.awaitSettled(m, timeoutMs = 2_000)
            }
        }
        return false
    }

    /**
     * Spotify, programmatically: open `spotify:search:<query>`, clear upsell pop-ups ("DISMISS"),
     * open the best result for the kind the user asked for (album / playlist / artist / song), then
     * tap the page's "Play · …" button. Success = music stream active and staying active.
     * Tapping a song row starts it directly, so that case needs no Play button.
     */
    private suspend fun playOnSpotify(ctx: SkillContext, query: String, playing: () -> Boolean): Boolean {
        val kind = when {
            "album" in query.lowercase() -> "album"
            "playlist" in query.lowercase() -> "playlist"
            "artist" in query.lowercase() -> "artist"
            else -> "song"
        }
        val search = query.replace(Regex("\\b(album|playlist|songs?|artist)\\b", RegexOption.IGNORE_CASE), "").trim().ifBlank { query }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(search)))
            .setPackage(SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.android.startActivity(open) }.isFailure) return false
        val tried = mutableSetOf<String>()
        repeat(30) { // ~20 s budget
            if (playing() && sustained(playing)) return true
            val s = ctx.screen.capture()
            if (s == null || s.packageName != SPOTIFY) { delay(500); return@repeat }
            fun tap(e: com.hackathon.assistant.core.UiElement, why: String) {
                Log.i(TAG, "spotify: $why \"${e.label}\"")
            }
            val blocker = s.elements.firstOrNull { e -> e.clickable && BLOCKERS.any { e.label.equals(it, ignoreCase = true) } }
            val playBtn = s.elements.firstOrNull { e -> e.clickable && (e.label.startsWith("Play ·") || e.label.equals("Play", true) || e.label.startsWith("Play ", true) && "playlist" !in e.label.lowercase()) }
            val row = if (playBtn == null) spotifyRow(s, kind, tried) else null
            val target = blocker ?: playBtn ?: row ?: run { delay(500); return@repeat }
            tap(target, if (target === blocker) "dismissing" else if (target === playBtn) "pressing" else "opening")
            if (target === row) tried += target.label
            val mark = ctx.screen.mark()
            ctx.ui.perform(UiAction.Tap(target.id, touch = true), s)
            ctx.screen.awaitSettled(mark, timeoutMs = 3_000)
            if (target === playBtn || (target === row && kind == "song")) {
                if (waitFor(playing, 6_000) && sustained(playing)) return true
            }
        }
        return playing()
    }

    /** Best search-result row for [kind]: "Saiyaara, Album • …", "…, Playlist", "…, Song • …", "…, Artist". */
    private fun spotifyRow(s: ScreenState, kind: String, tried: Set<String>) = s.elements
        .filter { e -> e.clickable && e.label !in tried && !e.label.startsWith("Add ") && !e.label.startsWith("More options") && e.label.contains(", ") }
        .firstOrNull { e ->
            val l = e.label.lowercase()
            when (kind) {
                "album" -> ", album" in l
                "playlist" -> ", playlist" in l
                "artist" -> ", artist" in l
                else -> ", song" in l
            }
        }

    private suspend fun waitForWatchPage(ctx: SkillContext): Boolean {
        repeat(20) { ctx.screen.capture()?.let { if (onWatchPage(it)) return true }; delay(300) }
        return false
    }

    /** The full player: like/dislike switches, a comments button, or the playback-position label. */
    private fun onWatchPage(s: ScreenState): Boolean = s.packageName == YOUTUBE && s.elements.any { e ->
        val l = e.label.lowercase()
        "like this video" in l || "dislike this video" in l || PROGRESS.containsMatchIn(l) || l.startsWith("comments")
    }

    /**
     * Video rows on a results page, best first. A row with a duration ("- 3 minutes") or views is a
     * video; everything else long enough is a fallback. Chrome, ads, channel cards, Shorts, install
     * cards and the search suggestion equal to the query are skipped.
     */
    private fun candidates(s: ScreenState, query: String, tried: Set<String>) =
        s.elements.filter { e ->
            e.clickable && !e.inOverlay && e.label.length >= 18 && e.label !in tried &&
                !e.label.equals(query, ignoreCase = true) &&
                // A video row's description ends in "- Go to channel": that's not a channel card.
                e.label.replace("go to channel", "", ignoreCase = true).let { l -> SKIP.none { l.contains(it, ignoreCase = true) } }
        }.sortedBy { e -> if (VIDEO_ROW.containsMatchIn(e.label.lowercase())) 0 else 1 }

    private suspend fun ensurePlaying(audio: AudioManager?, playing: () -> Boolean) {
        if (waitFor(playing, 4_000)) return
        Log.i(TAG, "watch page open but silent; sending play")
        mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun playFromSearch(pkg: String, query: String) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        .setPackage(pkg)
        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun mediaKey(audio: AudioManager?, code: Int) {
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private suspend fun waitFor(cond: () -> Boolean, ms: Long): Boolean {
        var t = 0L
        while (t < ms) { if (cond()) return true; delay(250); t += 250 }
        return cond()
    }

    /** Still true 1.5 s later: filters the blip a rejected/logged-out player produces. */
    private suspend fun sustained(cond: () -> Boolean): Boolean { delay(1_500); return cond() }

    override fun isAvailable(context: Context): Boolean = true

    private companion object {
        const val TAG = "PlayMusic"
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        val PROGRESS = Regex("\\d+ (minute|second|hour)s? .*of \\d+")
        val VIDEO_ROW = Regex(" - \\d+ (minute|second|hour)|\\bviews\\b|official (music )?video|\\blyric")
        /** Rows on a YouTube results page that are not a playable video. */
        val SKIP = listOf(
            "sponsored", "install", "stars", "channel", "subscribe", "shorts", "youtube music", "navigate up",
            "voice search", "more options", "action menu", "subscriptions", "search youtube", "filters",
            "since you searched", "people also", "tap to mute",
        )
        /** Pop-ups Spotify puts over results (Premium upsell etc.): tap to get them out of the way. */
        val BLOCKERS = listOf("DISMISS", "Not now", "No thanks", "Maybe later", "Close", "Skip")
        val GENERIC = setOf("song", "songs", "some song", "some songs", "a song", "music", "some music", "something", "anything", "popular songs")
    }
}
