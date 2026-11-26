package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Reading settings shared by a 컬렉션 or an automatic series group (🔗): change the font or the
 * view in one volume and the others follow. A book on no 컬렉션 and in no series keeps its own.
 * The same grouping as the 서재: the first 컬렉션 a book is on, else its series key (unless that
 * group was undone with "이 묶음 풀기").
 */
object SettingsGroup {
    /** The settings owner for [bookId], or null for the book alone. A quick local lookup. */
    fun of(app: Context, bookId: String): String? =
        runCatching { runBlocking(Dispatchers.IO) { find(app, bookId) } }.getOrNull()

    private suspend fun find(app: Context, bookId: String): String? {
        val db = AppDatabase.get(app)
        val mine = db.collections().observeLinks().first().filter { it.bookId == bookId }.map { it.collectionId }.toSet()
        if (mine.isNotEmpty()) {
            db.collections().all().firstOrNull { it.id in mine }?.let { return "gc" + hex(it.id) }
        }
        val b = db.books().get(bookId) ?: return null
        val key = seriesKeyOf(b) ?: return null
        val undone = app.getSharedPreferences("library", Context.MODE_PRIVATE).getStringSet("ungrouped_series", emptySet()).orEmpty()
        if (key in undone) return null
        return "gs" + hex(key)
    }

    // no ':' (the settings keys are "<owner>:<key>")
    private fun hex(s: String) = Integer.toHexString(s.hashCode()) + Integer.toHexString(s.length)
}
