package com.oasisreader.app.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.PronEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 발음 사전 (one for the whole app): "표현 → 읽는 법" pairs kept in the database (so they go with
 * the backup) and held here for 소리 내 읽기, which reads them on the main thread.
 */
object PronDict {
    /** word to reading, by word. */
    var entries by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    @Volatile private var loaded = false

    const val MAX_WORD = 50
    const val MAX_READING = 100

    suspend fun load(context: Context, again: Boolean = false) {
        if (loaded && !again) return
        val list = withContext(Dispatchers.IO) { AppDatabase.get(context).pron().all() }
        entries = list.map { it.word to it.reading }
        loaded = true
    }

    /** Add or change; [old] is the word being edited (renamed entries drop the old one). */
    suspend fun put(context: Context, word: String, reading: String, old: String? = null) {
        val w = word.trim().take(MAX_WORD)
        val r = reading.trim().take(MAX_READING)
        if (w.isEmpty() || r.isEmpty()) return
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(context).pron()
            if (old != null && old != w) dao.delete(old)
            dao.upsert(PronEntity(w, r, System.currentTimeMillis()))
        }
        load(context, again = true)
    }

    suspend fun remove(context: Context, word: String) {
        withContext(Dispatchers.IO) { AppDatabase.get(context).pron().delete(word) }
        load(context, again = true)
    }
}
