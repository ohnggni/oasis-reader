package com.oasisreader.app.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.oasisreader.app.MainActivity
import com.oasisreader.app.R

/**
 * Keeps 소리 내 읽기 going with the screen off: a foreground service (media playback) with the
 * player in the notification shade and a media session, so headset / Bluetooth buttons and the
 * lock screen control it. The reading itself stays in the reader; the service only relays.
 */
class ReadAloudService : Service() {

    /** What the reader shows to the service (set while a reader is reading aloud). */
    interface Player {
        val playing: Boolean
        fun toggle()
        fun skip(d: Int)
        fun close()
    }

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "소리 내 읽기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "책을 소리 내 읽는 동안의 재생 컨트롤"
                    setShowBadge(false)
                },
            )
        }
        session = MediaSession(this, "OGReaderReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (player?.playing == false) player?.toggle() }
                override fun onPause() { if (player?.playing == true) player?.toggle() }
                override fun onSkipToNext() { player?.skip(1) }
                override fun onSkipToPrevious() { player?.skip(-1) }
                override fun onStop() { player?.close() }
            })
            isActive = true
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> player?.toggle()
            ACT_NEXT -> player?.skip(1)
            ACT_PREV -> player?.skip(-1)
            ACT_CLOSE -> { player?.close(); return START_NOT_STICKY }
        }
        if (player == null) { stopSelf(); return START_NOT_STICKY }
        refresh()
        return START_NOT_STICKY
    }

    /** Notification + media session from the current state. */
    fun refresh() {
        val p = player ?: return
        val s = session ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(if (p.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val n = build(p.playing, s)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTE_ID, n)
    }

    private fun action(act: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ReadAloudService::class.java).setAction(act),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun build(playing: Boolean, s: MediaSession): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun act(icon: Int, label: String, pi: PendingIntent) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, pi).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(action(ACT_CLOSE, 4))
            .addAction(act(R.drawable.ic_ra_prev, "이전 문장", action(ACT_PREV, 1)))
            .addAction(if (playing) act(R.drawable.ic_ra_pause, "일시정지", action(ACT_TOGGLE, 2)) else act(R.drawable.ic_ra_play, "계속 읽기", action(ACT_TOGGLE, 2)))
            .addAction(act(R.drawable.ic_ra_next, "다음 문장", action(ACT_NEXT, 3)))
            .addAction(act(R.drawable.ic_ra_close, "끝내기", action(ACT_CLOSE, 4)))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        session?.release(); session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTE_ID = 7301
        private const val ACT_TOGGLE = "toggle"
        private const val ACT_NEXT = "next"
        private const val ACT_PREV = "prev"
        private const val ACT_CLOSE = "close"

        @Volatile var player: Player? = null
            private set
        private var instance: ReadAloudService? = null
        private var title = ""
        private var subtitle = ""

        /** Start (or update) the notification for [p]. Call from the reader, in front. */
        fun show(context: Context, p: Player, bookTitle: String, chapter: String) {
            player = p; title = bookTitle; subtitle = chapter
            val svc = instance
            if (svc != null) svc.refresh()
            else runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
        }

        /** State changed (play / pause / chapter): redraw if running. */
        fun update(bookTitle: String? = null, chapter: String? = null) {
            bookTitle?.let { title = it }; chapter?.let { subtitle = it }
            instance?.refresh()
        }

        fun hide(context: Context) {
            player = null
            instance?.let {
                it.stopForeground(STOP_FOREGROUND_REMOVE)
                it.stopSelf()
            } ?: runCatching { context.stopService(Intent(context, ReadAloudService::class.java)) }
        }
    }
}
