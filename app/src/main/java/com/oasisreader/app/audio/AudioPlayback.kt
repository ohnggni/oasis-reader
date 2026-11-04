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
)

/** [localPath]: the track kept on the phone (오프라인), played instead of the stream. */
@kotlinx.serialization.Serializable
data class AudioTrack(val id: Int, val title: String, val durationSec: Double, val localPath: String? = null)

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

    private fun ensurePlayer(context: Context, w: BookOasisWeb): ExoPlayer {
        val existing = player
        if (existing != null && web === w) return existing
        existing?.release()
        val app = context.applicationContext
        speed = app.getSharedPreferences("audio", Context.MODE_PRIVATE).getFloat("speed", 1f)
        val p = ExoPlayer.Builder(app)
            // files on the phone (오프라인) and the server's stream with the web session
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(app, OkHttpDataSource.Factory(w.client))))
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
                tick()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sleepAtTrackEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    sleepAtTrackEnd = false; p.pause()
                }
                tick(); save()
            }
            override fun onPlaybackStateChanged(state: Int) {
                tick()
                if (state == Player.STATE_ENDED) save(completed = true)
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "재생할 수 없습니다: " + (e.cause?.message ?: e.errorCodeName)
            }
        })
        player = p; web = w
        return p
    }

    /** Opens [b] at the server's place (or at [trackIndex]/[startSec] when given) and plays. */
    fun open(context: Context, w: BookOasisWeb, b0: AudioBook, startIndex: Int? = null, startSec: Double? = null) {
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
                    .setUri(t.localPath?.let { Uri.fromFile(java.io.File(it)) } ?: Uri.parse(w.absolute("/api/media/audiobooks/${b.id}/tracks/${t.id}/stream")))
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
        }
        p.play()
        context.startService(Intent(context, AudioService::class.java))
        tick()
        // listening puts the book on the 서재 (like 바로 읽기 does for books)
        if (b.serverId.isNotEmpty()) scope.launch {
            runCatching { AudioLibraryLink.add(context.applicationContext, w, b, 0.0, false) }
        }
    }

    fun toggle() { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); it.play() } } }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)); tick() }
    fun seekBy(ms: Long) { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }; tick() }
    fun track(i: Int) { player?.let { if (i in 0 until it.mediaItemCount) { it.seekTo(i, 0); it.play() } }; tick() }
    fun next() = player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = player?.let { if (it.currentPosition > 5_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }

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
        main.removeCallbacks(sleepRunnable); sleepAt = 0L; sleepAtTrackEnd = false
        player?.stop()
        player?.clearMediaItems()
        book = null; playing = false
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
                if (pl.isPlaying && System.currentTimeMillis() - lastSaved > 15_000) save()
                if (!pl.isPlaying) break
                delay(500)
            }
        }
    }

    /** The listening place back to bookoasis (the web player continues from it). */
    fun save(completed: Boolean = false) {
        val p = player ?: return
        val b = book ?: return
        val w = web ?: return
        val t = b.tracks.getOrNull(p.currentMediaItemIndex) ?: return
        lastSaved = System.currentTimeMillis()
        val sec = p.currentPosition / 1000.0
        val total = b.tracks.sumOf { it.durationSec }
        val before = b.tracks.take(p.currentMediaItemIndex).sumOf { it.durationSec }
        val pct = if (total > 0) ((before + sec) / total * 100).coerceIn(0.0, 100.0) else 0.0
        val idx = p.currentMediaItemIndex
        val ctx = app
        scope.launch {
            ctx?.let { c -> runCatching { AudioLibraryLink.savePlace(c, b, idx, sec, completed) } }
            runCatching {
                w.post("/api/media/audiobooks/${b.id}/progress", buildJsonObject {
                    put("current_track_id", t.id)
                    put("current_time", sec)
                    put("playback_rate", speed.toDouble())
                    put("total_progress_pct", if (completed) 100.0 else pct)
                    if (completed) put("is_completed", 1)
                })
            }
        }
    }
}
