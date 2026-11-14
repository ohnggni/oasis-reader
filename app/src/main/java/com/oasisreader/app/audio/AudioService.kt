package com.oasisreader.app.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.oasisreader.app.MainActivity

/**
 * Audiobook playback in the background: Media3 keeps the notification / lock-screen player and
 * the headset buttons in step with [AudioPlayback]'s player, and the app alive while it plays.
 */
class AudioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = AudioPlayback.player ?: run { stopSelf(); return }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, GuardedPlayer(p)).setId("og-audiobook").setSessionActivity(open).build()
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recent apps while paused: stop; while playing: keep going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            AudioPlayback.save(); stopSelf()
        }
    }

    /**
     * The player as the outside world sees it. Right after the earphones went away (a Bluetooth
     * headset put in its case), some headsets and phones send "play" again, and the book went on
     * out loud. Those plays are ignored for a few seconds; ▶ inside the app uses the player itself.
     */
    private class GuardedPlayer(p: Player) : ForwardingPlayer(p) {
        override fun play() { if (!AudioPlayback.externalPlayBlocked()) super.play() }
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && AudioPlayback.externalPlayBlocked()) return
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun onDestroy() {
        session?.let { it.release() }   // the player itself belongs to AudioPlayback
        session = null
        super.onDestroy()
    }
}
