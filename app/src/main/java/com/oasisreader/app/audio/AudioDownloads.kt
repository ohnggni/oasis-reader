package com.oasisreader.app.audio

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 오프라인 오디오북: the tracks of a 서재 audiobook copied to the phone (books/<id>/audio/),
 * with the track list beside them (audiobook.json), so it plays without the server. A streamed
 * audiobook keeps pinned = false (☁️ on the 서재, like 바로 읽기); a kept one pinned = true.
 */
object AudioDownloads {
    private const val DIR = "audio"
    private const val MANIFEST = "audiobook.json"

    /** Download progress (0..1) by 서재 book id, while it runs. */
    val progress = mutableStateMapOf<String, Float>()
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(context: Context, bookId: String) = File(BookStore.get(context.applicationContext).dir(bookId), DIR)
    private fun manifest(context: Context, bookId: String) = File(dir(context, bookId), MANIFEST)

    /** Is the whole audiobook on the phone? */
    fun isKept(context: Context, bookId: String): Boolean = manifest(context, bookId).isFile

    /** The book kept on the phone, if it is (tracks point at the files). */
    fun local(context: Context, bookId: String): AudioBook? = runCatching {
        val b = Http.json.decodeFromString(AudioBook.serializer(), manifest(context, bookId).readText())
        if (b.tracks.all { t -> t.localPath != null && File(t.localPath).isFile }) b else null
    }.getOrNull()

    /** [b] with the phone's files and cover where there are some (stream otherwise). */
    fun withLocal(context: Context, b: AudioBook): AudioBook {
        if (b.serverId.isEmpty()) return b
        val id = AudioLibraryLink.bookId(b.serverId, b.id)
        val cover = BookStore.get(context.applicationContext).coverFile(id).takeIf { it.isFile }?.path
        val kept = local(context, id)?.tracks?.associateBy { it.id }.orEmpty()
        return b.copy(
            artworkFile = cover ?: b.artworkFile,
            tracks = b.tracks.map { t -> kept[t.id]?.localPath?.let { p -> t.copy(localPath = p) } ?: t },
        )
    }

    fun start(context: Context, row: BookEntity, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        if (jobs[row.id]?.isActive == true) return
        progress[row.id] = 0f
        jobs[row.id] = scope.launch {
            val err = runCatching { download(app, row) }.exceptionOrNull()
            progress.remove(row.id)
            jobs.remove(row.id)
            withContext(Dispatchers.Main) {
                onDone(err?.let { it.message ?: it.javaClass.simpleName })
            }
        }
    }

    fun cancel(bookId: String) { jobs[bookId]?.cancel(); jobs.remove(bookId); progress.remove(bookId) }

    private suspend fun download(app: Context, row: BookEntity) {
        val (sid, aid) = AudioLibraryLink.parse(row) ?: throw IllegalStateException("오디오북 정보가 없습니다")
        val server = ServerSettings(app).get(sid) ?: throw IllegalStateException("이 오디오북의 서버 설정이 지워졌습니다")
        val web = BookOasisWeb.of(server)
        val d = AudioLibraryLink.detail(web, sid, aid, row.title, null)
        web.ensureLogin()
        val folder = dir(app, row.id).apply { mkdirs() }
        val total = d.book.tracks.size.coerceAtLeast(1)
        val kept = ArrayList<AudioTrack>()
        d.book.tracks.forEachIndexed { i, t ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val f = File(folder, "${t.id}.audio")
            if (!f.isFile || f.length() == 0L) {
                val tmp = File(folder, "${t.id}.part")
                val url = web.absolute("/api/media/audiobooks/${d.book.id}/tracks/${t.id}/stream")
                web.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("트랙 ${i + 1}을(를) 받지 못했습니다 (${r.code})")
                    val body = r.body ?: throw IllegalStateException("빈 응답")
                    val len = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            while (true) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); got += n
                                if (len > 0) progress[row.id] = ((i + got.toFloat() / len) / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                tmp.renameTo(f)
            }
            kept += t.copy(localPath = f.path)
            progress[row.id] = (i + 1f) / total
        }
        val book = d.book.copy(tracks = kept)
        manifest(app, row.id).writeText(Http.json.encodeToString(AudioBook.serializer(), book))
        val bytes = folder.walk().filter { it.isFile }.sumOf { it.length() }
        val dao = AppDatabase.get(app).books()
        dao.get(row.id)?.let { dao.insert(it.copy(pinned = true, fileBytes = bytes)) }
    }

    /** Back to streaming: the phone's copy goes, the place and the 서재 entry stay. */
    suspend fun remove(context: Context, row: BookEntity) = withContext(Dispatchers.IO) {
        cancel(row.id)
        dir(context, row.id).deleteRecursively()
        val dao = AppDatabase.get(context.applicationContext).books()
        dao.get(row.id)?.let { dao.insert(it.copy(pinned = false, fileBytes = 0)) }
    }
}
