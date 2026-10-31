package com.oasisreader.app.opds

import android.util.Xml
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.HttpException
import com.oasisreader.app.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

data class Acquisition(val href: String, val type: String)

data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val navHref: String? = null,          // a sub-catalog (navigation entry)
    val acquisitions: List<Acquisition> = emptyList(),
    val updated: String? = null,          // ISO-8601 (Atom <updated>/<published>, OPDS2 modified/published)
) {
    /** The download we can read: EPUB first, else a comic archive (CBZ/ZIP). */
    val readable: Acquisition? get() =
        acquisitions.firstOrNull { it.type.startsWith("application/epub+zip") }
            ?: acquisitions.firstOrNull { a -> COMIC_TYPES.any { a.type.startsWith(it) } }
            ?: acquisitions.firstOrNull { it.type.startsWith("application/pdf") }
            ?: acquisitions.firstOrNull { it.type.startsWith("text/plain") }
            ?: acquisitions.firstOrNull { a ->
                val path = a.href.substringBefore('?').lowercase()
                path.endsWith(".cbz") || path.endsWith(".zip") || path.endsWith(".epub") || path.endsWith(".txt") || path.endsWith(".pdf")
            }

    val isComic: Boolean get() = readable?.let { a ->
        val path = a.href.substringBefore('?').lowercase()
        COMIC_TYPES.any { a.type.startsWith(it) } || path.endsWith(".cbz") || path.endsWith(".zip")
    } == true

    companion object {
        val COMIC_TYPES = listOf(
            "application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/x-zip-compressed",
        )
    }
    val isBook get() = acquisitions.isNotEmpty()
}

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val next: String? = null,
    val searchHref: String? = null,       // either a template with {searchTerms} or an OpenSearch description
)

/**
 * Minimal OPDS client: OPDS 1.x (Atom XML) and OPDS 2.0 (JSON) catalogs,
 * HTTP Basic auth. OkHttp drops the Authorization header on redirects to
 * another host, so the password only ever goes to the catalog's own server.
 */
class OpdsClient(private val server: Server) {

    private fun request(url: String): Request {
        val b = Request.Builder().url(url)
            .header("Accept", "application/atom+xml;profile=opds-catalog, application/opds+json, application/atom+xml, application/json;q=0.8, */*;q=0.5")
        val home = server.url.toHttpUrlOrNull()
        val target = url.toHttpUrlOrNull()
        if (home != null && target != null && home.host == target.host && server.username.isNotBlank()) {
            b.header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8))
        }
        return b.build()
    }

    private fun check(r: Response) {
        if (!r.isSuccessful) {
            throw HttpException(r.code, when (r.code) {
                401 -> "로그인 정보가 맞지 않습니다 (401)"
                403 -> "접근이 거부되었습니다 (403)"
                404 -> "주소를 찾을 수 없습니다 (404)"
                else -> "서버 오류 (${r.code})"
            })
        }
    }

    suspend fun fetch(url: String): OpdsFeed = withContext(Dispatchers.IO) {
        Http.client.newCall(request(url)).execute().use { r ->
            check(r)
            val finalUrl = r.request.url.toString()
            val type = r.header("Content-Type").orEmpty()
            val body = r.body ?: throw IOException("빈 응답")
            val bytes = body.bytes()
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).trimStart()
            if (type.contains("json") || head.startsWith("{")) {
                parseJson(finalUrl, String(bytes, Charsets.UTF_8))
            } else {
                parseAtom(finalUrl, bytes.inputStream())
            }
        }
    }

    /** Resolve the catalog's search link to a query URL. */
    suspend fun searchUrl(feed: OpdsFeed, query: String): String? = withContext(Dispatchers.IO) {
        val href = feed.searchHref ?: return@withContext null
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        if (href.contains("{searchTerms}")) return@withContext fillTemplate(href, q)
        // OpenSearch description document: find the Atom (or any) URL template
        Http.client.newCall(request(href)).execute().use { r ->
            check(r)
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(r.body!!.byteStream(), null)
            var best: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Url") {
                    val t = p.getAttributeValue(null, "template") ?: continue
                    val ty = p.getAttributeValue(null, "type").orEmpty()
                    if (best == null || ty.contains("atom")) best = t
                }
            }
            best?.let { fillTemplate(resolve(r.request.url.toString(), it), q) }
        }
    }

    private fun fillTemplate(t: String, q: String) =
        t.replace("{searchTerms}", q)
            .replace(Regex("\\{[a-zA-Z:]+\\?\\}"), "")   // optional params we don't use

    /** Download to [dest], reporting bytes done / total (-1 if unknown). */
    /** Download to [dest]; returns the server's file name (Content-Disposition), if any. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): String? = withContext(Dispatchers.IO) {
        Http.client.newCall(request(url)).execute().use { r ->
            check(r)
            val fileName = r.header("Content-Disposition")?.let { fileNameFrom(it) }
            val body = r.body ?: throw IOException("빈 응답")
            val total = body.contentLength()
            if (total > MAX_BOOK_BYTES) throw IOException("파일이 너무 큽니다 (${total / 1048576} MB)")
            // the file is unpacked / kept next to the download: ask for about twice its size
            val free = dest.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (total > 0 && free < total * 2 + 200L * 1024 * 1024)
                throw IOException("폰 저장공간이 부족합니다 (약 ${(total * 2) / 1048576} MB 필요, ${free / 1048576} MB 남음)")
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_BOOK_BYTES) throw IOException("파일이 너무 큽니다")
                        onProgress(done, total)
                    }
                }
            }
            fileName
        }
    }

    /** Thumbnails (they may need the same login). */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        Http.client.newCall(request(url)).execute().use { r ->
            check(r)
            r.body?.bytes() ?: throw IOException("빈 응답")
        }
    }

    // ── OPDS 1.x (Atom) ──────────────────────────────────────────

    private fun parseAtom(base: String, input: InputStream): OpdsFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)

        var feedTitle = ""
        var next: String? = null
        var search: String? = null
        val entries = ArrayList<OpdsEntry>()

        // current entry
        var inEntry = false
        var inAuthor = false
        var id = ""; var title = ""; var author: String? = null; var summary: String? = null
        var updated: String? = null; var published: String? = null
        var thumb: String? = null; var image: String? = null; var nav: String? = null
        var acq = ArrayList<Acquisition>()

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> {
                        inEntry = true
                        id = ""; title = ""; author = null; summary = null; updated = null; published = null
                        thumb = null; image = null; nav = null; acq = ArrayList()
                    }
                    "author" -> inAuthor = true
                    "name" -> if (inAuthor && inEntry) {
                        val n = runCatching { p.nextText() }.getOrNull().orEmpty().trim()
                        if (n.isNotEmpty()) author = if (author == null) n else "$author, $n"
                    }
                    "title" -> {
                        val t = runCatching { p.nextText() }.getOrNull().orEmpty().trim()
                        if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t
                    }
                    "id" -> if (inEntry) id = runCatching { p.nextText() }.getOrNull().orEmpty().trim()
                    // date added: <published> when present, else <updated> (dc:issued is the publication year, not used)
                    "published" -> if (inEntry) runCatching { p.nextText() }.getOrNull()?.trim()?.ifBlank { null }?.let { published = it }
                    "updated" -> if (inEntry && updated == null) updated = runCatching { p.nextText() }.getOrNull()?.trim()?.ifBlank { null }
                    "summary", "content" -> if (inEntry && summary == null) {
                        summary = runCatching { p.nextText() }.getOrNull()?.let { stripTags(it) }?.ifBlank { null }
                    }
                    "link" -> {
                        val rel = p.getAttributeValue(null, "rel").orEmpty()
                        val href = p.getAttributeValue(null, "href") ?: ""
                        val type = p.getAttributeValue(null, "type").orEmpty()
                        if (href.isNotEmpty()) {
                            val abs = resolve(base, href)
                            if (inEntry) {
                                when {
                                    rel.startsWith("http://opds-spec.org/acquisition") -> acq.add(Acquisition(abs, type))
                                    rel == "http://opds-spec.org/image/thumbnail" || rel == "x-stanza-cover-image-thumbnail" -> thumb = abs
                                    rel == "http://opds-spec.org/image" || rel == "x-stanza-cover-image" -> image = abs
                                    type.contains("atom+xml") || type.contains("opds+json") -> if (nav == null) nav = abs
                                }
                            } else {
                                when (rel) {
                                    "next" -> next = abs
                                    "search" -> if (search == null || type.contains("atom")) search = abs
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        entries.add(
                            OpdsEntry(
                                id = id.ifEmpty { acq.firstOrNull()?.href ?: nav ?: title },
                                title = title.ifEmpty { "(제목 없음)" },
                                author = author,
                                summary = summary,
                                updated = published ?: updated,
                                thumb = thumb ?: image,
                                navHref = if (acq.isEmpty()) nav else null,
                                acquisitions = acq,
                            )
                        )
                    }
                }
            }
        }
        return OpdsFeed(base, feedTitle.ifEmpty { "북오아시스" }, entries, next, search)
    }

    // ── OPDS 2.0 (JSON) ──────────────────────────────────────────

    private fun parseJson(base: String, text: String): OpdsFeed {
        val root = Http.json.parseToJsonElement(text) as? JsonObject ?: throw IOException("OPDS JSON 형식이 아닙니다")
        val title = root.obj("metadata")?.str("title") ?: "북오아시스"
        var next: String? = null
        var search: String? = null
        root.arr("links")?.forEach { l ->
            val o = l as? JsonObject ?: return@forEach
            val rel = o.rels()
            val href = o.str("href") ?: return@forEach
            if ("next" in rel) next = resolve(base, href)
            if ("search" in rel) search = resolve(base, href)
        }
        val entries = ArrayList<OpdsEntry>()
        fun collect(o: JsonObject) {
            o.arr("navigation")?.forEach { n ->
                val no = n as? JsonObject ?: return@forEach
                val href = no.str("href") ?: return@forEach
                entries.add(OpdsEntry(id = href, title = no.str("title") ?: href, navHref = resolve(base, href)))
            }
            o.arr("publications")?.forEach { p -> (p as? JsonObject)?.let { entries.add(publication(base, it)) } }
            o.arr("groups")?.forEach { g -> (g as? JsonObject)?.let { collect(it) } }
        }
        collect(root)
        return OpdsFeed(base, title, entries, next, search)
    }

    private fun publication(base: String, o: JsonObject): OpdsEntry {
        val meta = o.obj("metadata")
        val acq = ArrayList<Acquisition>()
        o.arr("links")?.forEach { l ->
            val lo = l as? JsonObject ?: return@forEach
            val href = lo.str("href") ?: return@forEach
            if (lo.rels().any { it.startsWith("http://opds-spec.org/acquisition") }) {
                acq.add(Acquisition(resolve(base, href), lo.str("type").orEmpty()))
            }
        }
        val thumb = o.arr("images")?.firstNotNullOfOrNull { (it as? JsonObject)?.str("href") }?.let { resolve(base, it) }
        return OpdsEntry(
            id = meta?.str("identifier") ?: acq.firstOrNull()?.href ?: (meta?.str("title") ?: ""),
            title = meta?.str("title") ?: "(제목 없음)",
            author = meta?.get("author")?.let { names(it) },
            summary = meta?.str("description")?.let { stripTags(it) },
            updated = meta?.str("modified") ?: meta?.str("published"),
            thumb = thumb,
            acquisitions = acq,
        )
    }

    private fun names(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.content
        is JsonObject -> e.str("name")
        is JsonArray -> e.mapNotNull { names(it) }.joinToString(", ").ifBlank { null }
        else -> null
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonObject.rels(): List<String> = when (val r = this["rel"]) {
        is JsonPrimitive -> listOf(r.content)
        is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

    companion object {
        /** filename*=UTF-8''%EC%95%84.cbz  or  filename="name.cbz" */
        fun fileNameFrom(cd: String): String? {
            Regex("""(?i)filename\*\s*=\s*([^']*)'[^']*'([^;]+)""").find(cd)?.let { m ->
                val cs = m.groupValues[1].ifBlank { "UTF-8" }
                return runCatching { java.net.URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), cs) }.getOrNull()
            }
            Regex("""(?i)filename\s*=\s*"([^"]+)"""").find(cd)?.let { return it.groupValues[1] }
            Regex("""(?i)filename\s*=\s*([^;]+)""").find(cd)?.let { return it.groupValues[1].trim() }
            return null
        }

        /** Titles like "[만화] 1", "3권", "12" carry no series name. */
        private val GENERIC = Regex("""^\s*(\[[^\]]*\])?\s*(\d+)\s*(권|화|vol\.?)?\s*$""", RegexOption.IGNORE_CASE)
        fun isGenericTitle(t: String) = GENERIC.matches(t)

        /** Placeholder titles some EPUB makers leave in the metadata ("unknown title", "Untitled" …). */
        private val JUNK = Regex("""^\s*(unknown(\s+title)?|untitled(\s+document)?|no\s*title|title|book|document|제목\s*없음|무제)?\s*$""", RegexOption.IGNORE_CASE)
        fun isJunkTitle(t: String) = JUNK.matches(t)

        /** A title worth replacing with the catalog's / file's name. */
        fun isPoorTitle(t: String) = isGenericTitle(t) || isJunkTitle(t)
        fun numberIn(t: String): String? = GENERIC.find(t)?.groupValues?.get(2)

        const val MAX_BOOK_BYTES = 4L * 1024 * 1024 * 1024   // magazine PDFs run to several hundred MB

        fun resolve(base: String, href: String): String =
            base.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href

        fun stripTags(s: String): String =
            s.replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ").trim()
    }
}
