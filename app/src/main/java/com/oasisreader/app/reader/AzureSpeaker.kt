package com.oasisreader.app.reader

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * [ReadAloud.Speaker] for Azure 음성: each sentence is fetched as an MP3 ([AzureVoice.sentence],
 * the next two ahead of time) and played in turn; started / done are reported per sentence with
 * the queue number, as the phone's engine does. A sentence that cannot be fetched is skipped;
 * after [MAX_FAILS] in a row [onFail] is told (the reader goes back to the phone's voice).
 *
 * Audio focus, earphones-out and the sleep timer stay with [ReadAloud] / [ReadAloudService]: the
 * player here does not take focus itself.
 */
class AzureSpeaker(
    context: Context,
    private val started: (session: Int, index: Int) -> Unit,
    private val done: (session: Int, index: Int) -> Unit,
    private val onFail: (session: Int, index: Int, reason: String) -> Unit,
) : ReadAloud.Speaker {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val player: ExoPlayer = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ false,
        )
        .build()

    override fun speak(items: List<String>, from: Int, session: Int, rate: Float, pitch: Float) {
        stop()
        player.playbackParameters = PlaybackParameters(rate, pitch)
        val voice = AzureVoice.voice(app)
        val ahead = HashMap<Int, Deferred<File>>()
        fun fetch(k: Int): Deferred<File> = ahead.getOrPut(k) { scope.async(Dispatchers.IO) { AzureVoice.sentence(app, items[k], voice) } }
        job = scope.launch {
            var fails = 0
            for (k in from until items.size) {
                fetch(k)
                for (n in 1..AHEAD) if (k + n < items.size) fetch(k + n)
                val file = try {
                    ahead.getValue(k).await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fails++
                    ahead.remove(k)
                    // a wrong key or the month's quota will not get better by trying the next sentence
                    val status = (e as? AzureVoice.AzureError)?.status
                    if (fails >= MAX_FAILS || status == 401 || status == 403 || status == 429) {
                        onFail(session, k, AzureVoice.reason(e)); return@launch
                    }
                    done(session, k)
                    continue
                }
                fails = 0
                ahead.remove(k)
                started(session, k)
                play(file)
                done(session, k)
                if (k < items.lastIndex) delay(ReadAloud.gapAfter(items[k], rate))
            }
        }
    }

    /** Play one file to its end (or until cancelled). A file that will not play counts as heard. */
    private suspend fun play(f: File) = suspendCancellableCoroutine { cont ->
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.removeListener(this); if (cont.isActive) cont.resume(Unit) }
            }
            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this); if (cont.isActive) cont.resume(Unit)
            }
        }
        player.addListener(l)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare()
        player.play()
        cont.invokeOnCancellation { scope.launch { player.removeListener(l) } }
    }

    /** A sample in [voice] (the voice list's ▶). */
    fun preview(text: String, voice: String, rate: Float, pitch: Float, onError: (String) -> Unit) {
        stop()
        player.playbackParameters = PlaybackParameters(rate, pitch)
        job = scope.launch {
            val f = try { AzureVoice.sentence(app, text, voice) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { onError(AzureVoice.reason(e)); return@launch }
            play(f)
        }
    }

    override fun stop() {
        job?.cancel(); job = null
        player.stop()
        player.clearMediaItems()
    }

    override fun shutdown() {
        stop()
        player.release()
        scope.cancel()
    }

    companion object {
        const val AHEAD = 2
        const val MAX_FAILS = 3
    }
}
