package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.BuildConfig
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Reading-position sync through bookoasis' KOReader-compatible server (kosync):
 *   GET  <server>/kosync/users/auth                 x-auth-user, x-auth-key = MD5 of the sync password
 *   PUT  <server>/kosync/syncs/progress             {document, progress, percentage, device, device_id}
 *   GET  <server>/kosync/syncs/progress/<document>
 * A book is identified by KOReader's partial MD5 of its file; bookoasis computes the same value
 * when the file is downloaded over OPDS. The sync password is set on the bookoasis website
 * (account menu -> KOReader sync); only its MD5 is kept here, encrypted.
 */
object KoSync {
    const val MEDIA_TYPE = "application/vnd.koreader.v1+json"

    /** KOReader util.partialMD5: 1 KB at offsets 0, 1K, 4K, 16K, … 1G (1024 << 2i), stopping at the end. */
    fun partialMd5(file: File): String {
        val md5 = MessageDigest.getInstance("MD5")
        RandomAccessFile(file, "r").use { f ->
            val buf = ByteArray(1024)
            for (i in -1..10) {
                val offset = if (i < 0) 0L else 1024L shl (2 * i)
                if (offset >= f.length()) break
                f.seek(offset)
                val n = f.read(buf)
                if (n <= 0) break
                md5.update(buf, 0, n)
            }
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- account ----

    data class Account(val serverId: String, val baseUrl: String, val username: String, val key: String)

    private fun prefs(c: Context) = c.getSharedPreferences("kosync", Context.MODE_PRIVATE)
    private const val KEY_SECRET = "kosync_key"

    /** <scheme>://<host>[:port]/kosync of a catalog address. */
    fun baseUrl(server: Server): String? {
        val u = server.url.toHttpUrlOrNull() ?: return null
        val port = if (u.port == okhttp3.HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
        return "${u.scheme}://${u.host}$port/kosync"
    }

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)

    /** The account in use, or null when sync is off or not set up. */
    fun account(c: Context): Account? {
        val p = prefs(c)
        if (!p.getBoolean("enabled", false)) return null
        val server = ServerSettings(c).get(p.getString("serverId", null)) ?: return null
        val base = baseUrl(server) ?: return null
        val user = p.getString("username", null)?.takeIf { it.isNotBlank() } ?: return null
        val key = SecureStore(c).get(KEY_SECRET) ?: return null
        return Account(server.id, base, user, key)
    }

    /** What the settings screen shows (also while sync is off). */
    fun saved(c: Context): Triple<String?, String?, Boolean> {
        val p = prefs(c)
        return Triple(p.getString("serverId", null), p.getString("username", null), SecureStore(c).get(KEY_SECRET) != null)
    }

    fun save(c: Context, serverId: String, username: String, key: String) {
        SecureStore(c).put(KEY_SECRET, key)
        prefs(c).edit().putString("serverId", serverId).putString("username", username).putBoolean("enabled", true).apply()
    }

    fun disable(c: Context) = prefs(c).edit().putBoolean("enabled", false).apply()

    /** Stable id for this phone in the progress records. */
    fun deviceId(c: Context): String {
        val p = prefs(c)
        return p.getString("deviceId", null) ?: ("ogreader-" + java.util.UUID.randomUUID().toString())
            .also { p.edit().putString("deviceId", it).apply() }
    }

    fun deviceName(): String = "OG Reader (${android.os.Build.MODEL})"

    // ---- server ----

    private fun request(a: Account, path: String) = Request.Builder()
        .url(a.baseUrl + path)
        .header("x-auth-user", a.username)
        .header("x-auth-key", a.key)
        .header("Accept", MEDIA_TYPE)
        .header("User-Agent", "OGReader/${BuildConfig.VERSION_NAME}")

    /** Try a login; a readable reason on failure. */
    suspend fun checkAuth(a: Account): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(request(a, "/users/auth").get().build()).execute().use { r ->
                when {
                    r.isSuccessful -> Unit
                    r.code == 401 -> throw IOException("아이디 또는 동기화 비밀번호가 맞지 않습니다. 북오아시스 웹의 계정 메뉴 → KOReader 동기화에서 정한 비밀번호인지 확인하세요.")
                    r.code == 404 -> throw IOException("이 서버에는 진행 동기화 기능이 없습니다 (북오아시스 버전을 확인하세요).")
                    else -> throw IOException("서버 응답 ${r.code}")
                }
            }
        }
    }

    // ---- progress ----

    /** A position kept on the server (KOReader: progress = xpointer or page number, percentage 0..1). */
    data class Remote(
        val progress: String?,
        val percentage: Float?,
        val device: String?,
        val deviceId: String?,
        val timestamp: Long,      // epoch seconds, server clock
    ) {
        val fromOgReader get() = deviceId?.startsWith("ogreader-") == true
        val pageNumber get() = progress?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }

    /** The server's position for a book, or null when it has none (or cannot be reached). */
    suspend fun pull(a: Account, document: String): Remote? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(request(a, "/syncs/progress/$document").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = Http.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                if (o.isEmpty()) return@use null
                fun str(k: String) = o[k]?.jsonPrimitive?.takeIf { it.isString }?.content
                Remote(
                    progress = o["progress"]?.jsonPrimitive?.content?.takeIf { it != "null" },
                    percentage = o["percentage"]?.jsonPrimitive?.floatOrNull,
                    device = str("device"),
                    deviceId = str("device_id"),
                    timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        }.getOrNull()
    }

    suspend fun push(c: Context, a: Account, document: String, progress: String, percentage: Float): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("document", document)
                put("progress", progress)
                put("percentage", (percentage.coerceIn(0f, 1f) * 10000).toInt() / 10000.0)
                put("device", deviceName())
                put("device_id", deviceId(c))
            }.toString().toRequestBody("application/json".toMediaType())
            runCatching {
                Http.client.newCall(request(a, "/syncs/progress").put(body).build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Uploads that must outlive the screen that started them (closing a book). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushInBackground(c: Context, document: String, progress: String, percentage: Float) {
        val a = account(c) ?: return
        val app = c.applicationContext
        background.launch { push(app, a, document, progress, percentage) }
    }

    /**
     * Worth offering a jump: another device (or the bookoasis web viewer) stored it, after this
     * phone last read the book, and it is somewhere else.
     */
    fun isNewerElsewhere(c: Context, r: Remote, localLastReadAt: Long?, differs: Boolean): Boolean =
        r.deviceId != deviceId(c) && r.timestamp * 1000 > (localLastReadAt ?: 0L) + 5_000 && differs

    // ---- library: books read further on another device ----

    private val _ahead = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** bookId -> where another device is (0..1); shown on the library tile until the book is opened. */
    val ahead: StateFlow<Map<String, Float>> = _ahead
    private var lastLibraryCheck = 0L

    fun clearAhead(bookId: String) { _ahead.value = _ahead.value - bookId }

    /** Ask the server about the most recently read books (at most hourly, a few dozen requests). */
    suspend fun refreshLibrary(c: Context, books: List<com.oasisreader.app.data.db.BookEntity>) {
        val a = account(c) ?: return
        val now = System.currentTimeMillis()
        if (now - lastLibraryCheck < 60 * 60 * 1000L) return
        lastLibraryCheck = now
        val found = HashMap<String, Float>()
        books.filter { it.kosyncDoc != null }
            .sortedByDescending { it.lastReadAt ?: it.addedAt }
            .take(60)
            .forEach { b ->
                val r = pull(a, b.kosyncDoc!!) ?: return@forEach
                val pct = r.percentage ?: return@forEach
                if (isNewerElsewhere(c, r, b.lastReadAt, kotlin.math.abs(pct - b.progress) > 0.005f)) found[b.id] = pct
            }
        _ahead.value = found
    }

    // ---- book ids ----

    /** Give every book whose original file is on the phone its document id (books from before sync). */
    suspend fun backfillDocs(c: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(c)
        val store = BookStore.get(c)
        for (b in db.books().withoutKosyncDoc()) {
            val f = store.originalOf(b.id) ?: continue
            runCatching { partialMd5(f) }.getOrNull()?.let { db.books().setKosyncDoc(b.id, it) }
        }
    }
}
