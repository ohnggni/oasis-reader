package com.oasisreader.app.remote

import android.util.Xml
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.HttpException
import com.oasisreader.app.data.Server
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** WebDAV over OkHttp: PROPFIND (Depth 1) to list, GET with Range to receive. Basic login. */
class WebDavFs(private val server: Server) : RemoteFs {
    private val base: HttpUrl = server.url.trim().toHttpUrlOrNull()
        ?: throw IOException("WebDAV 주소는 https:// 또는 http:// 로 시작해야 합니다")
    override val root: String = Remote.norm(base.pathSegments.joinToString("/"))
    private val auth = if (server.username.isBlank()) null else Credentials.basic(server.username, server.password, Charsets.UTF_8)

    private fun urlOf(path: String, dir: Boolean): HttpUrl {
        val b = base.newBuilder().encodedPath("/")
        Remote.norm(path).split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private fun req(url: HttpUrl) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    override fun list(path: String): List<RemoteFile> {
        val dir = Remote.norm(path)
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val r = req(urlOf(dir, true))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        Http.client.newCall(r).execute().use { res ->
            if (res.code == 401) throw HttpException(401, "로그인 실패 (아이디·비밀번호 확인)")
            if (res.code == 404) throw HttpException(404, "폴더가 없습니다: $dir")
            if (res.code != 207 && !res.isSuccessful) throw HttpException(res.code, "WebDAV 오류 ${res.code}")
            val xml = res.body?.byteStream() ?: throw IOException("빈 응답")
            return parse(xml).filter { Remote.norm(it.path) != dir }
        }
    }

    private fun parse(input: java.io.InputStream): List<RemoteFile> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        val out = ArrayList<RemoteFile>()
        var href: String? = null
        var isDir = false
        var size = -1L
        var mtime = 0L
        var text = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; isDir = false; size = -1; mtime = 0 }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: -1
                    "getlastmodified" -> mtime = date(text.toString().trim())
                    "response" -> href?.let { h ->
                        val u = base.resolve(h)
                        if (u != null) {
                            val path = Remote.norm(u.pathSegments.joinToString("/"))
                            out += RemoteFile(Remote.nameOf(path), path, isDir, size, mtime)
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun date(s: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    }.getOrDefault(0L)

    override fun download(path: String, part: File, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean {
        val have = if (part.isFile) part.length() else 0L
        val r = req(urlOf(path, false)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        Http.client.newCall(r).execute().use { res ->
            if (res.code == 416 && have > 0) { part.delete(); return download(path, part, onProgress, stop) }
            if (res.code == 401) throw HttpException(401, "로그인 실패 (아이디·비밀번호 확인)")
            if (!res.isSuccessful) throw HttpException(res.code, "받기 실패 (${res.code})")
            val body = res.body ?: throw IOException("빈 응답")
            val append = have > 0 && res.code == 206
            if (!append && have > 0) part.delete()
            val start = if (append) have else 0L
            val total = body.contentLength().let { if (it > 0) it + start else -1L }
            return body.byteStream().use { Remote.copyResume(it, part, start, total, onProgress, stop) }
        }
    }

    override fun close() {}
}
