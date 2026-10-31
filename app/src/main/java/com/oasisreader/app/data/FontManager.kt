package com.oasisreader.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class FontOption(
    val id: String,
    val label: String,
    val kind: Kind,
    val googleFamily: String? = null,   // null = system font, nothing to download
    val weights: String = "400;700",
    val fallback: String,
    val custom: Boolean = false,        // a font file the user added
) {
    enum class Kind { SERIF, SANS }

    /** CSS font-family stack for the reader page. */
    fun cssStack(): String =
        if (googleFamily == null) fallback else "'$googleFamily', $fallback"
}

/**
 * Reader fonts. Korean Google Fonts are split into ~100 unicode-range files per
 * weight; we fetch the CSS once, download every file, and rewrite the CSS to
 * local paths so the font also works offline (served to the WebView from
 * filesDir/fonts/<id>/font.css).
 */
class FontManager(context: Context) {

    val root = File(context.filesDir, "fonts").apply { mkdirs() }

    init { loadCustoms() }

    private val customList get() = File(root, "custom.json")

    private fun loadCustoms() {
        customs = runCatching {
            org.json.JSONArray(customList.readText()).let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    customOption(o.getString("id"), o.getString("label"))
                }
            }
        }.getOrDefault(emptyList()).filter { File(dir(it.id), "font.css").isFile }
    }

    private fun saveCustoms() {
        val a = org.json.JSONArray()
        customs.forEach { a.put(org.json.JSONObject().put("id", it.id).put("label", it.label)) }
        customList.writeText(a.toString())
    }

    /** Add a TTF / OTF / WOFF / WOFF2 file as a reader font. */
    suspend fun importFont(context: Context, uri: android.net.Uri): FontOption = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "font.ttf"
        val ext = name.substringAfterLast('.', "ttf").lowercase()
        if (ext !in setOf("ttf", "otf", "woff", "woff2")) throw IOException("TTF, OTF, WOFF 글꼴 파일만 추가할 수 있습니다")
        val id = "custom-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val d = dir(id).apply { mkdirs() }
        val file = File(d, "font.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("파일을 열 수 없습니다")
        if (file.length() < 1024 || file.length() > 60L * 1024 * 1024) { d.deleteRecursively(); throw IOException("글꼴 파일이 아닌 것 같습니다") }
        val format = when (ext) { "otf" -> "opentype"; "woff" -> "woff"; "woff2" -> "woff2"; else -> "truetype" }
        File(d, "font.css").writeText(
            "@font-face { font-family: '${familyOf(id)}'; src: url(font.$ext) format('$format'); font-weight: 100 900; font-style: normal; }\n"
        )
        val f = customOption(id, name.substringBeforeLast('.'))
        customs = customs + f
        saveCustoms()
        f
    }

    fun removeCustom(f: FontOption) {
        if (!f.custom) return
        dir(f.id).deleteRecursively()
        customs = customs.filterNot { it.id == f.id }
        saveCustoms()
    }

    fun dir(id: String) = File(root, id)
    fun isReady(f: FontOption) = f.googleFamily == null || File(dir(f.id), "font.css").isFile
    fun sizeBytes(f: FontOption): Long = dir(f.id).listFiles()?.sumOf { it.length() } ?: 0L
    fun delete(f: FontOption) = dir(f.id).deleteRecursively()

    /** Relative to the reader's asset host. */
    fun cssPath(f: FontOption) = "/fonts/${f.id}/font.css"

    suspend fun download(f: FontOption, onProgress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val family = f.googleFamily ?: return@withContext
        val url = "https://fonts.googleapis.com/css2?family=" +
            family.replace(' ', '+') + ":wght@" + f.weights + "&display=swap"
        val css = get(url).toString(Charsets.UTF_8)

        val urlRe = Regex("""url\((https://fonts\.gstatic\.com/[^)]+)\)""")
        val files = urlRe.findAll(css).map { it.groupValues[1] }.distinct().toList()
        if (files.isEmpty()) throw IOException("no font files in CSS")

        val tmp = File(root, f.id + ".tmp").apply { deleteRecursively(); mkdirs() }
        val names = files.withIndex().associate { (i, u) -> u to "${i}.${u.substringAfterLast('.', "woff2")}" }
        val done = AtomicInteger(0)
        val gate = Semaphore(8)
        try {
            coroutineScope {
                files.map { u ->
                    async {
                        gate.withPermit {
                            File(tmp, names.getValue(u)).writeBytes(get(u))
                            onProgress(done.incrementAndGet(), files.size)
                        }
                    }
                }.awaitAll()
            }
            val local = urlRe.replace(css) { m -> "url(${names.getValue(m.groupValues[1])})" }
            File(tmp, "font.css").writeText(local)
            dir(f.id).deleteRecursively()
            if (!tmp.renameTo(dir(f.id))) throw IOException("could not store font")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun get(url: String): ByteArray {
        // a desktop UA makes Google Fonts serve woff2
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.bytes() ?: throw IOException("empty body")
        }
    }

    companion object {
        private const val SERIF = "'Noto Serif CJK KR', 'Noto Serif KR', serif"
        private const val SANS = "'Noto Sans CJK KR', 'Noto Sans KR', sans-serif"

        val ALL = listOf(
            FontOption("sys-serif", "기본 명조 (시스템)", FontOption.Kind.SERIF, fallback = SERIF),
            FontOption("noto-serif-kr", "본명조 · Noto Serif KR", FontOption.Kind.SERIF, "Noto Serif KR", fallback = SERIF),
            FontOption("nanum-myeongjo", "나눔명조", FontOption.Kind.SERIF, "Nanum Myeongjo", fallback = SERIF),
            FontOption("gowun-batang", "고운바탕", FontOption.Kind.SERIF, "Gowun Batang", fallback = SERIF),
            FontOption("hahmlet", "함렛 · Hahmlet", FontOption.Kind.SERIF, "Hahmlet", fallback = SERIF),
            FontOption("song-myung", "송명", FontOption.Kind.SERIF, "Song Myung", weights = "400", fallback = SERIF),

            FontOption("sys-sans", "기본 고딕 (시스템)", FontOption.Kind.SANS, fallback = SANS),
            FontOption("noto-sans-kr", "본고딕 · Noto Sans KR", FontOption.Kind.SANS, "Noto Sans KR", fallback = SANS),
            FontOption("nanum-gothic", "나눔고딕", FontOption.Kind.SANS, "Nanum Gothic", fallback = SANS),
            FontOption("gothic-a1", "Gothic A1", FontOption.Kind.SANS, "Gothic A1", fallback = SANS),
            FontOption("ibm-plex-sans-kr", "IBM Plex Sans KR", FontOption.Kind.SANS, "IBM Plex Sans KR", fallback = SANS),
            FontOption("gowun-dodum", "고운돋움", FontOption.Kind.SANS, "Gowun Dodum", weights = "400", fallback = SANS),
        )

        /** Fonts the user added (loaded by the first FontManager). */
        @Volatile var customs: List<FontOption> = emptyList()

        private fun familyOf(id: String) = "OG $id"
        private fun customOption(id: String, label: String) =
            FontOption(id, label, FontOption.Kind.SANS, googleFamily = familyOf(id), fallback = SANS, custom = true)

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: customs.firstOrNull { it.id == id } ?: ALL.first()
    }
}
