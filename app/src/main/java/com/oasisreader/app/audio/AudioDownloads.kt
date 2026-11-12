package com.oasisreader.app.audio

import com.oasisreader.app.data.writeTextAtomic
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 오프라인 오디오북: the tracks of a 서재 audiobook kept on the phone, encrypted ([MediaCrypt]).
 *
 *   filesDir/media/<id>/audiobook.json   the track list (from bookoasis)
 *   filesDir/media/<id>/queue.json       tracks still wanted, and the last error
 *   filesDir/media/<id>/<track>.ogr      a kept track; <track>.part while it comes (resumed by Range)
 *
 * Receiving runs in [AudioDownloadWorker] (WorkManager, a foreground service with a notification),
 * so it goes on with the app closed and after a reboot or a lost connection. The files are the
 * truth: a track is kept when its .ogr is there, wanted while it is in queue.json.
 * filesDir/media/ stays out of every backup and phone move (the key does not move either).
 * A 서재 row is pinned = true once every track is kept (☁️ streamed otherwise, like 바로 읽기).
 * Before 0.1.98 the tracks were plain files in books/<id>/audio/ — [migrateLegacy] moves them.
 */
object AudioDownloads {
    private const val LEGACY_DIR = "audio"
    private const val MANIFEST = "audiobook.json"
    private const val QUEUE = "queue.json"
    const val WORK = "audio-download"

    /** Whole-book progress (0..1) by 서재 book id, while it is being received. */
    val progress = mutableStateMapOf<String, Float>()
    /** What is being received now: book id → (track id, 0..1 of that track). */
    val current = mutableStateMapOf<String, Pair<Int, Float>>()
    /** The last failure by book id (shown until it is tried again). */
    val errors = mutableStateMapOf<String, String>()
    /** Bumped whenever the files change, so screens can read them again. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Serializable
    internal data class Queue(val wanted: List<Int> = emptyList(), val since: Long = 0, val error: String? = null, val paused: Boolean = false)

    fun root(context: Context) = File(context.applicationContext.filesDir, "media")
    internal fun dir(context: Context, bookId: String) = File(root(context), bookId)
    private fun legacyDir(context: Context, bookId: String) = File(BookStore.get(context.applicationContext).dir(bookId), LEGACY_DIR)
    private fun manifest(context: Context, bookId: String) = File(dir(context, bookId), MANIFEST)
    internal fun trackFile(context: Context, bookId: String, trackId: Int) = File(dir(context, bookId), "$trackId.${MediaCrypt.EXT}")
    internal fun partFile(context: Context, bookId: String, trackId: Int) = File(dir(context, bookId), "$trackId.part")

    // ---- the track list ----

    internal fun writeManifest(context: Context, bookId: String, b: AudioBook) {
        dir(context, bookId).mkdirs()
        manifest(context, bookId).writeTextAtomic(Http.json.encodeToString(AudioBook.serializer(), b.copy(tracks = b.tracks.map { it.copy(localPath = null) })))
    }

    private fun readManifest(context: Context, bookId: String): AudioBook? =
        runCatching { Http.json.decodeFromString(AudioBook.serializer(), manifest(context, bookId).readText()) }.getOrNull()
            ?: runCatching { Http.json.decodeFromString(AudioBook.serializer(), File(legacyDir(context, bookId), MANIFEST).readText()) }.getOrNull()

    /** The track list as last seen from the server (null before the first request). */
    fun trackList(context: Context, bookId: String): List<AudioTrack>? = readManifest(context, bookId)?.tracks

    /** 서재 book ids that have something on the phone or in the queue. */
    fun bookIds(context: Context): Set<String> = root(context).listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

        /** Track ids kept on the phone. */
    fun keptTracks(context: Context, bookId: String): Set<Int> =
        dir(context, bookId).listFiles()?.mapNotNull { f ->
            if (f.name.endsWith("." + MediaCrypt.EXT) && f.length() > MediaCrypt.HEADER) f.name.substringBefore('.').toIntOrNull() else null
        }?.toSet().orEmpty()

    /** Is the whole audiobook on the phone? */
    fun isKept(context: Context, bookId: String): Boolean {
        val b = readManifest(context, bookId) ?: return false
        if (File(legacyDir(context, bookId), MANIFEST).isFile) return true
        val kept = keptTracks(context, bookId)
        return b.tracks.isNotEmpty() && b.tracks.all { it.id in kept }
    }

    /** The book as far as it is on the phone (kept tracks point at their files), or null when none is. */
    fun local(context: Context, bookId: String): AudioBook? {
        val b = readManifest(context, bookId) ?: return null
        val legacy = File(legacyDir(context, bookId), MANIFEST).isFile
        val kept = keptTracks(context, bookId)
        val tracks = b.tracks.map { t ->
            when {
                legacy -> t.takeIf { it.localPath != null && File(it.localPath).isFile } ?: t.copy(localPath = null)
                t.id in kept -> t.copy(localPath = trackFile(context, bookId, t.id).path)
                else -> t.copy(localPath = null)
            }
        }
        return if (tracks.any { it.localPath != null }) b.copy(tracks = tracks) else null
    }

    /** [b] with the phone's files and cover where there are some (stream otherwise). */
    fun withLocal(context: Context, b: AudioBook): AudioBook {
        val id = b.rowId()
        if (id.isEmpty()) return b
        val cover = BookStore.get(context.applicationContext).coverFile(id).takeIf { it.isFile }?.path
        val kept = local(context, id)?.tracks?.associateBy { it.id }.orEmpty()
        return b.copy(
            artworkFile = cover ?: b.artworkFile,
            tracks = b.tracks.map { t -> kept[t.id]?.localPath?.let { p -> t.copy(localPath = p) } ?: t },
        )
    }

    // ---- the queue ----

    internal fun readQueue(context: Context, bookId: String): Queue = synchronized(lock) {
        runCatching { Http.json.decodeFromString(Queue.serializer(), File(dir(context, bookId), QUEUE).readText()) }.getOrNull() ?: Queue()
    }

    internal fun writeQueue(context: Context, bookId: String, q: Queue) = synchronized(lock) {
        val f = File(dir(context, bookId), QUEUE)
        if (q.wanted.isEmpty() && q.error == null) f.delete()
        else f.writeTextAtomic(Http.json.encodeToString(Queue.serializer(), q))
    }

    internal fun updateQueue(context: Context, bookId: String, change: (Queue) -> Queue) = synchronized(lock) {
        writeQueue(context, bookId, change(readQueue(context, bookId)))
    }

    /** Books with tracks still wanted, oldest request first. */
    internal fun pendingBooks(context: Context): List<String> =
        root(context).listFiles()?.filter { File(it, QUEUE).isFile }
            ?.map { it.name to readQueue(context, it.name) }
            ?.filter { it.second.wanted.isNotEmpty() && !it.second.paused }
            ?.sortedBy { it.second.since }?.map { it.first }.orEmpty()

    /** Tracks of [bookId] waiting to be received. */
    fun wantedTracks(context: Context, bookId: String): Set<Int> = readQueue(context, bookId).wanted.toSet()

    /**
     * Receive [trackIds] of a 서재 audiobook (null = all its tracks). The track list comes from
     * the server in the worker; the work goes on with the app closed (a notification shows it).
     */
    fun enqueue(context: Context, row: BookEntity, trackIds: Collection<Int>? = null, book: AudioBook? = null) {
        val app = context.applicationContext
        scope.launch {
            dir(app, row.id).mkdirs()
            // the track list right away (the worker refreshes it), so 받기 관리 can show the tracks
            if (book != null) writeManifest(app, row.id, book)
            updateQueue(app, row.id) { q ->
                val add = trackIds ?: listOf(ALL)
                Queue(wanted = (q.wanted + add).distinct(), since = if (q.wanted.isEmpty()) System.currentTimeMillis() else q.since, error = null, paused = false)
            }
            errors.remove(row.id)
            if (progress[row.id] == null) progress[row.id] = 0f
            version++
            kick(app)
        }
    }

    /** Start (or queue behind the running one) the worker. */
    internal fun kick(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, AudioDownloadWorker.request(context))
    }

    /** Wi-Fi only on or off: the waiting work starts again under the new rule (half tracks resume). */
    fun setWifiOnly(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().putBoolean("wifi_only", on).apply()
        scope.launch {
            if (pendingBooks(app).isNotEmpty()) {
                current.clear()
                WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, AudioDownloadWorker.request(app))
            }
            version++
        }
    }

    /** Stop receiving [bookId] (what is already kept stays; a half track resumes next time). */
    /** ✖️ Stop receiving [bookId]: out of the queue, half tracks deleted (kept tracks stay). */
    fun cancel(context: Context, bookId: String) {
        val app = context.applicationContext
        scope.launch {
            updateQueue(app, bookId) { Queue() }
            dir(app, bookId).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            progress.remove(bookId); current.remove(bookId); errors.remove(bookId)
            refreshRow(app, bookId)
        }
    }

    /** ⏸️ Pause [bookId]: it stays in the queue, a half track stays for later. */
    fun pause(context: Context, bookId: String) {
        val app = context.applicationContext
        scope.launch {
            updateQueue(app, bookId) { it.copy(paused = true) }
            current.remove(bookId)
            version++
        }
    }

    /** ▶️ Go on with [bookId] (also tries a failed one again). */
    fun resume(context: Context, bookId: String) {
        val app = context.applicationContext
        scope.launch {
            updateQueue(app, bookId) { it.copy(paused = false, error = null) }
            errors.remove(bookId)
            if (progress[bookId] == null) progress[bookId] = 0f
            version++
            kick(app)
        }
    }

    fun isPaused(context: Context, bookId: String) = readQueue(context, bookId).let { it.paused && it.wanted.isNotEmpty() }

    /** Every audiobook waiting in the queue: paused. */
    fun pauseAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            root(app).listFiles()?.forEach { d -> if (readQueue(app, d.name).wanted.isNotEmpty()) updateQueue(app, d.name) { it.copy(paused = true) } }
            current.clear()
            version++
        }
    }

    /** Take one waiting track out of the queue (a half part stays for later). */
    fun dequeueTrack(context: Context, bookId: String, trackId: Int) {
        val app = context.applicationContext
        scope.launch {
            updateQueue(app, bookId) { q ->
                val list = if (ALL in q.wanted) trackList(app, bookId)?.map { it.id }.orEmpty() + (q.wanted - ALL) else q.wanted
                val left = list.distinct() - trackId - keptTracks(app, bookId)
                if (left.isEmpty()) Queue() else q.copy(wanted = left)
            }
            if (readQueue(app, bookId).wanted.isEmpty()) { progress.remove(bookId); current.remove(bookId) }
            version++
        }
    }

        /** Stop everything that is being received or waits. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        scope.launch {
            root(app).listFiles()?.forEach { d -> writeQueue(app, d.name, Queue()) }
            progress.clear(); current.clear(); errors.clear()
            version++
        }
    }

    /** One kept track off the phone (the 서재 row goes back to ☁️ when the book is no longer whole). */
    suspend fun removeTrack(context: Context, bookId: String, trackId: Int) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        updateQueue(app, bookId) { q -> q.copy(wanted = q.wanted - trackId) }
        trackFile(app, bookId, trackId).delete()
        partFile(app, bookId, trackId).delete()
        refreshRow(app, bookId)
    }

    /** Back to streaming: the phone's copy goes, the place and the 서재 entry stay. */
    suspend fun remove(context: Context, row: BookEntity) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        updateQueue(app, row.id) { Queue() }
        progress.remove(row.id); current.remove(row.id); errors.remove(row.id)
        // the worker notices the queue is empty and lets go of the files
        dir(app, row.id).listFiles()?.filter { it.name != MANIFEST }?.forEach { it.delete() }
        legacyDir(app, row.id).deleteRecursively()
        refreshRow(app, row.id)
    }

    /** Bytes kept on the phone for [bookId]. */
    fun keptBytes(context: Context, bookId: String): Long =
        (dir(context, bookId).listFiles()?.filter { it.name.endsWith("." + MediaCrypt.EXT) }?.sumOf { it.length() } ?: 0L) +
            (legacyDir(context, bookId).listFiles()?.sumOf { it.length() } ?: 0L)

    /** The 서재 row after the files changed: pinned when whole, and the bytes on the phone. */
    internal suspend fun refreshRow(app: Context, bookId: String) {
        val dao = AppDatabase.get(app).books()
        dao.get(bookId)?.let { dao.insert(it.copy(pinned = isKept(app, bookId), fileBytes = keptBytes(app, bookId))) }
        version++
    }

    /** queue.json: "every track" before the track list is known. */
    internal const val ALL = -1

    /**
     * Plain tracks of 0.1.95–0.1.96 (books/<id>/audio/<track>.audio) → encrypted ones in media/<id>/.
     * Runs at start, quietly; a book that is playing is skipped and done next time.
     */
    fun migrateLegacy(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val books = BookStore.get(app).root.listFiles()?.filter { File(it, "$LEGACY_DIR/$MANIFEST").isFile }.orEmpty()
            books.forEach { d ->
                runCatching {
                    val id = d.name
                    if (AudioPlayback.book?.let { AudioLibraryLink.bookId(it.serverId, it.id) } == id) return@runCatching
                    val old = File(d, LEGACY_DIR)
                    val b = Http.json.decodeFromString(AudioBook.serializer(), File(old, MANIFEST).readText())
                    val key = MediaCrypt.key(app)
                    dir(app, id).mkdirs()
                    b.tracks.forEach { t ->
                        val src = t.localPath?.let(::File)?.takeIf { it.isFile } ?: return@forEach
                        val tmp = partFile(app, id, t.id).apply { delete() }
                        src.inputStream().use { input -> tmp.outputStream().use { out -> MediaCrypt.encrypt(key, MediaCrypt.newNonce(), input, out) } }
                        tmp.renameTo(trackFile(app, id, t.id))
                    }
                    writeManifest(app, id, b)
                    old.deleteRecursively()
                    refreshRow(app, id)
                }
            }
            // a request left from before the app was closed (the worker also comes back by itself)
            if (pendingBooks(app).isNotEmpty()) kick(app)
        }
    }
}
