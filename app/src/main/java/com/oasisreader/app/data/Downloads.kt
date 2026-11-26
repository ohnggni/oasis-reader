package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Book downloads from the catalog. Runs in an app-wide scope so leaving the
 * catalog screen does not cancel a download in progress.
 */
object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** "download all" may queue a whole series: at most two files at a time */
    private val gate = kotlinx.coroutines.sync.Semaphore(2)

    /** entry id -> 0..1 (or -1 while the size is unknown) */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    /** 바로 읽기 (and 다음 권) downloads, not queued: key -> title, so 받기 관리 can show them */
    private val _direct = MutableStateFlow<Map<String, String>>(emptyMap())
    val direct: StateFlow<Map<String, String>> = _direct

    /** The download running now, for the card over every screen (TODO 44): [fraction] 0..1, or -1 while the size is unknown. */
    data class Receiving(val title: String, val fraction: Float, val left: Int)
    private val _receiving = MutableStateFlow<Receiving?>(null)
    val receiving: StateFlow<Receiving?> = _receiving
    fun nowReceiving(title: String, fraction: Float, left: Int) { _receiving.value = Receiving(title, fraction, left) }
    fun received() { _receiving.value = null }

    /** user-facing messages ("받았습니다", errors) */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Progress from the background queue ([BookQueue]); null takes the entry out. */
    fun setProgress(key: String, f: Float?) = _progress.update { if (f == null) it - key else it + (key to f) }
    fun clearProgress() = _progress.update { emptyMap() }

    /** A download's progress for [key], sent on only when it moved by 1% (it arrives every 64KB). */
    private fun stepped(key: String): (Long, Long) -> Unit {
        var last = -2f
        return { done, total ->
            val f = if (total > 0) done.toFloat() / total else -1f
            if (f < 0f || f - last >= 0.01f) {
                last = f
                _progress.update { it + (key to f) }
                _receiving.value = Receiving(_direct.value[key].orEmpty(), f, 0)
            }
        }
    }
    fun message(text: String) { _messages.tryEmit(text) }

    /**
     * 서재에 담기 (pinned, nothing to do after) goes to the background queue ([BookQueue]):
     * it goes on with the app closed and can be paused. 바로 읽기 (opens the book when done)
     * runs here, right away.
     */
    fun start(context: Context, server: Server, entry: OpdsEntry, pinned: Boolean = true, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)? = null, collectionId: String? = null, origin: String? = null, shelvesOf: String? = null, mobile: Boolean = false) {
        val acq = entry.readable ?: run { _messages.tryEmit("EPUB, CBZ/ZIP, TXT, PDF 파일이 없는 항목입니다"); return }
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(entry.id, server.id, acq.href, entry.title, collectionId = collectionId, origin = origin, mobile = mobile))
            return
        }
        // 103: it must wait for Wi-Fi — into the queue (received later, not opened by itself)
        if (!mobile && DownloadPrefs.mustWait(context)) {
            BookQueue.enqueue(context, BookQueue.Job(entry.id, server.id, acq.href, entry.title, collectionId = collectionId, origin = origin, temp = !pinned, shelvesOf = shelvesOf))
            return
        }
        if (_progress.value.containsKey(entry.id)) return
        val app = context.applicationContext
        _progress.update { it + (entry.id to -1f) }
        _direct.update { it + (entry.id to entry.title) }
        scope.launch {
            val tmp = File(app.cacheDir, "dl_${System.nanoTime()}.bin")
            try {
                val fileName = OpdsClient(server).download(acq.href, tmp, stepped(entry.id))
                // catalog titles like "[만화] 1" carry no series name: prefer the server's file name
                val generic = OpdsClient.isGenericTitle(entry.title)
                val hint = if (generic) fileName?.substringBeforeLast('.')?.ifBlank { null } else entry.title
                val book = BookStore.get(app).import(
                    tmp, sourceUrl = acq.href, opdsId = entry.id,
                    titleHint = hint, fallbackTitle = entry.title, pinned = pinned, moveSource = true,
                )
                recordOrigin(app, book.id, origin)
                afterImport(app, book, pinned, onDone)
            } catch (e: HttpException) {
                _messages.tryEmit("받기 실패: ${e.message}")
            } catch (e: Exception) {
                _messages.tryEmit("받기 실패: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                tmp.delete()
                _progress.update { it - entry.id }
                _direct.update { it - entry.id }; _receiving.value = null
            }
        }
    }

    /**
     * Download any bookoasis file (from the meta catalog). [key] is the progress/dedupe key;
     * the title is taken as given (the meta API already knows the clean one).
     */
    fun startUrl(
        context: Context, server: Server, key: String, href: String, title: String,
        pinned: Boolean = true, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)? = null, collectionId: String? = null,
        origin: String? = null, shelvesOf: String? = null, mobile: Boolean = false,
    ) {
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(key, server.id, href, title, meta = true, collectionId = collectionId, origin = origin, mobile = mobile))
            return
        }
        if (!mobile && DownloadPrefs.mustWait(context)) {   // 103
            BookQueue.enqueue(context, BookQueue.Job(key, server.id, href, title, meta = true, collectionId = collectionId, origin = origin, temp = !pinned, shelvesOf = shelvesOf))
            return
        }
        if (_progress.value.containsKey(key)) return
        val app = context.applicationContext
        _progress.update { it + (key to -1f) }
        _direct.update { it + (key to title) }
        scope.launch {
            val tmp = File(app.cacheDir, "dl_${System.nanoTime()}.bin")
            try {
                val book = gate.withPermit {
                    OpdsClient(server).download(href, tmp, stepped(key))
                    BookStore.get(app).import(tmp, sourceUrl = href, opdsId = key, titleHint = title, fallbackTitle = title, pinned = pinned, moveSource = true)
                }.let { b ->
                    // comics: the catalog's clean title beats whatever ComicInfo inside says
                    if ((b.format == BookStore.FORMAT_COMIC || b.format == BookStore.FORMAT_PDF) && b.title != title) {
                        com.oasisreader.app.data.db.AppDatabase.get(app).books().rename(b.id, title)
                        b.copy(title = title)
                    } else b
                }
                recordOrigin(app, book.id, origin)
                afterImport(app, book, pinned, onDone)
            } catch (e: HttpException) {
                _messages.tryEmit("받기 실패: ${e.message}")
            } catch (e: Exception) {
                _messages.tryEmit("받기 실패: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                tmp.delete()
                _progress.update { it - key }
                _direct.update { it - key }; _receiving.value = null
            }
        }
    }

    /**
     * A file from a file server (WebDAV · SFTP · SMB · FTP). Kept books go through the queue
     * (background, resumable); 바로 읽기 is received at once. Its key and source are remote://….
     */
    fun startRemote(
        context: Context, server: Server, path: String,
        pinned: Boolean = true, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)? = null, collectionId: String? = null,
        origin: String? = null, shelvesOf: String? = null, mobile: Boolean = false,
    ) {
        val url = RemoteAddress.url(server.id, path)
        val title = RemoteAddress.nameOf(path).substringBeforeLast('.')
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(url, server.id, url, title, collectionId = collectionId, origin = origin, mobile = mobile))
            return
        }
        if (!mobile && DownloadPrefs.mustWait(context)) {   // 103
            BookQueue.enqueue(context, BookQueue.Job(url, server.id, url, title, collectionId = collectionId, origin = origin, temp = !pinned, shelvesOf = shelvesOf))
            return
        }
        if (_progress.value.containsKey(url)) return
        val app = context.applicationContext
        _progress.update { it + (url to -1f) }
        _direct.update { it + (url to title) }
        scope.launch {
            val tmp = File(app.cacheDir, "dl_${System.nanoTime()}.bin")
            try {
                val book = gate.withPermit {
                    fetch(app, url, tmp) { done, total ->
                        val f = if (total > 0) done.toFloat() / total else -1f
                        _progress.update { it + (url to f) }
                    }
                    BookStore.get(app).import(tmp, sourceUrl = url, opdsId = url, titleHint = title, fallbackTitle = title, pinned = pinned, moveSource = true)
                }
                collectionId?.let { com.oasisreader.app.data.ShelfCollections.set(com.oasisreader.app.data.db.AppDatabase.get(app), it, book.id, true) }
                recordOrigin(app, book.id, origin)
                afterImport(app, book, pinned, onDone)
            } catch (e: Exception) {
                _messages.tryEmit("받기 실패: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                tmp.delete()
                _progress.update { it - url }
                _direct.update { it - url }; _receiving.value = null
            }
        }
    }

    /**
     * Receives a book's source again into [dest] (its file went missing, 다시 받기): an OPDS /
     * bookoasis link, or a file server's remote://….
     */
    suspend fun fetch(app: Context, url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val remote = RemoteAddress.parseUrl(url)
        if (remote != null) {
            val server = ServerSettings(app).get(remote.first) ?: throw HttpException(0, "서버 설정이 지워졌습니다")
            dest.delete()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.oasisreader.app.remote.Remote.open(app, server).use { it.download(remote.second, dest, onProgress) { false } }
            }
        } else {
            val server = ServerSettings(app).forUrl(url) ?: Server("", "", "", "", "")
            com.oasisreader.app.data.OpdsClient(server).download(url, dest, onProgress)
        }
    }

    private val missingLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * The file of a 서재 book that is not on the phone (☁ — dropped, or 등록만 and never received),
     * from its source. One at a time per book: opening it while 다음 회차 미리 받기 is fetching it
     * waits for that instead of receiving it twice. Already here: nothing to do.
     */
    suspend fun receiveMissing(app: Context, bookId: String, url: String, keepGoing: () -> Boolean = { true }) {
        val lock = synchronized(missingLocks) { missingLocks.getOrPut(bookId) { kotlinx.coroutines.sync.Mutex() } }
        lock.lock()
        try {
            val store = BookStore.get(app)
            if (store.hasFiles(bookId)) return
            val tmp = File(app.cacheDir, "refetch_${System.nanoTime()}.bin")
            try {
                var checkedAt = 0L
                fetch(app, url, tmp) { _, _ ->
                    val now = System.currentTimeMillis()
                    if (now - checkedAt >= 1000) { checkedAt = now; if (!keepGoing()) throw WifiLost() }
                }
                store.rebuild(bookId, tmp)
            } finally {
                tmp.delete()
            }
        } finally {
            lock.unlock()
        }
    }

    /** Wi-Fi went away while something that waits for it was coming. */
    private class WifiLost : java.io.IOException()

    /** Is [url] waiting in the queue with leave to use mobile data ("지금 받기", 이번만)? */
    fun allowedOnMobile(app: Context, url: String) =
        BookQueue.all(app).any { it.href == url && it.mobile && it.state == BookQueue.State.WAITING }

    /**
     * The reader asked for a missing book now (다시 받기, or opening a ☁ one): received at once
     * (true), or — "와이파이에서만 받기" with no Wi-Fi (103), unless [mobile] — put in the queue to
     * come later (false).
     */
    suspend fun receiveOrQueue(app: Context, bookId: String, url: String, mobile: Boolean = false): Boolean {
        val dao = com.oasisreader.app.data.db.AppDatabase.get(app).books()
        val queued = BookQueue.all(app).firstOrNull { it.href == url }
        if (!mobile && DownloadPrefs.mustWait(app) && !BookStore.get(app).hasFiles(bookId)) {
            val book = dao.get(bookId)
            val server = ServerSettings(app).forUrl(url)
            if (book != null && server != null) {
                BookQueue.enqueue(app, BookQueue.Job(book.opdsId ?: url, server.id, url, book.title, temp = !book.pinned))
                return false
            }
        }
        // coming from the queue right now ("지금 받기" a moment ago): wait for that one
        if (queued != null) {
            while ((_progress.value[queued.key] ?: -1f) >= 0f &&
                BookQueue.get(app, queued.key)?.let { it.state == BookQueue.State.WAITING && it.error == null } == true
            ) kotlinx.coroutines.delay(500)
            if (BookQueue.get(app, queued.key) == null && BookStore.get(app).hasFiles(bookId)) {
                if (!queued.temp) dao.setPinned(bookId, true)
                return true
            }
        }
        // the queue's copy would only fetch it a second time
        if (queued?.temp == true) BookQueue.cancel(app, queued.key)
        receiveMissing(app, bookId, url)
        if (queued != null && !queued.temp) { dao.setPinned(bookId, true); BookQueue.cancel(app, queued.key) }
        return true
    }

    /**
     * 다음 회차 미리 받기 (TODO 78): while [book] is read, the next one — when it is on the 서재
     * without its file and is a 바로 읽기 (임시 보관) entry, as 등록만 makes them — is received
     * quietly, so turning to it needs no wait. Failures stay quiet (opening it tries again).
     * "와이파이에서만 받기" holds for it too (103): without Wi-Fi it waits in the queue.
     */
    fun prefetchNext(context: Context, book: com.oasisreader.app.data.db.BookEntity) {
        val app = context.applicationContext
        if (!CachePrefs(app).prefetchNext) return
        scope.launch {
            runCatching {
                val next = (NextVolumes.find(app, book) as? NextVolume.InLibrary)?.book ?: return@launch
                val url = next.sourceUrl ?: return@launch
                if (next.pinned || BookStore.get(app).hasFiles(next.id)) return@launch
                if (!DownloadPrefs.mustWait(app)) {
                    if (BookQueue.get(app, next.opdsId ?: url) != null) return@launch   // the queue has it
                    try {
                        gate.withPermit { receiveMissing(app, next.id, url) { !DownloadPrefs.mustWait(app) } }
                        return@launch
                    } catch (e: Exception) {
                        if (!DownloadPrefs.mustWait(app)) return@launch
                        // Wi-Fi went away meanwhile: it waits in the queue, below
                    }
                }
                waitForWifi(app, next, url)
            }
        }
    }

    private var offeredNow = false   // the card, once while the app runs

    private fun waitForWifi(app: Context, next: com.oasisreader.app.data.db.BookEntity, url: String) {
        val server = ServerSettings(app).forUrl(url) ?: return
        val key = next.opdsId ?: url
        BookQueue.dropQuiet(app, except = key)
        val old = BookQueue.get(app, key)
        if (old == null) BookQueue.enqueue(app, BookQueue.Job(key, server.id, url, next.title, temp = true, quiet = true))
        else if (!old.quiet) return   // asked for already
        if (offeredNow) return
        offeredNow = true
        ShelfNotices.offer("다음 회차는 와이파이에서 받습니다", "지금 받기") { BookQueue.resume(app, key, mobile = true) }
    }

    /** Where the book came from (다음 권 / 있던 폴더 열기). */
    suspend fun recordOrigin(app: Context, bookId: String, origin: String?) {
        if (origin != null) runCatching { com.oasisreader.app.data.db.AppDatabase.get(app).books().setOrigin(bookId, origin) }
    }

    /** Kept books get a message; "바로 읽기" opens the book and makes room in the cache. */
    private suspend fun afterImport(app: Context, book: com.oasisreader.app.data.db.BookEntity, pinned: Boolean, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)?) {
        if (pinned) ShelfNotices.added("서재에 담았습니다: ${book.title}", book.id)
        onDone?.invoke(book)
        if (!pinned) runCatching { BookStore.get(app).trimCache(CachePrefs(app).limitBytes, keep = book.id) }
    }
}
