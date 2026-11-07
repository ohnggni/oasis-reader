package com.oasisreader.app.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.oasisreader.app.data.BookOrigin
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.remote.Remote
import com.oasisreader.app.remote.RemoteFs
import com.oasisreader.app.ui.ShelfSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 소장 오디오북 (TODO 30): an audiobook made from a folder of sound files on a 🗄️ 파일 서버 or in a
 * phone folder, put on the 서재 like a bookoasis audiobook (format "audiobook", the same player).
 *
 * Rules (Audiobookshelf · Smart AudioBook Player · Voice, and the bookoasis "shyni" layout):
 * - a folder holding sound files = one book (folders above it are only walked through); sub folders
 *   that are all "CD 1" / "Disc 2" / "Part 3" are one book; several .m4b side by side are a book each
 * - the tracks: file names in natural order
 * - title · author · narrator · description: metadata.json (Audiobookshelf / shyni) → *.nfo →
 *   desc.txt · reader.txt → the folder name
 * - cover: cover/folder/front.jpg·png → the json's own picture → any picture → (폰 폴더) the first track's
 * - 장: metadata.json chapters, or (one m4b/m4a) the chapters inside the file
 *
 * Where it plays from is the listener's choice: 파일 서버 = stream (☁️) or receive; 폰 폴더 = in place
 * (the folder stays readable to the app) or copied in. Received/copied tracks are the encrypted
 * kept tracks of [AudioDownloads] (same as bookoasis audiobooks).
 */
object OwnAudio {
    private const val TAG = "ownaudio:"
    const val REMOTE = "remote"
    const val FOLDER = "folder"

    fun isOwn(b: BookEntity) = b.format == AudioLibraryLink.FORMAT && b.opdsId?.startsWith(TAG) == true

    /** What the 서재 row plays: the book (tracks with their remote:// / content:// places) and where it came from. */
    @Serializable
    data class Own(
        val book: AudioBook,
        val source: String,                 // REMOTE | FOLDER
        val serverId: String = "",
        val path: String = "",              // 파일 서버: the book folder (or file)
        val tree: String = "",              // 폰 폴더: the folder picked (its permission)
        val narrator: String? = null,
        val description: String? = null,
        val inPlace: Boolean = false,       // 폰 폴더 played where it is (not copied)
    )

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "own_audio").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun read(ctx: Context, id: String): Own? = runCatching {
        Http.json.decodeFromString(Own.serializer(), file(ctx, id).readText())
    }.getOrNull()

    private fun write(ctx: Context, id: String, o: Own) {
        val f = file(ctx, id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Http.json.encodeToString(Own.serializer(), o))
        tmp.renameTo(f)
    }

    fun forget(ctx: Context, id: String) { file(ctx, id).delete() }

    /** A track's length, learnt while playing (the files say nothing before). */
    fun learnDuration(ctx: Context, id: String, index: Int, sec: Double) = synchronized(this) {
        val o = read(ctx, id) ?: return@synchronized
        val t = o.book.tracks.getOrNull(index) ?: return@synchronized
        if (t.durationSec > 0) return@synchronized
        write(ctx, id, o.copy(book = o.book.copy(tracks = o.book.tracks.toMutableList().also { it[index] = t.copy(durationSec = sec) })))
    }

    /** The row's book, ready for the player (kept tracks and the cover filled in by AudioPlayback). */
    suspend fun play(context: Context, row: BookEntity) {
        val app = context.applicationContext
        val o = withContext(Dispatchers.IO) { read(app, row.id) } ?: throw IllegalStateException("이 오디오북의 정보 파일이 없습니다. 다시 가져와 주세요.")
        val cover = BookStore.get(app).coverFile(row.id).takeIf { it.isFile }?.path
        val b = o.book.copy(title = row.title, author = row.author ?: o.book.author, artworkFile = cover)
        val placed = row.lastReadAt != null && row.spineIndex in b.tracks.indices
        withContext(Dispatchers.Main) {
            if (AudioPlayback.book?.rowId() == row.id) AudioPlayback.open(context, null, b)
            else if (placed) AudioPlayback.open(context, null, b, row.spineIndex, row.chapterFraction.toDouble())
            else AudioPlayback.open(context, null, b)
        }
    }

    // ───────────────────────────── finding books ─────────────────────────────

    /** A folder or file where the books are looked for: a 파일 서버 path or a 폰 폴더 document. */
    data class Entry(val name: String, val ref: String, val isDir: Boolean, val size: Long = -1)

    /** Lists and reads one source (blocking: Dispatchers.IO). */
    interface Source {
        fun list(dir: Entry): List<Entry>
        fun bytes(e: Entry, max: Int): ByteArray
        fun reader(e: Entry): RandomReader
        /** Where the player reads the track. */
        fun trackUri(e: Entry): String
    }

    class RemoteSource(private val serverId: String, private val fs: RemoteFs) : Source {
        override fun list(dir: Entry) = fs.list(dir.ref).map { Entry(it.name, it.path, it.isDir, it.size) }
        override fun bytes(e: Entry, max: Int) = fs.openRead(e.ref, 0).use { it.readUpTo(max) }
        override fun reader(e: Entry) = object : RandomReader {
            override val size = e.size
            override fun read(offset: Long, length: Int) = fs.openRead(e.ref, offset).use { it.readUpTo(length) }
        }
        override fun trackUri(e: Entry) = Remote.url(serverId, e.ref)
    }

    /** A folder picked on the phone (Storage Access Framework). ref = a document uri. */
    class FolderSource(context: Context, private val tree: Uri) : Source {
        private val cr = context.applicationContext.contentResolver
        override fun list(dir: Entry): List<Entry> {
            val docId = DocumentsContract.getDocumentId(Uri.parse(dir.ref))
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val out = ArrayList<Entry>()
            cr.query(kids, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), isDir, if (c.isNull(3)) -1 else c.getLong(3))
                }
            }
            return out
        }
        override fun bytes(e: Entry, max: Int): ByteArray = cr.openInputStream(Uri.parse(e.ref))?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            while (out.size() < max) { val n = input.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
            out.toByteArray()
        } ?: ByteArray(0)
        override fun reader(e: Entry) = object : RandomReader {
            override val size = e.size
            override fun read(offset: Long, length: Int): ByteArray =
                cr.openFileDescriptor(Uri.parse(e.ref), "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        val buf = ByteBuffer.allocate(length)
                        var at = offset
                        while (buf.hasRemaining()) { val n = ch.read(buf, at); if (n <= 0) break; at += n }
                        buf.array().copyOf(buf.position())
                    }
                } ?: ByteArray(0)
        }
        override fun trackUri(e: Entry) = e.ref
        fun root(): Entry {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            return Entry(docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "폰 폴더" }, DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(), true)
        }
    }

    /** One audiobook found: its folder (or single file), the folders above it, its tracks in order, its other files. */
    data class Found(val home: Entry, val parents: List<String>, val tracks: List<Entry>, val files: List<Entry>, val singleFile: Boolean = false) {
        val title get() = if (singleFile) home.name.substringBeforeLast('.') else home.name
    }

    private val DISC = Regex("^(cd|disc|disk|part|파트)\\s*[-_.]?\\s*\\d+.*$", RegexOption.IGNORE_CASE)
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private val byName = Comparator<Entry> { a, b -> ShelfSearch.naturalCompare(a.name, b.name) }

    /** Every audiobook in [root] (it may be one itself), up to [max]. */
    fun find(src: Source, root: Entry, max: Int = 300, stop: () -> Boolean = { false }): List<Found> {
        val out = ArrayList<Found>()
        fun go(d: Entry, parents: List<String>, depth: Int) {
            if (out.size >= max || stop()) return
            val items = src.list(d).filter { !it.name.startsWith(".") }
            val audio = items.filter { !it.isDir && Remote.isAudioName(it.name) }.sortedWith(byName)
            val files = items.filter { !it.isDir }
            val dirs = items.filter { it.isDir }.sortedWith(byName)
            if (audio.isNotEmpty()) {
                // several m4b side by side: each is a whole book
                if (audio.size > 1 && audio.all { ext(it.name) == "m4b" }) audio.forEach { out += Found(it, parents + d.name, listOf(it), files, singleFile = true) }
                else out += Found(d, parents, audio, files)
                return
            }
            if (dirs.isNotEmpty() && dirs.all { DISC.matches(it.name) }) {
                val tracks = dirs.flatMap { cd -> src.list(cd).filter { !it.isDir && Remote.isAudioName(it.name) }.sortedWith(byName) }
                if (tracks.isNotEmpty()) { out += Found(d, parents, tracks, files); return }
            }
            if (depth < 10) dirs.forEach { go(it, parents + d.name, depth + 1) }
        }
        go(root, emptyList(), 0)
        return out
    }

    // ───────────────────────────── reading what it is ─────────────────────────────

    /** Everything known about one found book, before it goes on the 서재. */
    data class Draft(
        val title: String, val author: String?, val narrator: String?, val description: String?,
        val tracks: List<AudioTrack>, val chapters: List<AudioChapter>, val cover: ByteArray?,
        val bytes: Long = 0,
    )

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
    private fun JsonObject.names(k: String): String? = when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("name") }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }

    /** Text in an unknown encoding: UTF-8, else CP949 (Korean) when it reads as Korean, else Windows-1252. */
    fun text(bytes: ByteArray): String {
        fun strict(cs: Charset) = runCatching {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        val b = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        if (b !== bytes) return String(b, Charsets.UTF_8)
        strict(Charsets.UTF_8)?.let { return it }
        runCatching { strict(Charset.forName("x-windows-949")) }.getOrNull()?.let { s -> if (s.any { it in '가'..'힣' }) return s }
        return String(bytes, Charset.forName("windows-1252"))
    }

    /** The .nfo of rippers (inAudible and the like): "Title: …", "Author: …", "Read By: …", a "Book Description" part. */
    private fun nfo(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        val lines = s.lines()
        lines.forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) {
                val k = l.substring(0, i).trim().lowercase(); val v = l.substring(i + 1).trim()
                if (v.isNotEmpty() && k !in out) out[k] = v
            }
        }
        val d = lines.indexOfFirst { it.trim().equals("Book Description", true) || it.trim().equals("Description", true) }
        if (d >= 0) {
            val body = lines.drop(d + 1).dropWhile { it.isBlank() || it.trim().all { c -> c == '=' || c == '-' } }
                .takeWhile { !(it.isNotBlank() && it.trim().all { c -> c == '=' }) }.joinToString("\n").trim()
            if (body.isNotEmpty()) out["@description"] = body
        }
        return out
    }

    // a leading track number: "[001] ", "(12) ", "03 - ", "7. " — not "1회", "1장" (part of the title)
    private val LEAD_NO = Regex("^\\s*(\\[\\d{1,4}]|\\(\\d{1,4}\\)|\\d{1,4}(?=[\\s\\-_.)]))\\s*[-_.)]?\\s*")

    /** Track titles from file names: numbers and what every name shares taken off ("[001] 프롤로그.m4a" → "프롤로그"). */
    fun trackTitles(names: List<String>): List<String> {
        val base = names.map { it.substringBeforeLast('.') }
        var stripped = base.map { n -> n.replace(LEAD_NO, "") }
        if (stripped.size > 1) {
            // what all names share (never cutting into a number: "… - 001", "… - 002" keep 001, 002)
            val p = stripped.reduce { a, b -> a.commonPrefixWith(b) }.trimEnd { it.isDigit() }
            val s = stripped.reduce { a, b -> a.commonSuffixWith(b) }.trimStart { it.isDigit() }
            if (p.isNotEmpty() && stripped.all { it.length > p.length }) stripped = stripped.map { it.removePrefix(p) }
            if (s.isNotEmpty() && stripped.all { it.length > s.length }) stripped = stripped.map { it.removeSuffix(s) }
        }
        return stripped.mapIndexed { i, t ->
            val c = t.trim().trim('-', '_', '.', ' ').replace(LEAD_NO, "").trim().trim('-', '_', '.', ' ')
            c.ifEmpty { base[i].trim().takeIf { it.any(Char::isDigit) && it.length <= 12 } ?: "${i + 1}" }.ifEmpty { "${i + 1}" }
        }
    }

    /** Reads what [f] is (blocking). [embeddedCover]: the first track's picture, when it can be read cheaply. */
    fun draft(src: Source, f: Found, embeddedCover: (Entry) -> ByteArray? = { null }): Draft {
        val files = f.files.associateBy { it.name.lowercase() }
        var title: String? = null; var author: String? = null; var narrator: String? = null; var description: String? = null
        var jsonChapters: List<Triple<Double, Double, String>> = emptyList()
        var cover: ByteArray? = null

        if (!f.singleFile) files["metadata.json"]?.let { e ->
            runCatching {
                val o = Http.json.parseToJsonElement(src.bytes(e, 16 * 1024 * 1024).toString(Charsets.UTF_8)) as JsonObject
                title = o.str("title")?.let { t -> o.str("subtitle")?.takeIf { it.isNotBlank() && !it.startsWith("Collect", true) }?.let { "$t : $it" } ?: t }
                author = o.names("authors") ?: o.str("author")
                narrator = o.names("narrators") ?: o.str("narrator")
                description = o.str("description")
                jsonChapters = (o["chapters"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val s = (co["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val en = (co["end"] as? JsonPrimitive)?.doubleOrNull ?: s
                    Triple(s, en, co.str("title") ?: "")
                }
                // shyni: the cover inside the json (images.cover → image_blobs[hash].data, base64)
                val hash = ((o["images"] as? JsonObject)?.get("cover") as? JsonPrimitive)?.contentOrNull
                val blob = (o["image_blobs"] as? JsonObject)?.let { blobs -> (hash?.let { blobs[it] } ?: blobs.values.firstOrNull()) as? JsonObject }
                blob?.str("data")?.let { cover = android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            }
        }
        if (!f.singleFile && (title == null || author == null)) f.files.firstOrNull { ext(it.name) == "nfo" }?.let { e ->
            runCatching {
                val m = nfo(text(src.bytes(e, 512 * 1024)))
                title = title ?: m["title"]?.replace(Regex("\\s*\\((un)?abridged\\)\\s*$", RegexOption.IGNORE_CASE), "")
                author = author ?: m["author"] ?: m["authors"] ?: m["artist"]
                narrator = narrator ?: m["read by"] ?: m["narrator"] ?: m["narrated by"]
                description = description ?: m["@description"]
            }
        }
        if (!f.singleFile) {
            if (description == null) files["desc.txt"]?.let { e -> description = runCatching { text(src.bytes(e, 256 * 1024)).trim() }.getOrNull()?.ifEmpty { null } }
            if (narrator == null) files["reader.txt"]?.let { e -> narrator = runCatching { text(src.bytes(e, 4096)).trim() }.getOrNull()?.ifEmpty { null } }
            if (cover == null) {
                val pics = f.files.filter { ext(it.name) in IMAGE }
                val pick = pics.firstOrNull { it.name.substringBeforeLast('.').lowercase() in setOf("cover", "folder", "front") } ?: pics.firstOrNull()
                pick?.let { cover = runCatching { src.bytes(it, 8 * 1024 * 1024) }.getOrNull() }
            }
        }
        if (cover == null) cover = runCatching { embeddedCover(f.tracks.first()) }.getOrNull()

        val names = trackTitles(f.tracks.map { it.name })
        var tracks = f.tracks.mapIndexed { i, e -> AudioTrack(i + 1, names[i], 0.0, uri = src.trackUri(e)) }
        var chapters = emptyList<AudioChapter>()
        when {
            // one chapter per file: the chapter titles name the tracks, their lengths are the tracks'
            jsonChapters.size == tracks.size && tracks.size > 1 -> tracks = tracks.mapIndexed { i, t ->
                val c = jsonChapters[i]
                t.copy(title = c.third.ifBlank { t.title }, durationSec = (c.second - c.first).coerceAtLeast(0.0))
            }
            // one file, many chapters
            tracks.size == 1 && jsonChapters.size > 1 -> {
                chapters = jsonChapters.map { AudioChapter(0, it.first, it.third) }
                tracks = listOf(tracks[0].copy(durationSec = jsonChapters.last().second))
            }
            tracks.size == 1 && ext(f.tracks[0].name) in setOf("m4b", "m4a", "mp4") -> runCatching {
                Mp4Chapters.read(src.reader(f.tracks[0]))?.let { r ->
                    if (r.chapters.size > 1) chapters = r.chapters.mapIndexed { i, (s, t) -> AudioChapter(0, s, t.ifBlank { "${i + 1}장" }) }
                    if (r.durationSec > 0) tracks = listOf(tracks[0].copy(durationSec = r.durationSec))
                }
            }
        }
        if (tracks.size == 1 && chapters.isEmpty()) tracks = listOf(tracks[0].copy(title = f.title))
        return Draft(title ?: f.title, author, narrator, description, tracks, chapters, cover, f.tracks.sumOf { it.size.coerceAtLeast(0) })
    }

    // ───────────────────────────── on the 서재 ─────────────────────────────

    private fun idOf(key: String): String =
        "ao_" + java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * Puts one found book on the 서재 (again: refreshed, its place kept). [keep]: receive / copy the
     * tracks to the phone (encrypted) — else it plays from where it is (☁️ for a 파일 서버).
     * Returns the 서재 id.
     */
    suspend fun add(context: Context, d: Draft, own: Own, key: String, keep: Boolean, origin: String? = null): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val id = idOf(key)
        val store = BookStore.get(app)
        val cover = store.coverFile(id)
        d.cover?.takeIf { it.size > 100 }?.let { runCatching { cover.parentFile?.mkdirs(); cover.writeBytes(it) } }
        val book = AudioBook(
            id = (id.hashCode() and 0x7fffffff), title = d.title, author = d.author, coverUrl = null, tracks = d.tracks,
            own = true, shelfId = id, chapters = d.chapters, artworkFile = cover.takeIf { it.isFile }?.path,
        )
        write(app, id, own.copy(book = book, narrator = d.narrator, description = d.description))
        val dao = AppDatabase.get(app).books()
        val old = dao.get(id)
        val row = (old ?: BookEntity(id = id, title = d.title, addedAt = System.currentTimeMillis())).copy(
            title = old?.title ?: d.title,
            author = d.author ?: old?.author,
            format = AudioLibraryLink.FORMAT,
            opdsId = TAG + key,
            hasCover = cover.isFile,
            // 폰 폴더 played in place is on the phone already (no ☁️); kept tracks set this when they come
            pinned = own.inPlace || AudioDownloads.isKept(app, id),
            fileBytes = if (own.inPlace) d.bytes.coerceAtLeast(1) else AudioDownloads.keptBytes(app, id),
            origin = origin ?: old?.origin,
        )
        dao.insert(row)
        if (keep) AudioDownloads.enqueue(app, row, null, book)
        id
    }

    /** Where the worker reads a 소장 track (for 받기 / 복사): an open read from [offset]. */
    fun opener(context: Context, o: Own): Opener = when (o.source) {
        REMOTE -> {
            val server = com.oasisreader.app.data.ServerSettings(context.applicationContext).get(o.serverId)
                ?: throw IllegalStateException("이 오디오북의 파일 서버 설정이 지워졌습니다")
            val fs = Remote.open(context.applicationContext, server)
            object : Opener {
                override fun open(t: AudioTrack, offset: Long): com.oasisreader.app.remote.RemoteRead {
                    val (_, path) = Remote.parseUrl(t.uri ?: "") ?: throw IllegalStateException("트랙 위치가 없습니다")
                    return fs.openRead(path, offset)
                }
                override fun close() { runCatching { fs.close() } }
            }
        }
        else -> {
            val cr = context.applicationContext.contentResolver
            object : Opener {
                override fun open(t: AudioTrack, offset: Long): com.oasisreader.app.remote.RemoteRead {
                    val pfd = cr.openFileDescriptor(Uri.parse(t.uri ?: ""), "r") ?: throw IllegalStateException("폴더에 접근할 수 없습니다")
                    val input = java.io.FileInputStream(pfd.fileDescriptor)
                    input.channel.position(offset)
                    return com.oasisreader.app.remote.RemoteRead(input, pfd.statSize, offset) { runCatching { pfd.close() } }
                }
                override fun close() {}
            }
        }
    }

    interface Opener : java.io.Closeable { fun open(t: AudioTrack, offset: Long): com.oasisreader.app.remote.RemoteRead }
}
