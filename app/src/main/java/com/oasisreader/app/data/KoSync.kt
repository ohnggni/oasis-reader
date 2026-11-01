package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.BuildConfig
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
