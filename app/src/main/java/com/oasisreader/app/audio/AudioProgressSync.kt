package com.oasisreader.app.audio

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.oasisreader.app.data.Http
import com.oasisreader.app.data.ServerSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Listening places that could not reach bookoasis (offline, a downloaded book on the subway …)
 * are kept here, one per audiobook, and sent when there is a connection again — by a small
 * WorkManager job, so it happens even with the app closed.
 *
 * Which place wins — the phone's or the server's? bookoasis keeps when a place was saved, but its
 * web API does not hand that time out. So the app remembers the last place IT sent ([markSent]):
 * when the server now holds something else, the web or another device saved after us → the
 * server wins (even when it went back). When it still holds ours, nobody else listened → the
 * phone wins (it may be further, listened offline). Without such a memory (first time on this
 * phone): the one further along.
 */
object AudioProgressSync {
    private const val PREFS = "audio_progress_pending"
    private const val WORK = "audio-progress-sync"

    @Serializable
    data class Pending(
        val serverId: String,
        val audiobookId: Int,
        val title: String,
        val trackId: Int,
        val trackIndex: Int,
        val seconds: Double,
        val rate: Double,
        val percent: Double,
        val completed: Boolean,
        val at: Long,
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun sentPrefs(c: Context) = c.applicationContext.getSharedPreferences("audio_last_sent", Context.MODE_PRIVATE)

    /** The server took this place from us. */
    fun markSent(c: Context, serverId: String, audiobookId: Int, trackId: Int, seconds: Double) {
        sentPrefs(c).edit().putString("$serverId|$audiobookId", "$trackId:$seconds").apply()
    }

    /** true: the server's place was saved elsewhere after ours · false: it is still ours · null: unknown. */
    fun changedElsewhere(c: Context, serverId: String, audiobookId: Int, serverTrackId: Int?, serverSeconds: Double): Boolean? {
        if (serverTrackId == null) return null
        val v = sentPrefs(c).getString("$serverId|$audiobookId", null) ?: return null
        val t = v.substringBefore(':').toIntOrNull() ?: return null
        val sec = v.substringAfter(':').toDoubleOrNull() ?: return null
        return t != serverTrackId || kotlin.math.abs(sec - serverSeconds) > 3
    }

    /**
     * Start from the phone's place (true) or the server's (false)? See the rule above.
     * [serverIdx] < 0 means the server has no place (or could not be reached).
     */
    fun preferPhone(
        c: Context, serverId: String, audiobookId: Int, serverTrackId: Int?, serverIdx: Int, serverSeconds: Double,
        phoneIdx: Int, phoneSeconds: Double,
    ): Boolean {
        if (serverIdx < 0) return true
        return when (changedElsewhere(c, serverId, audiobookId, serverTrackId, serverSeconds)) {
            true -> false
            false -> true
            null -> phoneIdx > serverIdx || (phoneIdx == serverIdx && phoneSeconds > serverSeconds + 5)
        }
    }
    private fun key(p: Pending) = "${p.serverId}|${p.audiobookId}"

    fun all(c: Context): List<Pending> = prefs(c).all.values.mapNotNull { v ->
        runCatching { Http.json.decodeFromString(Pending.serializer(), v as String) }.getOrNull()
    }

    /** A place that did not go out: keep the newest one per book and send it when online. */
    fun keep(c: Context, p: Pending) {
        prefs(c).edit().putString(key(p), Http.json.encodeToString(Pending.serializer(), p)).apply()
        schedule(c)
    }

    /** It went out: nothing left to send for this book. */
    fun sent(c: Context, serverId: String, audiobookId: Int) {
        prefs(c).edit().remove("$serverId|$audiobookId").apply()
    }

    fun schedule(c: Context) {
        if (prefs(c).all.isEmpty()) return
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Send what is kept. Returns true when nothing is left (or only places that can never go). */
    suspend fun flush(c: Context): Boolean {
        val app = c.applicationContext
        var retry = false
        all(app).forEach { p ->
            val server = ServerSettings(app).get(p.serverId)
            if (server == null) { sent(app, p.serverId, p.audiobookId); return@forEach }
            val web = BookOasisWeb.of(server)
            try {
                val d = AudioLibraryLink.detail(web, p.serverId, p.audiobookId, p.title, null)
                val serverIdx = d.book.tracks.indexOfFirst { it.id == d.book.startTrackId }
                // saved elsewhere after us: that one stands, ours is dropped
                val serverWins = !p.completed && !preferPhone(
                    app, p.serverId, p.audiobookId, d.book.startTrackId, serverIdx, d.book.startSeconds, p.trackIndex, p.seconds,
                )
                if (!serverWins) web.post("/api/media/audiobooks/${p.audiobookId}/progress", buildJsonObject {
                    put("current_track_id", p.trackId)
                    put("current_time", p.seconds)
                    put("playback_rate", p.rate)
                    put("total_progress_pct", if (p.completed) 100.0 else p.percent)
                    if (p.completed) put("is_completed", 1)
                }).also { markSent(app, p.serverId, p.audiobookId, p.trackId, p.seconds) }
                // a newer place was saved meanwhile (the player is still going): keep that one
                val now = all(app).firstOrNull { it.serverId == p.serverId && it.audiobookId == p.audiobookId }
                if (now == null || now.at == p.at) sent(app, p.serverId, p.audiobookId)
            } catch (e: Exception) {
                retry = true
            }
        }
        return !retry
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            if (flush(applicationContext) || runAttemptCount >= 20) Result.success() else Result.retry()
    }
}
