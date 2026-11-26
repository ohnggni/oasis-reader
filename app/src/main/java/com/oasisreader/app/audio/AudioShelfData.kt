package com.oasisreader.app.audio

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// ── reading bookoasis JSON loosely (field names differ a little between lists) ──
private fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" } }
private fun JsonObject.int(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } }
private fun JsonObject.dbl(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } }
/** The first array of objects in a response ({"items": [...]}, {"series": [...]} …). */
private fun JsonObject.firstList(vararg prefer: String): List<JsonObject> {
    val arr = prefer.firstNotNullOfOrNull { this[it] as? JsonArray } ?: values.firstOrNull { it is JsonArray } as? JsonArray
    return arr.orEmpty().mapNotNull { it as? JsonObject }
}

internal data class AudioItem(
    val id: Int?, val title: String, val author: String?, val libraryId: String?, val cover: String?, val progress: Double?,
    val completed: Boolean = false,
    val added: String? = null,       // latest_added (for merging several categories by date)
    val folder: String? = null,      // anchor_dir (for merging by folder name)
)
internal data class AudioLibrary(val id: String, val name: String)

internal fun JsonElement.toItem(): AudioItem? {
    val o = this as? JsonObject ?: return null
    val title = o.str("display_name", "series_alias", "series_name", "title", "name") ?: return null
    return AudioItem(
        id = o.int("representative_book_id", "audiobook_id", "book_id", "id", "first_book_id"),
        title = title,
        author = o.str("author", "artist"),
        // the list carries the category in series_key ("<library>:<hash>")
        libraryId = o.str("library_id") ?: o.str("series_key")?.substringBefore(':')?.takeIf { k -> k.all(Char::isDigit) },
        cover = o.str("cover_image", "cover", "cover_url", "thumbnail"),
        progress = o.dbl("total_progress_pct", "progress_pct", "progress"),
        completed = (o.int("is_completed") ?: 0) != 0,
        added = o.str("latest_added"),
        folder = o.str("anchor_dir"),
    )
}

/** Sorts bookoasis offers for its lists (repositories/series_list_options.py). */
internal enum class AudioSort(val label: String, val kind: com.oasisreader.app.ui.SortKind, val defaultDesc: Boolean) {
    NAME("이름", com.oasisreader.app.ui.SortKind.TEXT, false),
    FOLDER("폴더 이름", com.oasisreader.app.ui.SortKind.TEXT, false),
    ADDED("추가한 날짜", com.oasisreader.app.ui.SortKind.DATE, true);

    fun api(desc: Boolean) = when (this) {
        NAME -> if (desc) "desc" else "asc"
        FOLDER -> if (desc) "folder_desc" else "folder_asc"
        ADDED -> if (desc) "date_desc" else "date_asc"
    }
}

/**
 * bookoasis searches the title unless the text starts with a prefix (작가: / 주제: / author: …),
 * so the chosen field becomes that prefix; a prefix typed by hand is left as it is.
 */
internal fun searchWithField(text: String, field: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val typed = Regex("^[^\\s:：]{1,8}[:：]").containsMatchIn(t)
    return when {
        typed || field == "title" -> t
        field == "author" -> "작가:$t"
        field == "topic" -> "주제:$t"
        else -> t
    }
}

/** The categories in a /api/media/libraries answer. */
internal fun audioLibraries(o: JsonObject): List<AudioLibrary> = o.firstList("libraries").mapNotNull { l ->
    val id = l.str("id") ?: return@mapNotNull null
    AudioLibrary(id, l.str("name") ?: id)
}

/**
 * Fills the 서가 list. AudiobookScreen owns (remembers) every state and hands them in; a new one of
 * these is made on each composition, so it always works on the states of the current server.
 */
internal class AudioShelfLoader(
    private val web: BookOasisWeb,
    private val items: SnapshotStateList<AudioItem>,
    private val totals: HashMap<String, Int>,   // all that match, when the server says and no app-side filter applies
    private val cursors: HashMap<String, Int>,  // library ("all" = none chosen) → next page
    private val done: HashSet<String>,
    libIdsState: State<Set<Int>>, readFilterState: State<String>, activeState: State<String>,
    fieldState: State<String>, sortState: State<AudioSort>, sortDescState: State<Boolean>,
    pageState: MutableIntState, hasMoreState: MutableState<Boolean>, totalState: MutableState<Int?>,
    loadingState: MutableState<Boolean>, errorState: MutableState<String?>,
) {
    private val libIds by libIdsState
    private val readFilter by readFilterState
    private val active by activeState
    private val field by fieldState
    private val sort by sortState
    private val sortDesc by sortDescState
    private var page by pageState
    private var hasMore by hasMoreState
    private var total by totalState
    private var loading by loadingState
    private var error by errorState

    private fun keep(it: AudioItem): Boolean =
        when (readFilter) { "done" -> it.completed; "listening" -> !it.completed; else -> true }
    private val sortKey: (AudioItem) -> String = { a ->
        when (sort) {
            AudioSort.NAME -> a.title
            AudioSort.FOLDER -> a.folder ?: a.title
            AudioSort.ADDED -> a.added.orEmpty()
        }
    }
    private suspend fun fetch(lib: String, pg: Int): Pair<List<AudioItem>, Boolean> {
        val o = web.get("/api/media/list", mapOf(
            "type" to "audiobook", "library_id" to lib,
            "search" to searchWithField(active, field),
            "sort" to sort.api(sortDesc), "page" to pg, "limit" to 40,
        ))
        val list = o.firstList("items", "series", "data", "list", "books").mapNotNull { it.toItem() }
        if (pg == 1) {
            val pgo = o["pagination"] as? JsonObject
            (o.int("total", "total_count", "totalCount", "count") ?: pgo?.int("total", "total_count", "count"))?.let { totals[lib] = it }
        }
        val more = (o["has_more"] as? JsonPrimitive)?.contentOrNull == "true" || (o["has_more"] == null && list.size >= 40)
        return list to more
    }
    /** p == 1: start over; otherwise one more round from every category that still has more. */
    suspend fun load(p: Int) {
        loading = true; error = null
        try {
            val libs = if (libIds.isEmpty()) listOf("all") else libIds.sorted().map { it.toString() }
            if (p == 1) { items.clear(); cursors.clear(); done.clear(); totals.clear(); page = 0; libs.forEach { cursors[it] = 1 } }
            var added = 0
            var rounds = 0
            do {
                val batch = ArrayList<AudioItem>()
                for (lib in libs) {
                    if (lib in done) continue
                    val pg = cursors[lib] ?: 1
                    val (list, more) = fetch(lib, pg)
                    batch += list.filter(::keep)
                    cursors[lib] = pg + 1
                    if (!more) done += lib
                }
                val cmp = compareBy<AudioItem, String>(com.oasisreader.app.data.ShelfSearch.natural) { sortKey(it) }
                val sorted = if (libs.size > 1) batch.sortedWith(if (sortDesc) cmp.reversed() else cmp) else batch
                items.addAll(sorted); added += sorted.size
                page++
                rounds++
                // the 읽음 filter can leave a round empty: read on a little, then stop (more stays offered)
            } while (added < 24 && done.size < libs.size && rounds < 6)
            hasMore = done.size < libs.size
            total = if (readFilter.isEmpty() && totals.keys.containsAll(libs)) libs.sumOf { totals[it] ?: 0 } else null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { loading = false }
    }
}
