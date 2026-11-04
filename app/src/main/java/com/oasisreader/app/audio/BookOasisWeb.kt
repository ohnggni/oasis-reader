package com.oasisreader.app.audio

import com.oasisreader.app.data.Http
import com.oasisreader.app.data.HttpException
import com.oasisreader.app.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The bookoasis web API (what its own web pages use), for things OPDS does not carry:
 * audiobooks (a folder of tracks) and their listening position. It takes the web login's
 * session cookie, so the client logs in with the catalog's saved account once and again
 * whenever the session has expired.
 */
class BookOasisWeb private constructor(val server: Server) {

    /** Site root: the OPDS address without its /opds… path. */
    val base: String = run {
        val u = server.url.toHttpUrlOrNull() ?: throw IOException("서버 주소가 올바르지 않습니다")
        val path = u.encodedPath.substringBefore("/opds").trimEnd('/')
        u.newBuilder().encodedPath(if (path.isEmpty()) "/" else "$path/").query(null).build().toString().trimEnd('/')
    }

    private val cookies = ConcurrentHashMap<String, Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookies.forEach { this@BookOasisWeb.cookies[it.name] = it } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }

    /** For the player too: the stream requests need the same cookie. */
    val client: OkHttpClient = Http.client.newBuilder().cookieJar(jar).build()
    private val loginLock = Mutex()
    @Volatile private var loggedIn = false

    private suspend fun login() = loginLock.withLock {
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("username", server.username); put("password", server.password); put("remember_me", true)
            }.toString().toRequestBody(JSON)
            client.newCall(Request.Builder().url("$base/login").post(body).header("Accept", "application/json").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val ok = r.isSuccessful && !text.contains("\"success\": false") && !text.contains("\"success\":false")
                if (!ok) throw HttpException(r.code, if (r.code == 401 || r.code == 400 || text.contains("success")) "북오아시스 로그인 실패: 아이디·비밀번호를 확인하세요" else "북오아시스 로그인 오류 (${r.code})")
                loggedIn = true
            }
        }
    }

    /** Makes sure there is a session (logs in once); call before handing [client] to the player. */
    suspend fun ensureLogin() { if (!loggedIn) login() }

    private fun url(path: String, params: Map<String, Any?>): String {
        val b = ("$base$path").toHttpUrlOrNull()?.newBuilder() ?: throw IOException("주소가 올바르지 않습니다: $path")
        params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private suspend fun call(build: () -> Request): String = withContext(Dispatchers.IO) {
        ensureLogin()
        repeat(2) { attempt ->
            client.newCall(build()).execute().use { r ->
                if (r.code == 401 && attempt == 0) { loggedIn = false; login(); return@use }
                if (!r.isSuccessful) throw HttpException(r.code, when (r.code) {
                    401 -> "북오아시스 로그인이 필요합니다"
                    403 -> "이 계정으로는 볼 수 없습니다 (403)"
                    404 -> "찾을 수 없습니다 (404)"
                    else -> "북오아시스 오류 (${r.code})"
                })
                return@withContext r.body?.string() ?: throw IOException("빈 응답")
            }
        }
        throw IOException("북오아시스 로그인이 필요합니다")
    }

    suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val text = call { Request.Builder().url(url(path, params)).header("Accept", "application/json").build() }
        val o = Http.json.parseToJsonElement(text).jsonObject
        if (o["success"]?.toString() == "false") throw IOException(o["error"]?.toString()?.trim('"') ?: "북오아시스 오류")
        return o
    }

    suspend fun post(path: String, body: JsonElement): JsonObject {
        val text = call {
            Request.Builder().url(url(path, emptyMap())).post(body.toString().toRequestBody(JSON)).header("Accept", "application/json").build()
        }
        return runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    fun absolute(path: String): String = if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val clients = ConcurrentHashMap<String, BookOasisWeb>()

        /** One client (one session) per server and account. */
        fun of(server: Server): BookOasisWeb =
            clients.getOrPut(server.id + "|" + server.url + "|" + server.username) { BookOasisWeb(server) }
    }
}
