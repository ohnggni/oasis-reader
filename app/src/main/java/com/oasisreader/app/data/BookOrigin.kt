package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.meta.MetaApi
import com.oasisreader.app.opds.OpdsClient
import com.oasisreader.app.opds.OpdsEntry
import com.oasisreader.app.ui.ShelfCollections
import com.oasisreader.app.ui.ShelfSearch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a book came from (books.origin, DB v8): the OPDS folder (the whole path, like ⭐ 즐겨찾기),
 * the oasis-meta series, or the phone folder it was imported from. Books received before 1.2 have none.
 */
@Serializable
data class BookOrigin(
    val type: String,
    val server: String? = null,
    val urls: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val series: String? = null,
    val library: Int? = null,
    val tree: String? = null,
    val folder: String? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    /** 📂 있던 폴더 열기 can go there (a server folder or series; phone folders cannot be shown). */
    val openable get() = server != null && (type == OPDS && urls.isNotEmpty() || type == META && series != null || type == REMOTE && folder != null)

    companion object {
        const val OPDS = "opds"
        const val META = "meta"
        const val FOLDER = "folder"
        const val REMOTE = "remote"     // a file server folder (TODO 29): server + folder (its path there)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(s: String?): BookOrigin? = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun opds(serverId: String, urls: List<String>, labels: List<String>): String? =
            if (urls.isEmpty()) null else BookOrigin(OPDS, serverId, urls, labels).encode()

        fun meta(serverId: String, series: String?, library: Int?): String? =
            series?.ifBlank { null }?.let { BookOrigin(META, serverId, series = it, library = library).encode() }

        fun folder(tree: String, folder: String): String = BookOrigin(FOLDER, tree = tree, folder = folder).encode()

        fun remote(serverId: String, folder: String): String = BookOrigin(REMOTE, serverId, folder = folder).encode()
    }
}

/** 다음 권: on the 서재 already, or on the server (received with 바로 읽기 when asked). */
sealed interface NextVolume {
    val title: String

    data class InLibrary(val book: BookEntity) : NextVolume { override val title get() = book.title }

    class OnServer(override val title: String, val receive: (Context, (BookEntity) -> Unit) -> Unit) : NextVolume
}

/**
 * Finding the next volume: ① the same 컬렉션, titles in natural order (works offline); a book on
 * several 컬렉션 uses the first that has one after it; ② the folder or series it came from (the
 * server is asked again; a phone folder is looked up on the 서재); ③ nothing.
 */
object NextVolumes {
    private const val MAX_PAGES = 10

    suspend fun find(app: Context, book: BookEntity): NextVolume? {
        inCollection(app, book)?.let { return NextVolume.InLibrary(it) }
        return runCatching { fromOrigin(app, book) }.getOrNull()
    }

    private val byTitle = Comparator<BookEntity> { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }

    private suspend fun inCollection(app: Context, book: BookEntity): BookEntity? {
        val db = AppDatabase.get(app)
        val links = db.collections().observeLinks().first()
        val mine = links.filter { it.bookId == book.id }.map { it.collectionId }.toSet()
        if (mine.isEmpty()) return null
        val books = db.books().all().associateBy { it.id }
        for (c in db.collections().all().filter { it.id in mine }) {
            val list = links.filter { it.collectionId == c.id }.mapNotNull { books[it.bookId] }.sortedWith(byTitle)
            val i = list.indexOfFirst { it.id == book.id }
            list.getOrNull(i + 1)?.let { return it }
        }
        return null
    }

    private suspend fun fromOrigin(app: Context, book: BookEntity): NextVolume? {
        val o = BookOrigin.decode(book.origin) ?: return null
        val db = AppDatabase.get(app)
        return when (o.type) {
            BookOrigin.FOLDER -> db.books().withOrigin(book.origin!!).sortedWith(byTitle).let { list ->
                list.getOrNull(list.indexOfFirst { it.id == book.id } + 1)?.let { NextVolume.InLibrary(it) }
            }
            BookOrigin.OPDS -> {
                val server = ServerSettings(app).get(o.server) ?: return null
                val client = OpdsClient(server)
                val entries = ArrayList<OpdsEntry>()
                var next: String? = o.urls.lastOrNull() ?: return null
                var pages = 0
                var found = -1
                // pages until the book and the one after it are both in hand
                while (next != null && pages < MAX_PAGES) {
                    val f = client.fetch(next)
                    entries.addAll(f.entries.filter { it.isBook && it.readable != null })
                    next = f.next; pages++
                    found = entries.indexOfFirst { it.id == book.opdsId || it.readable?.href == book.sourceUrl }
                    if (found >= 0 && found < entries.size - 1) break
                }
                val e = entries.getOrNull(found + 1)?.takeIf { found >= 0 } ?: return null
                db.books().findBySource(e.id, e.readable?.href)?.let { return NextVolume.InLibrary(it) }
                NextVolume.OnServer(e.title) { c, done ->
                    Downloads.start(c, server, e, pinned = false, onDone = { b -> afterReceive(c, book, b); done(b) }, origin = book.origin)
                }
            }
            BookOrigin.META -> {
                val server = ServerSettings(app).get(o.server) ?: return null
                val api = MetaApi(server)
                val list = api.books(o.library?.toString(), o.series, null, "title", 1).items
                    .filter { it.readable }
                    .sortedWith { a, b -> ShelfSearch.naturalCompare(a.name, b.name) }
                val i = list.indexOfFirst { api.downloadUrl(it) == book.sourceUrl || api.key(it) == book.opdsId }
                if (i < 0) return null
                val m = list.getOrNull(i + 1) ?: return null
                val href = api.downloadUrl(m) ?: return null
                db.books().findBySource(api.key(m), href)?.let { return NextVolume.InLibrary(it) }
                NextVolume.OnServer(m.name) { c, done ->
                    Downloads.startUrl(c, server, api.key(m), href, m.name, pinned = false, onDone = { b -> afterReceive(c, book, b); done(b) }, origin = book.origin)
                }
            }
            BookOrigin.REMOTE -> {
                val server = ServerSettings(app).get(o.server) ?: return null
                val dir = o.folder ?: return null
                val mine = com.oasisreader.app.remote.Remote.parseUrl(book.sourceUrl ?: return null)?.second ?: return null
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.oasisreader.app.remote.Remote.list(app, server, dir)
                }.filter { it.isBook }.sortedWith { a, b -> ShelfSearch.naturalCompare(a.name, b.name) }
                val i = list.indexOfFirst { it.path == mine }
                if (i < 0) return null
                val f = list.getOrNull(i + 1) ?: return null
                val url = com.oasisreader.app.remote.Remote.url(server.id, f.path)
                db.books().findBySource(url, url)?.let { return NextVolume.InLibrary(it) }
                NextVolume.OnServer(f.name.substringBeforeLast('.')) { c, done ->
                    Downloads.startRemote(c, server, f.path, pinned = false, onDone = { b -> afterReceive(c, book, b); done(b) }, origin = book.origin)
                }
            }
            else -> null
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The next volume joins the 컬렉션 the current one is on (as a series usually is). */
    private fun afterReceive(app: Context, current: BookEntity, next: BookEntity) {
        scope.launch {
            runCatching {
                val db = AppDatabase.get(app)
                db.collections().observeLinks().first().filter { it.bookId == current.id }.forEach {
                    ShelfCollections.set(db, it.collectionId, next.id, true)
                }
            }
        }
    }
}
