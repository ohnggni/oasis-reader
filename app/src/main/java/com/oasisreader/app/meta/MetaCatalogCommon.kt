package com.oasisreader.app.meta

import com.oasisreader.app.data.MetaApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.ui.RemoteCover

internal enum class SeriesSort(val label: String, val kind: com.oasisreader.app.ui.SortKind, val defaultDesc: Boolean) {
    DATE("올라온 날짜", com.oasisreader.app.ui.SortKind.DATE, true),
    ADDED("서버에 추가된 날짜", com.oasisreader.app.ui.SortKind.DATE, true),
    NAME("이름", com.oasisreader.app.ui.SortKind.TEXT, false);

    fun api(desc: Boolean): String = when (this) {
        DATE -> if (desc) "mtime_desc" else "mtime_asc"
        ADDED -> if (desc) "added_desc" else "added_asc"
        NAME -> if (desc) "name_desc" else "name"
    }
}

/** "2022-05-20T01:45:53Z" -> "2022.5.20" in local time */
internal fun metaDate(s: String?): String? {
    if (s.isNullOrBlank()) return null
    return runCatching {
        val d = java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "${d.year}.${d.monthValue}.${d.dayOfMonth}"
    }.getOrNull()
}

/** 📖 book · 🖼️ comic · 📄 PDF, from the file format. */
internal fun formatEmoji(format: String?): String = when (format?.lowercase()) {
    "cbz", "zip", "cbr", "rar", "cb7", "7z" -> "🖼️"
    "pdf" -> "📄"
    null, "" -> "📦"
    else -> "📖"
}

internal fun mb(bytes: Long) = if (bytes <= 0) null else "%.0f MB".format(bytes / 1048576.0)


/**
 * Cover from oasis-meta's cover_url when given; otherwise bookoasis's own thumbnail,
 * found once through an OPDS search (CoverResolver) and remembered.
 */
@Composable
internal fun MetaCover(api: MetaApi, opds: OpdsClient, coverPath: String?, bookId: Long?, name: String, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val url by androidx.compose.runtime.produceState(
        api.absolute(coverPath) ?: bookId?.let { CoverResolver.cached(context, api.db, it) },
        coverPath, bookId,
    ) {
        if (value != null || bookId == null) return@produceState
        value = CoverResolver.resolve(context, api.server, api.db, bookId, name)
    }
    RemoteCover(url, name, load = { opds.bytes(it) }, modifier = modifier)
}

internal val SERIES_TILE_STEPS = listOf(80, 96, 112, 130, 150, 176)
internal const val SERIES_TILE_DEFAULT = 112
