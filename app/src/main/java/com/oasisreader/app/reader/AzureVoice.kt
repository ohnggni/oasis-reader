package com.oasisreader.app.reader

import android.content.Context
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Azure 음성 (Microsoft Azure AI Speech, the official text-to-speech API) with the reader's own
 * key: a free (F0) Speech resource reads up to 500,000 characters a month and is then refused
 * (HTTP 429) rather than charged. The same natural voices as Edge's 소리 내어 읽기 (선히, 인준 …).
 *
 * The key lives in [SecureStore] (encrypted, not in backups, never logged); the region and the
 * voice in prefs `read_aloud`. Each sentence is asked for alone and kept as an MP3 in the cache, so
 * a sentence heard again costs nothing. Speed and pitch are applied by the player, not by SSML:
 * SSML prosody tags count as billed characters.
 */
object AzureVoice {
    private const val KEY = "azure_tts_key"
    private const val PREFS = ReadAloud.PREFS
    const val DEFAULT_VOICE = "ko-KR-SunHiNeural"
    const val DEFAULT_REGION = "koreacentral"
    const val FREE_CHARS = 500_000
    private const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val CACHE_MAX = TtsTuning.AZURE_CACHE_MAX

    /** Regions offered in the setup screen (Korea Central first; others for those who made it elsewhere). */
    val REGIONS = listOf(
        "koreacentral" to "한국 중부", "japaneast" to "일본 동부", "southeastasia" to "동남아시아",
        "eastasia" to "동아시아", "eastus" to "미국 동부", "westus2" to "미국 서부 2", "westeurope" to "서유럽",
    )

    // ── settings ──

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(c: Context): String? = SecureStore(c.applicationContext).get(KEY)?.takeIf { it.isNotBlank() }
    fun region(c: Context): String = prefs(c).getString("azure_region", null) ?: DEFAULT_REGION
    fun voice(c: Context): String = prefs(c).getString("azure_voice", null) ?: DEFAULT_VOICE
    fun hasKey(c: Context) = key(c) != null
    /** Reading with Azure is on (and possible). */
    fun on(c: Context) = prefs(c).getBoolean("azure_on", false) && hasKey(c)

    fun save(c: Context, key: String, region: String) {
        SecureStore(c.applicationContext).put(KEY, key.trim())
        prefs(c).edit().putString("azure_region", region).apply()
    }
    fun forget(c: Context) {
        SecureStore(c.applicationContext).put(KEY, null)
        prefs(c).edit().putBoolean("azure_on", false).apply()
    }
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("azure_on", on).apply()
    fun setVoice(c: Context, v: String) = prefs(c).edit().putString("azure_voice", v).apply()

    // ── this month's characters sent from this phone (an estimate: Azure's own count is in its portal) ──

    private fun month() = LocalDate.now().toString().take(7)
    fun usedThisMonth(c: Context): Int {
        val p = prefs(c)
        return if (p.getString("azure_month", null) == month()) p.getInt("azure_chars", 0) else 0
    }
    private fun count(c: Context, chars: Int) {
        val used = usedThisMonth(c) + chars
        prefs(c).edit().putString("azure_month", month()).putInt("azure_chars", used).apply()
    }

    // ── requests ──

    class AzureError(val status: Int, message: String) : IOException(message)

    /** A short Korean reason for the reader. */
    fun reason(e: Throwable): String = when ((e as? AzureError)?.status) {
        401, 403 -> "키나 지역이 맞지 않습니다"
        429 -> "이번 달 무료 한도를 다 썼거나 요청이 너무 많습니다"
        400 -> "요청을 받아 주지 않았습니다"
        null -> "인터넷에 연결할 수 없습니다"
        else -> "Azure 서버 문제(${(e as AzureError).status})"
    }

    private fun host(region: String) = "https://$region.tts.speech.microsoft.com/cognitiveservices"

    @Serializable
    data class VoiceInfo(
        val ShortName: String,
        val LocalName: String = "",
        val Gender: String = "",
        val Locale: String = "",
        val VoiceType: String = "",
    )

    /** Korean voices of the region; also the connection test (a wrong key or region fails here, for free). */
    suspend fun voices(key: String, region: String): List<VoiceInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${host(region)}/voices/list")
            .header("Ocp-Apim-Subscription-Key", key).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AzureError(r.code, "voices ${r.code}")
            val all = Http.json.decodeFromString(ListSerializer(VoiceInfo.serializer()), r.body!!.string())
            all.filter { it.Locale.startsWith("ko-") && it.VoiceType.equals("Neural", true) }.sortedBy { it.ShortName }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun cacheDir(c: Context) = File(c.cacheDir, "azure_tts").apply { mkdirs() }
    private fun cacheFile(c: Context, voice: String, text: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest("$voice\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir(c), "$h.mp3")
    }

    /** The sentence as an MP3 file: from the cache, or asked for now (and kept). */
    suspend fun sentence(c: Context, text: String, voice: String = voice(c)): File = withContext(Dispatchers.IO) {
        val f = cacheFile(c, voice, text)
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return@withContext f }
        val key = key(c) ?: throw AzureError(401, "no key")
        val lang = voice.split('-').take(2).joinToString("-")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='$voice'>${esc(text)}</voice></speak>"
        val req = Request.Builder().url("${host(region(c))}/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("X-Microsoft-OutputFormat", FORMAT)
            .header("User-Agent", "OGReader")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AzureError(r.code, "synth ${r.code}")
            val bytes = r.body!!.bytes()
            if (bytes.isEmpty()) throw AzureError(502, "empty")
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        count(c, text.length)
        trim(c)
        f
    }

    private fun trim(c: Context) {
        val files = cacheDir(c).listFiles()?.filter { it.name.endsWith(".mp3") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_MAX) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_MAX * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    fun clearCache(c: Context) { cacheDir(c).listFiles()?.forEach { it.delete() } }
}
