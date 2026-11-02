package com.oasisreader.app.meta

import com.oasisreader.app.data.Http
import com.oasisreader.app.data.HttpException
import com.oasisreader.app.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

@Serializable
data class MetaLibrary(val id: Int, val name: String)

@Serializable
data class MetaSeries(
    @SerialName("library_id") val libraryId: Int,
    @SerialName("series_name") val seriesName: String,
    @SerialName("book_count") val bookCount: Int = 0,
    @SerialName("latest_mtime") val latestMtime: String? = null,
    @SerialName("first_mtime") val firstMtime: String? = null,
    @SerialName("first_book_id") val firstBookId: Long? = null,
    @SerialName("cover_url") val coverUrl: String? = null,   // optional: bookoasis thumbnail path of the first book
)

@Serializable
data class MetaBook(
    val id: Long,
    val title: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    val author: String? = null,
    @SerialName("library_id") val libraryId: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_format") val fileFormat: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("file_mtime") val fileMtime: String? = null,
    @SerialName("download_path") val downloadPath: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,   // optional: bookoasis thumbnail path
    val summary: String? = null,                              // only from /books/{id}
    @SerialName("summary_trust") val summaryTrust: String? = null,   // "ok" | "suspect" | null
) {
    val name: String get() = displayTitle?.ifBlank { null } ?: fileName?.substringBeforeLast('.') ?: title ?: "#$id"
    val isComic: Boolean get() = fileFormat?.lowercase() in setOf("cbz", "zip", "cbr")
    /**
     * Real page count, only for page-based files (comics, PDF). For EPUB/TXT bookoasis keeps
     * an internal value in total_pages (often 100 once the web viewer has opened the book).
     */
    val pageCount: Int? get() = totalPages.takeIf { it > 0 && (isComic || fileFormat?.lowercase() == "pdf") }
    val readable: Boolean get() = fileFormat?.lowercase() in setOf("epub", "cbz", "zip", "txt", "pdf")
}

@Serializable
data class MetaPage<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    val limit: Int = 100,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
private data class MetaLibraries(val items: List<MetaLibrary> = emptyList())

/**
 * oasis-meta: read-only sidecar next to bookoasis that knows series names, clean titles and
 * real file dates (the OPDS feed has none of these). Same Basic login as the catalog.
 * Files are still downloaded from bookoasis itself ([server] url host + download_path).
 */
class MetaApi(val server: Server, val db: String = server.metaDb) {

    /** progress / dedupe key of a book: ids are per database, so the db is part of it */
    fun key(b: MetaBook) = "meta:$db:${b.id}"

    private val base = server.metaUrl.trimEnd('/')

    private fun url(path: String, params: Map<String, Any?>): String {
        val b = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw IOException("메타 API 주소가 올바르지 않습니다")
        b.addQueryParameter("db", db)
        params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    /** Responses are kept 10 minutes on disk: coming back to a list is instant. */
    private suspend fun get(u: String): String = withContext(Dispatchers.IO) {
        val f = cacheDir?.let { java.io.File(it, sha1(server.username + "|" + u) + ".json") }
        if (f != null && f.isFile && System.currentTimeMillis() - f.lastModified() < CACHE_MS) {
            runCatching { f.readText() }.getOrNull()?.let { return@withContext it }
        }
        val body = fetch(u)
        if (f != null) runCatching { f.parentFile?.mkdirs(); f.writeText(body) }
        body
    }

    private fun fetch(u: String): String {
        val req = Request.Builder().url(u).header("Accept", "application/json")
        if (server.username.isNotBlank()) req.header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8))
        Http.client.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, when (r.code) {
                401 -> "메타 API 로그인 실패 (401)"
                403 -> "메타 API 접근 거부 (403)"
                404 -> "메타 API에서 찾을 수 없습니다 (404)"
                else -> "메타 API 오류 (${r.code})"
            })
            return r.body?.string() ?: throw IOException("빈 응답")
        }
    }

    suspend fun libraries(): List<MetaLibrary> =
        Http.json.decodeFromString(MetaLibraries.serializer(), get(url("/libraries", emptyMap()))).items

    suspend fun series(libraryId: String?, q: String?, sort: String, page: Int, limit: Int = 36): MetaPage<MetaSeries> =
        Http.json.decodeFromString(
            MetaPage.serializer(MetaSeries.serializer()),
            get(url("/series", mapOf("library_id" to libraryId, "q" to q, "sort" to sort, "page" to page, "limit" to limit))),
        )

    suspend fun books(libraryId: String?, series: String?, q: String?, sort: String, page: Int, limit: Int = 500): MetaPage<MetaBook> =
        Http.json.decodeFromString(
            MetaPage.serializer(MetaBook.serializer()),
            get(url("/books", mapOf("library_id" to libraryId, "series" to series, "q" to q, "sort" to sort, "page" to page, "limit" to limit))),
        )

    suspend fun book(id: Long): MetaBook =
        Http.json.decodeFromString(MetaBook.serializer(), get(url("/books/$id", emptyMap())))

    /** Absolute bookoasis download URL for a book. */
    fun downloadUrl(b: MetaBook): String? {
        val p = b.downloadPath ?: return null
        val host = server.url.toHttpUrlOrNull() ?: return null
        return host.resolve(p)?.toString()
    }

    /** A cover path given by oasis-meta ("/covers/..."), made absolute against bookoasis. */
    fun absolute(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val host = server.url.toHttpUrlOrNull() ?: return null
        return host.resolve(path)?.toString()
    }

    companion object {
        @Volatile var cacheDir: java.io.File? = null
        private const val CACHE_MS = 10 * 60_000L

        private fun sha1(s: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** bookoasis download links look like .../opds/download/<db>/<id> */
        private val DOWNLOAD = Regex("""/opds/download/([a-z]+)/(\d+)""")
        fun idFromDownload(url: String): Pair<String, Long>? =
            DOWNLOAD.find(url)?.let { it.groupValues[1] to it.groupValues[2].toLong() }
    }
}
