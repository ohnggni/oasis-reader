package com.oasisreader.app.data

import com.oasisreader.app.data.db.BookEntity

/** What a 서재 book is: shown first in its text line, one tab each, sorted by 종류. */
enum class ShelfKind(val emoji: String, val label: String) {
    BOOK("📖", "책"), COMIC("🖼️", "만화"), PDF("📄", "PDF"), AUDIO("🎧", "오디오북");

    companion object {
        fun of(b: BookEntity) = when (b.format) {
            BookStore.FORMAT_COMIC -> COMIC
            BookStore.FORMAT_PDF -> PDF
            BookStore.FORMAT_AUDIOBOOK -> AUDIO
            else -> BOOK
        }

        /** The user's order for 종류 sorting and the tabs (prefs "kind_order", names joined by commas). */
        fun order(saved: String?): List<ShelfKind> {
            val named = saved?.split(',')?.mapNotNull { n -> entries.firstOrNull { it.name == n } }.orEmpty()
            return (named + entries).distinct()
        }
    }
}

/**
 * Which series a book belongs to, for 🔗 시리즈별로 묶기: the server series or folder it came from
 * (since 1.2), else its title without the volume number; per kind, so a novel and a comic of the same
 * name stay apart. Null: not part of a series.
 */
fun seriesKeyOf(b: BookEntity): String? {
    val kind = ShelfKind.of(b).name
    BookOrigin.decode(b.origin)?.let { o ->
        when (o.type) {
            BookOrigin.META -> o.series?.let { return "$kind|m|${o.server}|${o.library}|$it" }
            BookOrigin.OPDS -> o.urls.lastOrNull()?.let { return "$kind|o|${o.server}|$it" }
            BookOrigin.FOLDER -> o.folder?.takeIf { it.isNotBlank() }?.let { return "$kind|f|${o.tree}|$it" }
        }
    }
    return ShelfSearch.seriesStem(b.title)?.let { "$kind|t|" + ShelfSearch.normalize(it) }
}
