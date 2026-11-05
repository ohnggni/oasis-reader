package com.oasisreader.app.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.oasisreader.app.R
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Receives the audiobook tracks waiting in [AudioDownloads]' queue, one book after another,
 * as a foreground service with a progress notification ("모두 멈춤" pauses it). Network
 * trouble: WorkManager tries again later (needs a connection); a half track goes on from
 * where it stopped (HTTP Range, [MediaCrypt] continues the same file).
 */
class AudioDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** The server said no (not worth trying again by itself). */
    private class Refused(message: String) : Exception(message)
    /** The track was taken out of the queue while it came. */
    private class Unwanted : Exception()
    /** The book was paused while it came (the half track stays). */
    private class Paused : Exception()

    private var lastNote = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = info(applicationContext, "받을 준비 중…", null, -1)

    override suspend fun doWork(): Result {
        val app = applicationContext
        channel(app)
        runCatching { setForeground(getForegroundInfo()) }   // may be refused in the background: the work still runs
        while (true) {
            val bookId = AudioDownloads.pendingBooks(app).firstOrNull() ?: break
            try {
                book(app, bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Refused) {
                // stop asking for this book; the error stays to be seen (받기 관리, 서재 menu)
                fail(app, bookId, e.message ?: "받지 못했습니다")
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName)
                AudioDownloads.errors[bookId] = "연결 문제로 잠시 뒤 다시 받습니다 ($msg)"
                AudioDownloads.updateQueue(app, bookId) { it.copy(error = msg) }
                AudioDownloads.current.remove(bookId)
                return if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                    AudioDownloads.errors[bookId] = "받지 못했습니다: $msg"
                    Result.failure()
                }
            }
        }
        return Result.success()
    }

    private fun fail(app: Context, bookId: String, msg: String) {
        AudioDownloads.updateQueue(app, bookId) { AudioDownloads.Queue(error = msg) }
        AudioDownloads.errors[bookId] = msg
        AudioDownloads.progress.remove(bookId); AudioDownloads.current.remove(bookId)
        AudioDownloads.version++
    }

    private suspend fun book(app: Context, bookId: String) {
        val dao = AppDatabase.get(app).books()
        val row = dao.get(bookId) ?: run { AudioDownloads.writeQueue(app, bookId, AudioDownloads.Queue()); return }
        val (sid, aid) = AudioLibraryLink.parse(row) ?: throw Refused("오디오북 정보가 없습니다")
        val server = ServerSettings(app).get(sid) ?: throw Refused("이 오디오북의 서버 설정이 지워졌습니다")
        val web = BookOasisWeb.of(server)
        val d = AudioLibraryLink.detail(web, sid, aid, row.title, null)
        if (d.book.tracks.isEmpty()) throw Refused("트랙이 없습니다")
        AudioDownloads.writeManifest(app, bookId, d.book)
        web.ensureLogin()
        AudioDownloads.errors.remove(bookId)

        val scope = HashSet<Int>()   // the tracks this run is about (for the percentage)
        while (true) {
            val q = AudioDownloads.readQueue(app, bookId)
            if (q.paused) { AudioDownloads.progress.remove(bookId); AudioDownloads.current.remove(bookId); return }
            val all = AudioDownloads.ALL in q.wanted
            val kept = AudioDownloads.keptTracks(app, bookId)
            val wanted = d.book.tracks.filter { all || it.id in q.wanted }
            scope += wanted.map { it.id }
            val next = wanted.firstOrNull { it.id !in kept }
            if (next == null) {
                AudioDownloads.writeQueue(app, bookId, AudioDownloads.Queue())
                break
            }
            val index = d.book.tracks.indexOf(next)
            val doneInScope = scope.count { it in kept }
            fun report(frac: Float) {
                val overall = ((doneInScope + frac) / scope.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                AudioDownloads.progress[bookId] = overall
                AudioDownloads.current[bookId] = next.id to frac
                val now = System.currentTimeMillis()
                if (now - lastNote > 1000) {
                    lastNote = now
                    val text = "트랙 ${index + 1}/${d.book.tracks.size} · ${(overall * 100).toInt()}%"
                    runCatching { setForegroundAsync(info(app, row.title, text, (overall * 100).toInt())) }
                }
            }
            report(0f)
            try {
                track(app, web, d.book.id, bookId, next) { report(it) }
            } catch (e: Unwanted) {
                continue   // the queue changed: read it again
            } catch (e: Paused) {
                AudioDownloads.progress.remove(bookId); AudioDownloads.current.remove(bookId)
                return
            }
            if (!all) AudioDownloads.updateQueue(app, bookId) { it.copy(wanted = it.wanted - next.id) }
            AudioDownloads.refreshRow(app, bookId)
        }
        AudioDownloads.progress.remove(bookId); AudioDownloads.current.remove(bookId)
        AudioDownloads.refreshRow(app, bookId)
        if (scope.isNotEmpty() && scope.all { it in AudioDownloads.keptTracks(app, bookId) }) done(app, row.title, scope.size)
    }

    /** One track into <track>.part (going on from what is there), then renamed to <track>.ogr. */
    private suspend fun track(app: Context, web: BookOasisWeb, audiobookId: Int, bookId: String, t: AudioTrack, onFrac: (Float) -> Unit) {
        val part = AudioDownloads.partFile(app, bookId, t.id)
        val key = MediaCrypt.key(app)
        var offset = 0L
        var nonce: ByteArray? = null
        if (part.length() > MediaCrypt.HEADER) {
            val head = ByteArray(MediaCrypt.HEADER)
            RandomAccessFile(part, "r").use { it.readFully(head) }
            nonce = MediaCrypt.nonceOf(head)
            if (nonce != null) offset = part.length() - MediaCrypt.HEADER else part.delete()
        } else part.delete()

        val url = web.absolute("/api/media/audiobooks/$audiobookId/tracks/${t.id}/stream")
        var tries = 0
        while (true) {
            tries++
            val req = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            val call = web.client.newCall(req)
            val r = call.execute()
            r.use {
                when {
                    r.code == 401 && tries <= 2 -> { web.relogin(); return@use }
                    r.code == 416 && offset > 0 && tries <= 2 -> { part.delete(); offset = 0; nonce = null; return@use }
                    r.code in 500..599 || r.code == 408 || r.code == 429 -> throw IOException("서버 응답 ${r.code}")
                    !r.isSuccessful -> throw Refused("트랙을 받지 못했습니다 (${r.code})")
                }
                val body = r.body ?: throw IOException("빈 응답")
                val append = offset > 0 && r.code == 206 && nonce != null
                if (!append) { offset = 0; nonce = MediaCrypt.newNonce() }
                val len = body.contentLength().let { if (it > 0) it + offset else -1L }
                var got = offset
                var sinceCheck = 0L
                FileOutputStream(part, append).buffered(256 * 1024).use { out ->
                    body.byteStream().use { input ->
                        MediaCrypt.encrypt(key, nonce!!, input, out, position = offset) { n ->
                            got += n; sinceCheck += n
                            if (sinceCheck >= 512 * 1024) {
                                sinceCheck = 0
                                if (isStopped) throw CancellationException("stopped")
                                val q = AudioDownloads.readQueue(app, bookId)
                                if (AudioDownloads.ALL !in q.wanted && t.id !in q.wanted) throw Unwanted()
                                if (q.paused) throw Paused()
                                if (len > 0) onFrac((got.toFloat() / len).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (len > 0 && got < len) throw IOException("연결이 끊겼습니다")
                val target = AudioDownloads.trackFile(app, bookId, t.id)
                if (!part.renameTo(target)) throw IOException("파일을 저장하지 못했습니다")
                onFrac(1f)
                return
            }
        }
    }

    private fun done(app: Context, title: String, tracks: Int) {
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("⬇️ 받기 완료")
            .setContentText("$title · 트랙 ${tracks}개. 인터넷 없이 들을 수 있습니다")
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(DONE_ID + (title.hashCode() and 0xfff), n) }
    }

    companion object {
        const val CHANNEL = "audio_downloads"
        private const val NOTE_ID = 7301
        private const val DONE_ID = 7400
        private const val MAX_RETRIES = 30

        /** 받기 관리 › "와이파이에서만 받기". */
        fun wifiOnly(context: Context) = context.getSharedPreferences("audio", Context.MODE_PRIVATE).getBoolean("wifi_only", false)

        fun request(context: Context): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AudioDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        fun channel(app: Context) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "오디오북 받기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "오디오북을 폰에 받는 동안의 진행 상황"
                    setShowBadge(false)
                })
            }
        }

        private fun openApp(app: Context): PendingIntent? =
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun info(app: Context, title: String, text: String?, percent: Int): ForegroundInfo {
            channel(app)
            val cancel = PendingIntent.getBroadcast(
                app, 1, Intent(app, CancelReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("⬇️ $title")
                .setContentText(text ?: "오디오북 받는 중")
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(app))
                .addAction(0, "모두 멈춤", cancel)
                .build()
            return ForegroundInfo(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /** "모두 멈춤" in the notification: everything waiting is paused (half tracks stay; ▶️ goes on). */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AudioDownloads.pauseAll(context)
            androidx.work.WorkManager.getInstance(context.applicationContext).cancelUniqueWork(AudioDownloads.WORK)
        }
    }
}
