package com.oasisreader.app.meta

import android.content.Context
import com.oasisreader.app.data.Server
import com.oasisreader.app.opds.OpdsClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * bookoasis thumbnails are hash-named, so a book id alone can't give the URL. The OPDS search
 * results do carry them: search by name, keep only the entry whose download link has exactly
 * this book id, and remember every id -> thumbnail pair seen on the way (kept for good).
 */
object CoverResolver {
    private val gate = Semaphore(3)
    private val searchUrlLock = Mutex()
    private val searchTemplates = HashMap<String, String?>()   // server id -> ".../search?q={q}"
    private const val MISS = "-"

    private fun prefs(context: Context) = context.getSharedPreferences("meta_covers", Context.MODE_PRIVATE)
    private fun key(db: String, id: Long) = "$db:$id"

    /** Known thumbnail for a book, or null when unknown / known to have none. */
    fun cached(context: Context, db: String, id: Long): String? =
        prefs(context).getString(key(db, id), null)?.takeIf { it != MISS }

    fun isKnown(context: Context, db: String, id: Long) = prefs(context).contains(key(db, id))

    private suspend fun searchUrl(server: Server, opds: OpdsClient, q: String): String? {
        val tpl = searchUrlLock.withLock {
            if (!searchTemplates.containsKey(server.id)) {
                val root = runCatching { opds.fetch(server.url) }.getOrNull()
                searchTemplates[server.id] = root?.let { runCatching { opds.searchUrl(it, "QQQ") }.getOrNull() }
            }
            searchTemplates[server.id]
        } ?: return null
        return tpl.replace("QQQ", java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
    }

    /** Words worth searching for: drop "[작가]" tags and keep the first few words. */
    private fun query(name: String): String =
        name.replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ").trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }.take(3).joinToString(" ")
            .ifBlank { name }

    suspend fun resolve(context: Context, server: Server, db: String, id: Long, name: String): String? {
        if (isKnown(context, db, id)) return cached(context, db, id)
        return gate.withPermit {
            if (isKnown(context, db, id)) return@withPermit cached(context, db, id)
            val opds = OpdsClient(server)
            val url = searchUrl(server, opds, query(name)) ?: return@withPermit null
            val feed = runCatching { opds.fetch(url) }.getOrNull() ?: return@withPermit null
            val p = prefs(context).edit()
            var found: String? = null
            for (e in feed.entries) {
                val thumb = e.thumb ?: continue
                val ids = e.acquisitions.mapNotNull { MetaApi.idFromDownload(it.href) }
                for ((d, n) in ids) {
                    p.putString(key(d, n), thumb)
                    if (d == db && n == id) found = thumb
                }
            }
            if (found == null) p.putString(key(db, id), MISS)
            p.apply()
            found
        }
    }
}
