package com.oasisreader.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.oasisreader.app.R
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 서재에 담기 for books, comics and PDFs, like the audiobooks' (AudioDownloads): a queue kept in a
 * file (filesDir/book_queue.json), received one after another by [Worker] in the background with a
 * notification; a broken download goes on from where it stopped (HTTP Range, files in
 * cacheDir/dl/<key>.part). Each one can be paused (⏸️ — the part stays), resumed (▶️) or
 * cancelled (✖️ — the part goes). 바로 읽기 (open now, temporary) does not queue: see [Downloads].
 */
object BookQueue {
    enum class State { WAITING, PAUSED, FAILED }

    @Serializable
    data class Job(
        val key: String,                 // OPDS entry id, or the meta catalog's key
        val serverId: String,
        val href: String,
        val title: String,
        val meta: Boolean = false,       // from the meta catalog (clean title given)
        val collectionId: String? = null,
        val state: State = State.WAITING,
        val error: String? = null,
        val addedAt: Long = System.currentTimeMillis(),
        val origin: String? = null,      // BookOrigin JSON (DB v8)
        val fails: Int = 0,              // tries in a row that got nowhere (TODO 70)
        val temp: Boolean = false,       // 바로 읽기 waiting for Wi-Fi (103): kept 임시, like a direct one
        val shelvesOf: String? = null,   // 다음 권 (103): joins the 컬렉션 this book is on
        val mobile: Boolean = false,     // 이번만 모바일 데이터로 (103): this one does not wait for Wi-Fi
        val quiet: Boolean = false,      // 다음 회차 미리 받기 waiting for Wi-Fi (103): no card, no message
    )

    private const val WORK = "book-download"
    private const val MIXED = "mixed"
    /** Tries in a row without a single new byte before a book is given up as ⚠️ (▶️ tries again). */
    const val MAX_FAILS = 8
    private const val CHANNEL = "book_downloads"
    private const val NOTE_ID = 7601
    private val lock = Any()
    private val ser = ListSerializer(Job.serializer())

    /** Bumped when the queue changes, for screens. */
    var version by mutableIntStateOf(0)
        private set

    private fun file(c: Context) = File(c.applicationContext.filesDir, "book_queue.json")
    fun partFile(c: Context, key: String) = File(File(c.applicationContext.cacheDir, "dl").apply { mkdirs() }, key.hashCode().toUInt().toString(16) + ".part")

    fun all(c: Context): List<Job> = synchronized(lock) {
        runCatching { Http.json.decodeFromString(ser, file(c).readText()) }.getOrDefault(emptyList())
    }

    private fun write(c: Context, list: List<Job>) = synchronized(lock) {
        if (list.isEmpty()) file(c).delete() else file(c).writeTextAtomic(Http.json.encodeToString(ser, list))
        version++
    }

    private fun change(c: Context, f: (List<Job>) -> List<Job>) = synchronized(lock) { write(c, f(all(c))) }

    fun get(c: Context, key: String) = all(c).firstOrNull { it.key == key }

    fun enqueue(c: Context, job: Job) {
        change(c) { list ->
            // a 임시 request does not turn a waiting 서재에 담기 of the same book into a 임시 one
            val kept = list.any { it.key == job.key && !it.temp }
            list.filterNot { it.key == job.key } + job.copy(temp = job.temp && !kept)
        }
        Downloads.setProgress(job.key, -1f)
        if (!job.quiet) BatteryExempt.noteQueued(c)   // a background one asks nothing
        kick(c)
    }

    fun pause(c: Context, key: String) {
        change(c) { list -> list.map { if (it.key == key) it.copy(state = State.PAUSED) else it } }
        Downloads.setProgress(key, null)
    }

    /** ▶️ Go on; [mobile]: over mobile data this time. Also "지금 받기" for a waiting one. */
    fun resume(c: Context, key: String, mobile: Boolean = false) {
        change(c) { list -> list.map { if (it.key == key) it.copy(state = State.WAITING, error = null, fails = 0, mobile = mobile, quiet = false) else it } }
        if (get(c, key) == null) return
        Downloads.setProgress(key, -1f)
        kick(c)
    }

    /** Only the latest 미리 받기 waits: the earlier ones leave the queue. */
    fun dropQuiet(c: Context, except: String) = cancelMany(c, all(c).filter { it.quiet && it.key != except }.map { it.key })

    /**
     * The one to receive now: waiting, not tried this round and not [held] back for Wi-Fi —
     * what the person asked for before a 미리 받기.
     */
    internal fun next(jobs: List<Job>, tried: Set<String>, held: (Job) -> Boolean): Job? {
        val ready = jobs.filter { it.state == State.WAITING && it.key !in tried && !held(it) }
        return ready.firstOrNull { !it.quiet } ?: ready.firstOrNull()
    }

    fun cancel(c: Context, key: String) {
        change(c) { list -> list.filterNot { it.key == key } }
        partFile(c, key).delete()
        Downloads.setProgress(key, null)
    }

    /** 선택·일괄 삭제 (TODO 71): the queue entries and what was received of them. */
    fun cancelMany(c: Context, keys: Collection<String>) {
        val set = keys.toHashSet()
        if (set.isEmpty()) return
        change(c) { list -> list.filterNot { it.key in set } }
        set.forEach { partFile(c, it).delete(); Downloads.setProgress(it, null) }
    }

    fun pauseAll(c: Context) {
        change(c) { list -> list.map { if (it.state == State.WAITING) it.copy(state = State.PAUSED) else it } }
        Downloads.clearProgress()
    }

    /**
     * Starts the receiving. A worker that is only waiting (for the network, or in its retry back-off)
     * is replaced at once, so a new ▶️ never sits behind a back-off of minutes; one that is running
     * is left alone and picks the new book up in its loop.
     */
    fun kick(c: Context, policy: ExistingWorkPolicy? = null, forWifi: Boolean = false) {
        // the same "와이파이에서만 받기" switch as the audiobooks (받기 관리). With a 이번만 one waiting
        // (103) the work starts on any connection, and holds the others back itself ("mixed")
        val wifiOnly = DownloadPrefs.wifiOnly(c)
        val mixed = !forWifi && wifiOnly && all(c).any { it.state == State.WAITING && it.mobile }
        val net = if (wifiOnly && !mixed) NetworkType.UNMETERED else NetworkType.CONNECTED
        val wm = WorkManager.getInstance(c.applicationContext)
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setInputData(androidx.work.Data.Builder().putBoolean(MIXED, mixed).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        if (policy != null) { wm.enqueueUniqueWork(WORK, policy, request); return }
        val infos = wm.getWorkInfosForUniqueWork(WORK)
        infos.addListener({
            val running = runCatching { infos.get().any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
            wm.enqueueUniqueWork(WORK, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }, Runnable::run)
    }

    /** The Wi-Fi rule changed: start the waiting work again under it. */
    fun rekick(c: Context) { if (all(c).any { it.state == State.WAITING }) kick(c, ExistingWorkPolicy.REPLACE) }

    /** At start: anything still waiting from before goes on. */
    fun resumeWaiting(c: Context) { if (all(c).any { it.state == State.WAITING }) kick(c) }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        private var lastNote = 0L

        override suspend fun getForegroundInfo(): ForegroundInfo = info("받을 준비 중…", 0, 0)

        /** This run is on any connection for a 이번만 one: the rest wait while it is not Wi-Fi. */
        private val mixed get() = inputData.getBoolean(MIXED, false)
        private fun held(app: Context, job: Job) = mixed && !job.mobile && DownloadPrefs.mustWait(app)

        override suspend fun doWork(): Result {
            val app = applicationContext
            channel(app)
            runCatching { setForeground(getForegroundInfo()) }
            var retry = false
            val tried = HashSet<String>()   // a book that failed this round waits for the next one; the others go on
            while (!isStopped) {
                val job = next(all(app), tried) { held(app, it) } ?: break
                val left = all(app).count { it.state == State.WAITING && !held(app, it) }
                val part = partFile(app, job.key)
                val before = if (part.isFile) part.length() else 0L
                try {
                    one(app, job, left)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    // the server said no: keep it as failed (▶️ tries again)
                    if (get(app, job.key)?.quiet == true) cancel(app, job.key)   // 미리 받기: opening it tries again
                    else {
                        change(app) { l -> l.map { if (it.key == job.key) it.copy(state = State.FAILED, error = e.message) else it } }
                        Downloads.setProgress(job.key, null)
                        Downloads.message("받기 실패: ${job.title} — ${e.message}")
                    }
                } catch (e: Exception) {
                    // network trouble: from where it stopped, later. New bytes = still getting somewhere.
                    val grew = (if (part.isFile) part.length() else 0L) > before
                    val fails = if (grew) 1 else job.fails + 1
                    val dead = fails >= MAX_FAILS
                    val why = e.message ?: e.javaClass.simpleName
                    change(app) { l -> l.map { if (it.key == job.key) it.copy(error = why, fails = fails, state = if (dead) State.FAILED else State.WAITING) else it } }
                    tried += job.key
                    if (dead && get(app, job.key)?.quiet == true) {
                        cancel(app, job.key)
                    } else if (dead) {
                        Downloads.setProgress(job.key, null)
                        Downloads.message("받기 실패: ${job.title} — $why")
                    } else {
                        Downloads.setProgress(job.key, null)
                        retry = true
                    }
                } finally {
                    Downloads.received()
                }
            }
            if (retry && !isStopped) {
                if (runAttemptCount < 20) return Result.retry()
                // not for ever: what is still waiting becomes ⚠️ where the person can see it
                change(app) { l -> l.map { if (it.state == State.WAITING && it.error != null) it.copy(state = State.FAILED) else it } }
            }
            // what was held back goes on once there is Wi-Fi
            if (mixed && !isStopped && all(app).any { it.state == State.WAITING }) kick(app, ExistingWorkPolicy.APPEND_OR_REPLACE, forWifi = true)
            return Result.success()
        }

        private suspend fun one(app: Context, job: Job, left: Int) {
            // a 미리 받기 whose book arrived another way meanwhile
            if (job.quiet) AppDatabase.get(app).books().findBySource(job.key, job.href)?.let {
                if (BookStore.get(app).hasFiles(it.id)) { cancel(app, job.key); return }
            }
            val server = ServerSettings(app).get(job.serverId) ?: throw HttpException(0, "서버 설정이 지워졌습니다")
            val part = partFile(app, job.key)
            var lastFrac = -1f
            val progress = { done: Long, total: Long ->
                val f = if (total > 0) done.toFloat() / total else -1f
                if (f < 0 || f - lastFrac >= 0.01f) { lastFrac = f; Downloads.setProgress(job.key, f); if (!job.quiet) Downloads.nowReceiving(job.title, f, left) }
                note(job.title, left, f)
            }
            // paused or cancelled meanwhile — or Wi-Fi gone while this one must wait for it?
            var checkedAt = 0L
            var wait = false
            val stop = {
                val now = System.currentTimeMillis()
                val current = get(app, job.key)
                if (mixed && current != null && now - checkedAt >= 1000) { checkedAt = now; wait = held(app, current) }
                isStopped || wait || current?.state != State.WAITING
            }
            val (fileName, complete) = if (server.isRemote) {
                // a file server (WebDAV · SFTP · SMB · FTP): href is remote://<server>/<path>
                val path = RemoteAddress.parseUrl(job.href)?.second ?: throw HttpException(0, "주소가 잘못되었습니다")
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.oasisreader.app.remote.Remote.open(app, server).use { it.download(path, part, progress, stop) }
                }
                RemoteAddress.nameOf(path) to ok
            } else OpdsClient(server).downloadResume(job.href, part, progress, stop)
            if (!complete) { Downloads.setProgress(job.key, if (wait) -1f else null); return }   // -1: still waiting
            val generic = !job.meta && OpdsClient.isGenericTitle(job.title)
            val hint = if (generic) fileName?.substringBeforeLast('.')?.ifBlank { null } else job.title
            val store = BookStore.get(app)
            var book = store.import(part, sourceUrl = job.href, opdsId = job.key, titleHint = hint, fallbackTitle = job.title, pinned = !job.temp, moveSource = true)
            part.delete()
            val db = AppDatabase.get(app)
            if (job.meta && (book.format == BookStore.FORMAT_COMIC || book.format == BookStore.FORMAT_PDF) && book.title != job.title) {
                db.books().rename(book.id, job.title); book = book.copy(title = job.title)
            }
            job.collectionId?.let { com.oasisreader.app.data.ShelfCollections.set(db, it, book.id, true) }
            Downloads.recordOrigin(app, book.id, job.origin)
            job.shelvesOf?.let { NextVolumes.joinShelves(app, it, book.id) }
            val quiet = get(app, job.key)?.quiet ?: job.quiet
            change(app) { l -> l.filterNot { it.key == job.key } }
            Downloads.setProgress(job.key, null)
            if (!quiet) ShelfNotices.added((if (job.temp) "받았습니다: " else "서재에 담았습니다: ") + book.title, book.id)
            if (job.temp) runCatching { store.trimCache(CachePrefs(app).limitBytes, keep = book.id) }
        }

        private fun note(title: String, left: Int, f: Float) {
            val now = System.currentTimeMillis()
            if (now - lastNote < 1000) return
            lastNote = now
            runCatching { setForegroundAsync(info(title + (if (left > 1) " · 남은 ${left}권" else ""), (f * 100).toInt(), 100)) }
        }

        private fun info(text: String, done: Int, total: Int): ForegroundInfo {
            val app = applicationContext
            channel(app)
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("⬇️ 책 받는 중")
                .setContentText(text)
                .setProgress(total, done.coerceAtLeast(0), total == 0 || done < 0)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    android.app.PendingIntent.getActivity(app, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                })
                .addAction(0, "모두 멈춤", android.app.PendingIntent.getBroadcast(
                    app, 2, android.content.Intent(app, PauseReceiver::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                .build()
            return ForegroundInfo(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /** "모두 멈춤" in the notification: every waiting book is paused (parts stay). */
    class PauseReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            pauseAll(context)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        }
    }

    private fun channel(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "책 받기", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }
}
