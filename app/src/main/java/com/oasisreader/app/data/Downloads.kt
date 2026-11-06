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

    /** user-facing messages ("받았습니다", errors) */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Progress from the background queue ([BookQueue]); null takes the entry out. */
    fun setProgress(key: String, f: Float?) = _progress.update { if (f == null) it - key else it + (key to f) }
    fun clearProgress() = _progress.update { emptyMap() }
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
        scope.launch {
            val tmp = File(app.cacheDir, "dl_${System.nanoTime()}.bin")
            try {
                val fileName = OpdsClient(server).download(acq.href, tmp) { done, total ->
                    val f = if (total > 0) done.toFloat() / total else -1f
                    _progress.update { it + (entry.id to f) }
                }
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
        scope.launch {
            val tmp = File(app.cacheDir, "dl_${System.nanoTime()}.bin")
            try {
                val book = gate.withPermit {
                    OpdsClient(server).download(href, tmp) { done, total ->
                        val f = if (total > 0) done.toFloat() / total else -1f
                        _progress.update { it + (key to f) }
                    }
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
            }
        }
    }

    /** Where the book came from (다음 권 / 있던 폴더 열기). */
    suspend fun recordOrigin(app: Context, bookId: String, origin: String?) {
        if (origin != null) runCatching { com.oasisreader.app.data.db.AppDatabase.get(app).books().setOrigin(bookId, origin) }
    }

    /** Kept books get a message; "바로 읽기" opens the book and makes room in the cache. */
    private suspend fun afterImport(app: Context, book: com.oasisreader.app.data.db.BookEntity, pinned: Boolean, onDone: ((com.oasisreader.app.data.db.BookEntity) -> Unit)?) {
        if (pinned) _messages.tryEmit("서재에 담았습니다: ${book.title}")
        onDone?.invoke(book)
        if (!pinned) runCatching { BookStore.get(app).trimCache(CachePrefs(app).limitBytes, keep = book.id) }
    }
}
