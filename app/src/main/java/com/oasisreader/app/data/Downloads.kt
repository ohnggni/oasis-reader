package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.opds.OpdsClient
import com.oasisreader.app.opds.OpdsEntry
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
    fun start(context: Context, server: Server, entry: OpdsEntry, pinned: Boolean = true, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)? = null, collectionId: String? = null, origin: String? = null) {
        val acq = entry.readable ?: run { _messages.tryEmit("EPUB, CBZ/ZIP, TXT, PDF 파일이 없는 항목입니다"); return }
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(entry.id, server.id, acq.href, entry.title, collectionId = collectionId, origin = origin))
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
        origin: String? = null,
    ) {
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(key, server.id, href, title, meta = true, collectionId = collectionId, origin = origin))
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
        origin: String? = null,
    ) {
        val url = com.oasisreader.app.remote.Remote.url(server.id, path)
        val title = com.oasisreader.app.remote.Remote.nameOf(path).substringBeforeLast('.')
        if (pinned && onDone == null) {
            BookQueue.enqueue(context, BookQueue.Job(url, server.id, url, title, collectionId = collectionId, origin = origin))
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
                collectionId?.let { com.oasisreader.app.ui.ShelfCollections.set(com.oasisreader.app.data.db.AppDatabase.get(app), it, book.id, true) }
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
        val remote = com.oasisreader.app.remote.Remote.parseUrl(url)
        if (remote != null) {
            val server = ServerSettings(app).get(remote.first) ?: throw HttpException(0, "서버 설정이 지워졌습니다")
            dest.delete()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.oasisreader.app.remote.Remote.open(app, server).use { it.download(remote.second, dest, onProgress) { false } }
            }
        } else {
            val server = ServerSettings(app).forUrl(url) ?: Server("", "", "", "", "")
            com.oasisreader.app.opds.OpdsClient(server).download(url, dest, onProgress)
        }
    }

    /** Where the book came from (다음 권 / 있던 폴더 열기). */
    suspend fun recordOrigin(app: Context, bookId: String, origin: String?) {
        if (origin != null) runCatching { com.oasisreader.app.data.db.AppDatabase.get(app).books().setOrigin(bookId, origin) }
    }

    /** Kept books get a message; "바로 읽기" opens the book and makes room in the cache. */
    private suspend fun afterImport(app: Context, book: com.oasisreader.app.data.db.BookEntity, pinned: Boolean, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)?) {
        if (pinned) com.oasisreader.app.ui.Notice.added("서재에 담았습니다: ${book.title}", book.id)
        onDone?.invoke(book)
        if (!pinned) runCatching { BookStore.get(app).trimCache(CachePrefs(app).limitBytes, keep = book.id) }
    }
}
