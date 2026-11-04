package com.oasisreader.app.audio

import android.content.Context
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Audiobooks on the 서재: a book row without files (format [FORMAT]); the server and the
 * audiobook number sit in opdsId ("audiobook:<server>:<id>"). Listening keeps its place
 * (track, place in it, whole-book progress) like reading does, and the bookoasis place is
 * taken when it is further along (the web player may have gone on).
 */
object AudioLibraryLink {
    const val FORMAT = "audiobook"

    fun bookId(serverId: String, aid: Int) = "ab_${serverId}_$aid"
    private fun tag(serverId: String, aid: Int) = "audiobook:$serverId:$aid"

    /** (server id, audiobook id) of a library row, or null for other books. */
    fun parse(b: BookEntity): Pair<String, Int>? {
        if (b.format != FORMAT) return null
        val p = b.opdsId?.removePrefix("audiobook:")?.split(':') ?: return null
        val aid = p.lastOrNull()?.toIntOrNull() ?: return null
        return p.dropLast(1).joinToString(":") to aid
    }

    // ── reading the detail response (bookoasis /api/media/detail?type=audiobook) ──
    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" } }
    private fun JsonObject.int(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } }
    private fun JsonObject.dbl(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } }

    data class Detail(val book: AudioBook, val summary: String?, val percent: Double, val completed: Boolean)

    /** A book's tracks and the place bookoasis keeps for this account. */
    suspend fun detail(web: BookOasisWeb, serverId: String, aid: Int?, title: String, libraryId: String?): Detail {
        val o = web.get("/api/media/detail", mapOf(
            "type" to "audiobook", "series" to title, "library_id" to (libraryId ?: "all"),
            "representative_book_id" to aid,
        ))
        val meta = o["meta"] as? JsonObject ?: JsonObject(emptyMap())
        val tracks = (o["books"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { t ->
            val id = t.int("id") ?: return@mapNotNull null
            AudioTrack(
                id,
                // file names as titles: without the extension ("01.mp3" → "01")
                (t.str("title", "track_code") ?: "${t.int("track_number") ?: ""}")
                    .replace(Regex("\\.(mp3|m4b|m4a|flac|aac|wav|ogg|opus|wma)$", RegexOption.IGNORE_CASE), ""),
                t.dbl("duration") ?: 0.0,
            )
        }
        val id = meta.int("id") ?: aid ?: throw IllegalStateException("오디오북 번호를 알 수 없습니다")
        return Detail(
            book = AudioBook(
                id = id,
                title = meta.str("series_alias", "series_name") ?: title,
                author = meta.str("author"),
                coverUrl = meta.str("cover_image")?.let(web::absolute),
                tracks = tracks,
                startTrackId = meta.int("current_track_id"),
                startSeconds = meta.dbl("current_time") ?: 0.0,
                serverId = serverId,
                libraryId = libraryId,
            ),
            summary = meta.str("summary")?.takeIf { it != "등록된 설명이 없습니다." },
            percent = meta.dbl("total_progress_pct") ?: 0.0,
            completed = (meta.int("is_completed") ?: 0) != 0,
        )
    }

    /** Puts [b] on the 서재 (or refreshes it): title, author, cover; keeps the row's own place. */
    suspend fun add(context: Context, web: BookOasisWeb, b: AudioBook, percent: Double, completed: Boolean) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        val store = BookStore.get(app)
        val id = bookId(b.serverId, b.id)
        val old = db.books().get(id)
        val cover = store.coverFile(id)
        if (!cover.isFile && b.coverUrl != null) runCatching {
            val bytes = web.bytes(b.coverUrl)
            cover.parentFile?.mkdirs(); cover.writeBytes(bytes)
        }
        val serverPct = (percent / 100.0).toFloat().coerceIn(0f, 1f)
        val row = (old ?: BookEntity(id = id, title = b.title, addedAt = System.currentTimeMillis())).copy(
            title = old?.title ?: b.title,
            author = b.author ?: old?.author,
            format = FORMAT,
            opdsId = tag(b.serverId, b.id),
            hasCover = cover.isFile,
            pinned = true,                      // nothing to drop: the tracks stay on the server
            progress = maxOf(old?.progress ?: 0f, serverPct),
            finishedAt = old?.finishedAt ?: if (completed) System.currentTimeMillis() else null,
            spineIndex = old?.spineIndex ?: b.tracks.indexOfFirst { it.id == b.startTrackId }.coerceAtLeast(0),
        )
        db.books().insert(row)
        row
    }

    /** The listening place on the 서재 row (no row: nothing happens). */
    suspend fun savePlace(context: Context, b: AudioBook, trackIndex: Int, positionSec: Double, completed: Boolean) {
        if (b.serverId.isEmpty()) return
        val total = b.tracks.sumOf { it.durationSec }
        val before = b.tracks.take(trackIndex).sumOf { it.durationSec }
        val trackLen = b.tracks.getOrNull(trackIndex)?.durationSec ?: 0.0
        val overall = if (completed) 1f else if (total > 0) ((before + positionSec) / total).toFloat().coerceIn(0f, 1f) else 0f
        val inTrack = if (trackLen > 0) (positionSec / trackLen).toFloat().coerceIn(0f, 1f) else 0f
        val dao = AppDatabase.get(context.applicationContext).books()
        val id = bookId(b.serverId, b.id)
        dao.savePosition(id, trackIndex, inTrack, overall, System.currentTimeMillis())
        if (completed) dao.setFinished(id, System.currentTimeMillis())
    }

    /** Opens a 서재 row: the server's tracks and place, then plays. Throws when the server cannot be reached. */
    suspend fun play(context: Context, b: BookEntity) {
        val (sid, aid) = parse(b) ?: throw IllegalStateException("오디오북 정보가 없습니다")
        val server: Server = ServerSettings(context.applicationContext).get(sid)
            ?: throw IllegalStateException("이 오디오북의 서버 설정이 지워졌습니다")
        val web = BookOasisWeb.of(server)
        val d = detail(web, sid, aid, b.title, null)
        add(context, web, d.book, d.percent, d.completed)
        // the phone's place when it is further along than the server's (e.g. listened offline of the web)
        val local = AppDatabase.get(context.applicationContext).books().get(b.id)
        val serverIdx = d.book.tracks.indexOfFirst { it.id == d.book.startTrackId }
        val useLocal = local != null && serverIdx >= 0 && local.spineIndex > serverIdx
        withContext(Dispatchers.Main) {
            if (useLocal && local != null) {
                val t = d.book.tracks.getOrNull(local.spineIndex)
                AudioPlayback.open(context, web, d.book, local.spineIndex, (t?.durationSec ?: 0.0) * local.chapterFraction)
            } else AudioPlayback.open(context, web, d.book)
        }
    }
}
