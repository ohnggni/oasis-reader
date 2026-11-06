package com.oasisreader.app.reader

import android.content.Context
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.ui.TurnSpeed
import com.oasisreader.app.ui.TurnStyle

enum class ReaderMode { PAGE, SCROLL }

/**
 * Page colours for the book reader. [AUTO] follows the app's light/dark choice
 * (PAPER in light, NIGHT in dark).
 */
enum class ReaderTheme(
    val id: String, val label: String, val dark: Boolean,
    val bg: Long, val fg: Long, val muted: Long, val accent: Long, val rule: Long,
) {
    AUTO("auto", "자동", false, 0xFFF6EFE1, 0xFF2B2620, 0xFF7A6F60, 0xFF2F5D50, 0xFFE3D8C3),
    PAPER("paper", "종이", false, 0xFFF6EFE1, 0xFF2B2620, 0xFF7A6F60, 0xFF2F5D50, 0xFFE3D8C3),
    WHITE("white", "흰색", false, 0xFFFFFFFF, 0xFF1E1E1E, 0xFF6E6E6E, 0xFF2F5D50, 0xFFE4E4E4),
    SEPIA("sepia", "세피아", false, 0xFFEEDDBB, 0xFF4A3A26, 0xFF84704F, 0xFF7A4E1E, 0xFFDCC69C),
    GRAY("gray", "회색", false, 0xFFE4E4E1, 0xFF262626, 0xFF6A6A66, 0xFF3E5470, 0xFFD0D0CC),
    GREEN("green", "연두", false, 0xFFDCE8D5, 0xFF223020, 0xFF5E7058, 0xFF2F5D50, 0xFFC6D8BC),
    NIGHT("night", "밤", true, 0xFF1B2420, 0xFFD6DDD6, 0xFF8F9C94, 0xFF9CC8B4, 0xFF2E3A34),
    NAVY("navy", "남색", true, 0xFF141C2B, 0xFFC9D3E0, 0xFF8090A6, 0xFF8FB4E0, 0xFF26324A),
    BLACK("black", "검정", true, 0xFF000000, 0xFFBDBDBD, 0xFF7A7A7A, 0xFF9CC8B4, 0xFF222222);

    /** AUTO resolved against the app's light/dark setting. */
    fun resolve(appDark: Boolean) = if (this == AUTO) (if (appDark) NIGHT else PAPER) else this

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

/**
 * Reader settings. Reading positions live in the database.
 *
 * With a [bookId], look-and-feel settings (font, size, spacing, margins, colours, page turns,
 * book style, mode) are per book: a change made while reading is kept for that book only, and a
 * book with no change of its own follows the defaults. Without a [bookId] (or for brightness,
 * screen-on, bar hiding) the defaults themselves are read and written.
 */
class ReaderPrefs(context: Context, private val bookId: String? = null) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val book = context.getSharedPreferences("reader_book", Context.MODE_PRIVATE)

    private fun bk(key: String) = "$bookId:$key"
    private fun perBook(key: String) = bookId != null && key in BOOK_KEYS

    private fun int(key: String, def: Int): Int =
        if (perBook(key) && book.contains(bk(key))) book.getInt(bk(key), def) else prefs.getInt(key, def)
    private fun setInt(key: String, v: Int) =
        if (perBook(key)) book.edit().putInt(bk(key), v).apply() else prefs.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean): Boolean =
        if (perBook(key) && book.contains(bk(key))) book.getBoolean(bk(key), def) else prefs.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) =
        if (perBook(key)) book.edit().putBoolean(bk(key), v).apply() else prefs.edit().putBoolean(key, v).apply()
    private fun str(key: String): String? =
        if (perBook(key) && book.contains(bk(key))) book.getString(bk(key), null) else prefs.getString(key, null)
    private fun setStr(key: String, v: String) =
        if (perBook(key)) book.edit().putString(bk(key), v).apply() else prefs.edit().putString(key, v).apply()

    var fontPx: Int
        get() = int("font_px", 18)
        set(v) = setInt("font_px", v.coerceIn(MIN_FONT, MAX_FONT))

    var mode: ReaderMode
        get() = runCatching { ReaderMode.valueOf(str("mode") ?: "") }.getOrDefault(ReaderMode.PAGE)
        set(v) = setStr("mode", v.name)

    val animatePages: Boolean get() = prefs.getBoolean("animate_pages", true)

    /** 0.02..1 = fixed level for the reader, -1 = follow the system */
    var brightness: Float
        get() = prefs.getFloat("brightness", -1f)
        set(v) = prefs.edit().putFloat("brightness", v).apply()

    /** 0 = normal, 1 = slightly bolder, 2 = bolder (stroke-thickened, works for every font) */
    var weight: Int
        get() = int("weight", 0)
        set(v) = setInt("weight", v.coerceIn(0, 2))

    var fontId: String
        get() = str("font_id") ?: FontManager.ALL.first().id
        set(v) = setStr("font_id", v)

    /** keep the book's own CSS (alignment, indents, headings); fonts/colours are always the reader's */
    var publisherStyle: Boolean
        get() = bool("publisher_style", true)
        set(v) = setBool("publisher_style", v)

    /** line height x10 (18 = 1.8) */
    var lineHeight: Int
        get() = int("line_height", 18)
        set(v) = setInt("line_height", v.coerceIn(14, 24))

    /** keep the screen on while reading (released after 10 min without a page turn) */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** books, comics, PDF: no Android status bar while one is open (the 서재 keeps it) */
    var hideStatusBar: Boolean
        get() = bool("hide_status_bar", false)
        set(v) = setBool("hide_status_bar", v)

    /** the same for the navigation bar (gesture handle / buttons) */
    var hideNavBar: Boolean
        get() = prefs.getBoolean("hide_nav_bar", false)
        set(v) = prefs.edit().putBoolean("hide_nav_bar", v).apply()

    /** with the status bar hidden: a small clock and battery on the right of the top line */
    var showClock: Boolean
        get() = bool("show_clock", false)
        set(v) = setBool("show_clock", v)

    var showBattery: Boolean
        get() = bool("show_battery", false)
        set(v) = setBool("show_battery", v)

    /** the chapter title at the top of the page (comics/PDF: the book's title, with the status bar hidden) */
    var showHead: Boolean
        get() = bool("show_head", true)
        set(v) = setBool("show_head", v)

    /** the page number / % at the bottom; off = the text goes further down */
    var showPageNum: Boolean
        get() = bool("show_page_num", true)
        set(v) = setBool("show_page_num", v)

    /** hide the top bar a few seconds after it appears */
    var autoHideBars: Boolean
        get() = prefs.getBoolean("auto_hide_bars", true)
        set(v) = prefs.edit().putBoolean("auto_hide_bars", v).apply()

    /** page-turn look (book reader and comic/PDF viewer); older "effect off" maps to none */
    var turnStyle: TurnStyle
        get() = str("turn_style")?.let { TurnStyle.of(it) } ?: if (animatePages) TurnStyle.SLIDE else TurnStyle.NONE
        set(v) = setStr("turn_style", v.id)

    var turnSpeed: TurnSpeed
        get() = TurnSpeed.of(str("turn_speed"))
        set(v) = setStr("turn_speed", v.id)

    var theme: ReaderTheme
        get() = ReaderTheme.of(str("theme"))
        set(v) = setStr("theme", v.id)

    /** page margins, dp added to the built-in ones (0 = as before) */
    var marginTop: Int
        get() = int("margin_top", 0)
        set(v) = setInt("margin_top", v)
    var marginBottom: Int
        get() = int("margin_bottom", 0)
        set(v) = setInt("margin_bottom", v)
    var marginLeft: Int
        get() = int("margin_left", 0)
        set(v) = setInt("margin_left", v)
    var marginRight: Int
        get() = int("margin_right", 0)
        set(v) = setInt("margin_right", v)

    val font: FontOption get() = FontManager.byId(fontId)

    /** Does this book have settings of its own? */
    val hasOwn: Boolean get() = bookId != null && book.all.keys.any { it.startsWith("$bookId:") }

    /** Make this book's settings the defaults for every book without its own. */
    fun saveAsDefaults() {
        if (bookId == null) return
        val e = prefs.edit()
        for ((k, v) in book.all) {
            if (!k.startsWith("$bookId:")) continue
            val key = k.removePrefix("$bookId:")
            when (v) {
                is Int -> e.putInt(key, v)
                is Boolean -> e.putBoolean(key, v)
                is String -> e.putString(key, v)
            }
        }
        e.apply()
    }

    /** Forget this book's own settings: it follows the defaults again. */
    /** Every book goes back to the defaults (their own settings are dropped). */
    fun resetAllBooks() = book.edit().clear().apply()

    /** How many books have settings of their own. */
    val booksWithOwn: Int get() = book.all.keys.map { it.substringBefore(':') }.toSet().size

    fun resetBook() {
        if (bookId == null) return
        val e = book.edit()
        book.all.keys.filter { it.startsWith("$bookId:") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        const val MIN_FONT = 13
        const val MAX_FONT = 30
        /** margin adjustment range (dp relative to the default) */
        val MARGIN_V = -24..64
        val MARGIN_H = -16..64

        val BOOK_KEYS = setOf(
            "font_px", "mode", "weight", "font_id", "publisher_style", "line_height",
            "turn_style", "turn_speed", "theme", "margin_top", "margin_bottom", "margin_left", "margin_right",
            "hide_status_bar", "show_clock", "show_battery", "show_head", "show_page_num",
        )
    }
}
