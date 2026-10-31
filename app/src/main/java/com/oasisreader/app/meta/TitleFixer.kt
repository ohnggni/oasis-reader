package com.oasisreader.app.meta

import android.content.Context
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.opds.OpdsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Books that came from bookoasis OPDS with a title like "[만화] 1" or "unknown title": ask oasis-meta for the
 * clean title once (by the id in the download link) and rename them. Quietly does nothing
 * offline or when no server has a meta API.
 */
object TitleFixer {
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val settings = ServerSettings(context)
        var fixed = 0
        for (b in db.books().all()) {
            if (!OpdsClient.isPoorTitle(b.title)) continue
            val url = b.sourceUrl ?: continue
            val (dbName, id) = MetaApi.idFromDownload(url) ?: continue
            val server = settings.forUrl(url)?.takeIf { it.metaUrl.isNotBlank() } ?: continue
            val name = runCatching { MetaApi(server, dbName).book(id).name }.getOrNull() ?: continue
            if (name.isNotBlank() && name != b.title) {
                db.books().rename(b.id, name)
                fixed++
            }
        }
        fixed
    }
}
