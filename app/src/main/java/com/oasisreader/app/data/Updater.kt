package com.oasisreader.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.oasisreader.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A GitHub release of the app, as far as updating needs it. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkBytes: Long,
)

/**
 * App updates from the public GitHub releases: no server of our own and no push service.
 * CI tags every build v0.1.<run number> and that run number is also the versionCode, so a
 * bigger tag number means a newer build. The APK is signed with the same fixed key, so it
 * installs over the current one.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/ohnggni/oasis-reader/releases/latest"
    private const val APK_NAME = "OasisReader.apk"
    // how often the library may ask GitHub (unauthenticated: 60 requests an hour per address)
    private const val INTERVAL = 60L * 60 * 1000

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    /** "v0.1.66" -> 66 (the last number is the build number). */
    fun versionCodeOf(tag: String): Int? = Regex("""(\d+)\s*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** The newest release, or null when GitHub has none with an APK. Throws on network errors. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OGReader/${BuildConfig.VERSION_NAME}")
            .build()
        val text = Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "GitHub ${r.code}")
            r.body?.string() ?: throw IOException("빈 응답")
        }
        val rel = Http.json.decodeFromString(GhRelease.serializer(), text)
        if (rel.draft || rel.prerelease) return@withContext null
        val apk = rel.assets.firstOrNull { it.name == APK_NAME } ?: rel.assets.firstOrNull { it.name.endsWith(".apk") }
            ?: return@withContext null
        val code = versionCodeOf(rel.tag) ?: return@withContext null
        AppRelease(code, rel.tag.removePrefix("v"), rel.body.orEmpty().trim(), apk.url, apk.size)
    }

    fun isNewer(r: AppRelease?): Boolean = r != null && r.versionCode > BuildConfig.VERSION_CODE

    // ---- automatic check: whenever the library opens, at most once an hour ----

    private fun prefs(context: Context) = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Newer release for the library banner, or null (asks GitHub at most once an hour; a skipped version stays hidden). */
    suspend fun dailyCheck(context: Context): AppRelease? {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastCheck", 0) < INTERVAL) {
            // between checks, keep showing what the last check found (until it is installed or skipped)
            val code = p.getInt("foundCode", 0)
            if (code <= BuildConfig.VERSION_CODE || code == p.getInt("skipped", 0)) return null
            return AppRelease(code, p.getString("foundName", null) ?: "0.1.$code", p.getString("foundNotes", null).orEmpty(),
                p.getString("foundUrl", null) ?: return null, p.getLong("foundBytes", 0))
        }
        val r = runCatching { latest() }.getOrNull()
        p.edit().putLong("lastCheck", now).apply()
        if (!isNewer(r)) return null
        r!!
        p.edit().putInt("foundCode", r.versionCode).putString("foundName", r.versionName)
            .putString("foundNotes", r.notes).putString("foundUrl", r.apkUrl).putLong("foundBytes", r.apkBytes).apply()
        return r.takeIf { it.versionCode != p.getInt("skipped", 0) }
    }

    /** "나중에": hide the banner for this version (a later one shows again). */
    fun skip(context: Context, r: AppRelease) = prefs(context).edit().putInt("skipped", r.versionCode).apply()

    // ---- download + install ----

    private fun dir(context: Context) = File(context.cacheDir, "updates")

    /** Download the APK into the cache, reporting 0..1 (or -1 when the size is unknown). */
    suspend fun download(context: Context, r: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val d = dir(context).apply { deleteRecursively(); mkdirs() }
        val out = File(d, "OGReader-${r.versionCode}.apk")
        val part = File(d, out.name + ".part")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "OGReader/${BuildConfig.VERSION_NAME}").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "다운로드 실패 (${resp.code})")
            val body = resp.body ?: throw IOException("빈 응답")
            val total = body.contentLength().takeIf { it > 0 } ?: r.apkBytes
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(out)) throw IOException("파일을 저장하지 못했습니다")
        onProgress(1f)
        out
    }

    /** May this app open the package installer? (Android asks the user once per app.) */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows installs from this app. */
    fun allowInstallIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hand the APK to the system installer (it asks the user to confirm the update). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
