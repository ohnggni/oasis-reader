package com.oasisreader.app.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One audiobook (or music album) as the player needs it. */
@kotlinx.serialization.Serializable
data class AudioBook(
    val id: Int,
    val title: String,
    val author: String?,
    val coverUrl: String?,          // absolute
    val tracks: List<AudioTrack>,
    val startTrackId: Int? = null,  // listening position kept by the server
    val startSeconds: Double = 0.0,
    val serverId: String = "",      // the catalog it came from (서재 row: ab_<server>_<id>)
    val libraryId: String? = null,
    val artworkFile: String? = null,   // the 서재 cover on the phone (notification / lock screen, offline too)
    // 소장 오디오북 (TODO 30): files from a 🗄️ 파일 서버 or a phone folder, not bookoasis — no server place
    val own: Boolean = false,
    val shelfId: String = "",          // the 서재 row (소장: "ao_…"; bookoasis: ab_<server>_<id>)
    val chapters: List<AudioChapter> = emptyList(),   // 장 표시: inside the tracks (m4b, metadata.json)
)

/** The place in the whole book: [doneSec] of [totalSec]; [exact] is false while some track lengths are guessed. */
data class BookPlace(val doneSec: Double, val totalSec: Double, val exact: Boolean)

/**
 * Where [track] / [posSec] is in the whole book (TODO 34). [durations]: the tracks' lengths (0 = not
 * known yet — 소장 tracks are learnt as they play), [curDurSec]: the player's length of the track
 * playing. Unknown lengths count as the average of the known ones; null when nothing is known.
 */
fun bookPlace(durations: List<Double>, track: Int, posSec: Double, curDurSec: Double): BookPlace? {
    if (track !in durations.indices) return null
    val d = durations.mapIndexed { i, v -> if (i == track && curDurSec > 0) curDurSec else v }
    val known = d.filter { it > 0 }
    if (known.isEmpty()) return null
    val avg = known.average()
    val filled = d.map { if (it > 0) it else avg }
    val total = filled.sum()
    val done = filled.take(track).sum() + posSec.coerceIn(0.0, filled[track])
    return BookPlace(done.coerceAtMost(total), total, known.size == d.size)
}

private const val NO_SOUND = "소리 트랙을 재생하지 못했습니다"

/** The 서재 row of [this] book. */
fun AudioBook.rowId(): String = shelfId.ifEmpty { if (serverId.isEmpty()) "" else AudioLibraryLink.bookId(serverId, id) }

/**
 * [localPath]: the track kept on the phone (오프라인), played instead of the stream.
 * [uri]: where a 소장 track plays from otherwise (remote://… 파일 서버, content://… 폰 폴더).
 */
@kotlinx.serialization.Serializable
data class AudioTrack(val id: Int, val title: String, val durationSec: Double, val localPath: String? = null, val uri: String? = null)

/** One 장 (chapter): it starts in track [track] (index) at [startSec]. */
@kotlinx.serialization.Serializable
data class AudioChapter(val track: Int, val startSec: Double, val title: String)

/**
 * The one audiobook player of the app: an ExoPlayer streaming the tracks from bookoasis with the
 * web session, shown by [AudioService] (notification, lock screen, headset buttons). The place
 * goes back to bookoasis every 15 s, on pause and on track change, so the web player follows.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioPlayback {
    var player: ExoPlayer? = null
        private set
    var web: BookOasisWeb? = null
        private set
    var book by mutableStateOf<AudioBook?>(null)
        private set
    var playing by mutableStateOf(false)

    /** When playback last stopped because the earphones went away (0 = not since the last play). */
    @Volatile var noisyAt = 0L
    private const val NOISY_GUARD_MS = 10_000L

    /** A play from outside the app (headset, notification, lock screen) within 10 s of that is ignored. */
    fun externalPlayBlocked(): Boolean =
        noisyAt != 0L && android.os.SystemClock.elapsedRealtime() - noisyAt < NOISY_GUARD_MS
        private set
    var trackIndex by mutableIntStateOf(0)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf<String?>(null)
    /** Sleep timer: pause at this time (ms) or at the end of the track. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepAtTrackEnd by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var ticker: Job? = null
    private var lastSaved = 0L
    // listening time for the 독서 통계 (real seconds, whatever the speed), written with each save
    private var listenedMs = 0L
    private var lastTickAt = 0L

    private fun ensurePlayer(context: Context, w: BookOasisWeb?): ExoPlayer {
        val existing = player
        // 소장 오디오북 need no bookoasis session: any player plays them
        if (existing != null && (w == null || web === w)) return existing
        existing?.release()
        val app = context.applicationContext
        speed = app.getSharedPreferences("audio", Context.MODE_PRIVATE).getFloat("speed", 1f)
        val p = ExoPlayer.Builder(app)
            // files on the phone (오프라인, encrypted) and the server's stream with the web session
            // PlainHeaderDataSource: m4a files with left-over protection headers (TODO 40)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PlainHeaderDataSource.Factory(
                MediaDataSourceFactory(app, androidx.media3.datasource.DefaultDataSource.Factory(app, OkHttpDataSource.Factory(w?.client ?: com.oasisreader.app.data.Http.client))),
            )))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)          // earphones out: pause
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        p.setPlaybackSpeed(speed)
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // earphones out / Bluetooth gone: remember when, so a "play" sent right after by the
                // headset or the phone does not start it again on the speaker (see AudioService)
                noisyAt = if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                    android.os.SystemClock.elapsedRealtime() else if (playWhenReady) 0L else noisyAt
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
                tick()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sleepAtTrackEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    sleepAtTrackEnd = false; p.pause()
                }
                tick()
                // only a real move to another track is a place to keep: when the list is set or
                // cleared (opening a book, ⏹️) the player is at "track 1, 0 s" for a moment, and
                // saving that wiped the listening place (TODO 5)
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) save()
            }
            override fun onPlaybackStateChanged(state: Int) {
                tick()
                if (state == Player.STATE_ENDED) save(completed = true)
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "재생할 수 없습니다: " + (e.cause?.message ?: e.errorCodeName)
            }
            // A sound track the player cannot use (a codec the phone lacks, protected content) is
            // skipped without an error: the time runs on in silence. Say so instead.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSelected }) {
                    val f = audio.first().getTrackFormat(0)
                    error = NO_SOUND + " (" + listOfNotNull(f.sampleMimeType, f.codecs).joinToString(" ") + ")"
                } else if (error?.startsWith(NO_SOUND) == true) error = null
            }
        })
        player = p; if (w != null) web = w
        return p
    }

    /** Opens [b] at the server's place (or at [trackIndex]/[startSec] when given) and plays. */
    /** Two places to choose from (see AudioProgressSync.decide): shown by [PlaceChoiceDialog]. */
    data class PlaceChoice(val title: String, val serverIdx: Int, val serverSec: Double, val phoneIdx: Int, val phoneSec: Double)
    var placeChoice by mutableStateOf<PlaceChoice?>(null)

    /** The listener picked a place: go there and play. */
    fun choosePlace(useServer: Boolean) {
        val c = placeChoice ?: return
        placeChoice = null
        val p = player ?: return
        if (useServer) p.seekTo(c.serverIdx, (c.serverSec * 1000).toLong().coerceAtLeast(0))
        else p.seekTo(c.phoneIdx, (c.phoneSec * 1000).toLong().coerceAtLeast(0))
        p.play()
    }

    /**
     * Opens [b] at the phone's place, paused, and asks which place to go on from (the server's
     * place looks like a player that started over elsewhere).
     */
    fun openAsking(context: Context, w: BookOasisWeb?, b: AudioBook, phoneIdx: Int, phoneSec: Double) {
        val serverIdx = b.tracks.indexOfFirst { it.id == b.startTrackId }
        open(context, w, b, phoneIdx, phoneSec, autoPlay = false)
        placeChoice = PlaceChoice(b.title, serverIdx, b.startSeconds, phoneIdx, phoneSec)
    }

    fun open(context: Context, w: BookOasisWeb?, b0: AudioBook, startIndex: Int? = null, startSec: Double? = null, autoPlay: Boolean = true) {
        var play = autoPlay
        val b = AudioDownloads.withLocal(context, b0)
        val p = ensurePlayer(context, w)
        app = context.applicationContext
        error = null
        val same = book?.id == b.id && p.mediaItemCount == b.tracks.size
        book = b
        if (!same) {
            p.setMediaItems(b.tracks.mapIndexed { i, t ->
                MediaItem.Builder()
                    .setMediaId("${b.id}:${t.id}")
                    .setUri(
                        t.localPath?.let { Uri.fromFile(java.io.File(it)) } ?: t.uri?.let { Uri.parse(it) }
                            ?: w?.let { Uri.parse(it.absolute("/api/media/audiobooks/${b.id}/tracks/${t.id}/stream")) } ?: Uri.EMPTY,
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.title.ifBlank { "${i + 1}" })
                            .setArtist(b.title)
                            .setAlbumTitle(b.title)
                            .setAlbumArtist(b.author)
                            .setTrackNumber(i + 1).setTotalTrackCount(b.tracks.size)
                            .apply {
                                val art = b.artworkFile?.let { Uri.fromFile(java.io.File(it)) } ?: b.coverUrl?.let { Uri.parse(it) }
                                art?.let { setArtworkUri(it) }
                            }
                            .build(),
                    )
                    .build()
            })
            val idx = startIndex ?: b.tracks.indexOfFirst { it.id == b.startTrackId }.takeIf { it >= 0 } ?: 0
            val sec = startSec ?: if (startIndex == null && b.tracks.getOrNull(idx)?.id == b.startTrackId) b.startSeconds else 0.0
            p.seekTo(idx, (sec * 1000).toLong().coerceAtLeast(0))
            p.prepare()
        } else if (startIndex != null) {
            p.seekTo(startIndex, ((startSec ?: 0.0) * 1000).toLong())
        } else if (!p.isPlaying && !b.own) {
            // the same book is still loaded (paused): go to the server's place when it was saved
            // elsewhere after ours (the web or another phone) — AudioProgressSync.decide
            val idx = b.tracks.indexOfFirst { it.id == b.startTrackId }
            val here = p.currentMediaItemIndex
            val hereSec = p.currentPosition / 1000.0
            val c = context.applicationContext
            when (if (idx >= 0) AudioProgressSync.decide(c, b.serverId, b.id, b.startTrackId, idx, b.startSeconds, here, hereSec) else AudioProgressSync.Place.PHONE) {
                AudioProgressSync.Place.SERVER -> p.seekTo(idx, (b.startSeconds * 1000).toLong().coerceAtLeast(0))
                AudioProgressSync.Place.ASK -> { play = false; placeChoice = PlaceChoice(b.title, idx, b.startSeconds, here, hereSec) }
                AudioProgressSync.Place.PHONE -> {}
            }
        }
        if (play) p.play()
        context.startService(Intent(context, AudioService::class.java))
        tick()
        // listening puts the book on the 서재 (like 바로 읽기 does for books)
        if (b.serverId.isNotEmpty() && !b.own && w != null) scope.launch {
            runCatching { AudioLibraryLink.add(context.applicationContext, w, b, 0.0, false) }
        }
    }

    fun toggle() { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); it.play() } } }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)); tick() }
    fun seekBy(ms: Long) { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }; tick() }
    fun track(i: Int) { player?.let { if (i in 0 until it.mediaItemCount) { it.seekTo(i, 0); it.play() } }; tick() }
    fun next() {
        val p = player ?: return
        val cs = book?.chapters.orEmpty()
        if (cs.isNotEmpty()) { val i = chapterIndex(); if (i + 1 < cs.size) goChapter(i + 1); return }
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }
    fun previous() {
        val p = player ?: return
        val cs = book?.chapters.orEmpty()
        if (cs.isNotEmpty()) {
            val i = chapterIndex()
            val c = cs.getOrNull(i)
            val into = if (c != null && c.track == p.currentMediaItemIndex) p.currentPosition / 1000.0 - c.startSec else 99.0
            goChapter(if (into > 5 || i <= 0) i.coerceAtLeast(0) else i - 1)
            return
        }
        if (p.currentPosition > 5_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    // ── 장 (chapters inside the tracks: m4b, metadata.json) ──
    /** The chapter at [track] / [posSec] (-1 before the first). */
    fun chapterAt(b: AudioBook, track: Int, posSec: Double): Int =
        b.chapters.indexOfLast { it.track < track || (it.track == track && it.startSec <= posSec + 0.5) }
    fun chapterIndex(): Int { val b = book ?: return -1; return chapterAt(b, trackIndex, positionMs / 1000.0) }
    /** Jump to a place in the book (a 책갈피) and play. */
    fun goTo(track: Int, sec: Double) {
        player?.let { if (track in 0 until it.mediaItemCount) { it.seekTo(track, (sec * 1000).toLong().coerceAtLeast(0)); it.play() } }
        tick()
    }
    fun goChapter(i: Int) {
        val c = book?.chapters?.getOrNull(i) ?: return
        player?.let { it.seekTo(c.track, (c.startSec * 1000).toLong().coerceAtLeast(0)); it.play() }
        tick()
    }

    fun changeSpeed(context: Context, s: Float) {
        speed = s
        player?.setPlaybackSpeed(s)
        context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().putFloat("speed", s).apply()
    }

    private val sleepRunnable = Runnable { sleepAt = 0L; player?.pause() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun sleepIn(minutes: Int) {
        main.removeCallbacks(sleepRunnable); sleepAtTrackEnd = false
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (minutes > 0) main.postDelayed(sleepRunnable, minutes * 60_000L)
    }
    fun sleepAtEndOfTrack() { main.removeCallbacks(sleepRunnable); sleepAt = 0L; sleepAtTrackEnd = true }

    /** Ends listening: saves the place, stops the notification. */
    fun close(context: Context) {
        save()
        // forget the book before the player is emptied: nothing may be saved from an empty player
        book = null; playing = false
        main.removeCallbacks(sleepRunnable); sleepAt = 0L; sleepAtTrackEnd = false
        player?.stop()
        player?.clearMediaItems()
        context.stopService(Intent(context, AudioService::class.java))
    }

    /** UI state from the player, a few times a second while playing. */
    private fun tick() {
        val p = player ?: return
        trackIndex = p.currentMediaItemIndex
        positionMs = p.currentPosition
        durationMs = p.duration.takeIf { it > 0 } ?: ((book?.tracks?.getOrNull(trackIndex)?.durationSec ?: 0.0) * 1000).toLong()
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val pl = player ?: break
                trackIndex = pl.currentMediaItemIndex
                positionMs = pl.currentPosition
                if (pl.duration > 0) durationMs = pl.duration
                val now = android.os.SystemClock.elapsedRealtime()
                if (pl.isPlaying && lastTickAt > 0) listenedMs += (now - lastTickAt).coerceIn(0, 2_000)
                lastTickAt = now
                if (pl.isPlaying && System.currentTimeMillis() - lastSaved > 15_000) save()
                if (!pl.isPlaying) { lastTickAt = 0; break }
                delay(500)
            }
        }
    }

    /** 소장 오디오북: the place on the 서재 row only (no server), the listening time, a track length learnt. */
    private fun saveOwn(p: ExoPlayer, b: AudioBook, completed: Boolean) {
        if (p.mediaItemCount != b.tracks.size || p.playbackState == Player.STATE_IDLE) return
        lastSaved = System.currentTimeMillis()
        val idx = p.currentMediaItemIndex
        val sec = p.currentPosition / 1000.0
        val dur = p.duration.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0
        val ctx = app ?: return
        val heard = listenedMs / 1000
        listenedMs -= heard * 1000
        scope.launch {
            runCatching { AudioLibraryLink.savePlace(ctx, b, idx, sec, completed, dur) }
            if (heard > 0) runCatching {
                val dao = com.oasisreader.app.data.db.AppDatabase.get(ctx).readingTime()
                val day = java.time.LocalDate.now().toString()
                val id = b.rowId()
                val cur = dao.get(day, id)?.seconds ?: 0L
                dao.upsert(com.oasisreader.app.data.db.ReadingTimeEntity(day, id, cur + heard))
            }
            if (dur > 0 && (b.tracks.getOrNull(idx)?.durationSec ?: 1.0) <= 0) runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { OwnAudio.learnDuration(ctx, b.rowId(), idx, dur) } }
        }
    }

    /** The listening place back to bookoasis (the web player continues from it). */
    fun save(completed: Boolean = false) {
        val p = player ?: return
        val b = book ?: return
        if (b.own) { saveOwn(p, b, completed); return }
        val w = web ?: return
        // an empty or not yet loaded player has no place to keep
        if (p.mediaItemCount != b.tracks.size || p.playbackState == Player.STATE_IDLE) return
        val t = b.tracks.getOrNull(p.currentMediaItemIndex) ?: return
        lastSaved = System.currentTimeMillis()
        val sec = p.currentPosition / 1000.0
        val total = b.tracks.sumOf { it.durationSec }
        val before = b.tracks.take(p.currentMediaItemIndex).sumOf { it.durationSec }
        val pct = if (total > 0) ((before + sec) / total * 100).coerceIn(0.0, 100.0) else 0.0
        val idx = p.currentMediaItemIndex
        val ctx = app
        val heard = listenedMs / 1000
        listenedMs -= heard * 1000
        scope.launch {
            ctx?.let { c -> runCatching { AudioLibraryLink.savePlace(c, b, idx, sec, completed) } }
            if (heard > 0 && b.serverId.isNotEmpty()) ctx?.let { c ->
                runCatching {
                    val dao = com.oasisreader.app.data.db.AppDatabase.get(c).readingTime()
                    val day = java.time.LocalDate.now().toString()
                    val id = b.rowId()
                    val cur = dao.get(day, id)?.seconds ?: 0L
                    dao.upsert(com.oasisreader.app.data.db.ReadingTimeEntity(day, id, cur + heard))
                }
            }
            val rate = speed.toDouble()
            runCatching {
                w.post("/api/media/audiobooks/${b.id}/progress", buildJsonObject {
                    put("current_track_id", t.id)
                    put("current_time", sec)
                    put("playback_rate", rate)
                    put("total_progress_pct", if (completed) 100.0 else pct)
                    if (completed) put("is_completed", 1)
                })
            }.onSuccess {
                ctx?.let { c ->
                    AudioProgressSync.markSent(c, b.serverId, b.id, t.id, sec)
                    AudioProgressSync.sent(c, b.serverId, b.id); AudioProgressSync.schedule(c)
                }
            }.onFailure {
                // offline (a book on the phone) or the server was away: send it later
                if (b.serverId.isNotEmpty()) ctx?.let { c ->
                    AudioProgressSync.keep(c, AudioProgressSync.Pending(
                        b.serverId, b.id, b.title, t.id, idx, sec, rate, pct, completed, System.currentTimeMillis(),
                    ))
                }
            }
        }
    }
}
