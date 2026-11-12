package com.oasisreader.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's own one-colour icons (TODO 48): drawn on a 24 grid as bold lines
 * (width 2.25, square ends, no fill), tinted with the content colour like text.
 * Screens still name things by emoji; [forEmoji] hands back the matching icon
 * when the "단색" style is on, or null to keep the emoji.
 */
object OgIcons {
    const val MONO = "mono"
    const val EMOJI = "emoji"

    var style by mutableStateOf(MONO)
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun load(c: Context) { style = prefs(c).getString("icon_style", MONO) ?: MONO }

    fun set(c: Context, s: String) {
        style = s
        prefs(c).edit().putString("icon_style", s).apply()
    }

    fun label(s: String) = if (s == EMOJI) "이모지" else "단색"

    private val cache = HashMap<String, ImageVector>()

    fun icon(name: String): ImageVector? {
        cache[name]?.let { return it }
        val d = OgIconPaths.paths[name] ?: return null
        val v = ImageVector.Builder(name = "og_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.25f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
            )
            .build()
        cache[name] = v
        return v
    }

    /** The icon to draw in place of [emoji], or null to keep the emoji (style "이모지", or no icon for it). */
    fun forEmoji(emoji: String): ImageVector? = if (style == EMOJI) null else OgIconPaths.nameFor(emoji)?.let { icon(it) }
}

/** The drawings and the emoji table, kept free of UI state so plain unit tests can read them. */
object OgIconPaths {
    /** Icon name -> path data (viewBox 0 0 24 24). */
    val paths: Map<String, String> = mapOf(
        // 공통 조작
        "back" to "M19.5 12H5.5M11 6L5 12L11 18",
        "home" to "M4 11L12 4L20 11M6 9.5V20H18V9.5M10 20V14H14V20",
        "menu" to "M4.5 6.5H19.5M4.5 12H19.5M4.5 17.5H19.5",
        "more" to "M12 5.5h.01M12 12h.01M12 18.5h.01",
        "search" to "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0M15.5 15.5L20 20",
        "settings" to "M4 8H12M18 8H20M4 16H6M12 16H20M12.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M6.5 16a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "add" to "M12 5V19M5 12H19",
        "close" to "M6 6L18 18M18 6L6 18",
        "delete" to "M4.5 7H19.5M9.5 7V4H14.5V7M6.5 7L7.5 20H16.5L17.5 7M10.2 11V16M13.8 11V16",
        "share" to "M12 15V4M7.5 8.5L12 4L16.5 8.5M5 13V20H19V13",
        "refresh" to "M19 12a7 7 0 1 1-2.05-4.95M17 3V7.05H13",
        // 서재 · 종류
        "shelf" to "M4 4h4v16H4zM8 4h4v16H8zM14.2 6.2l3.6-1 3.7 13.6-3.6 1z",
        "book" to "M12 6.5V20M12 6.5C10 5 7 4.5 3.5 5V18.5C7 18 10 18.5 12 20C14 18.5 17 18 20.5 18.5V5C17 4.5 14 5 12 6.5Z",
        "audiobook" to "M4 14v-2a8 8 0 0 1 16 0v2M4 14h3v6H5.5a1.5 1.5 0 0 1-1.5-1.5zM20 14h-3v6h1.5a1.5 1.5 0 0 0 1.5-1.5z",
        "file" to "M6 3.5H14L18.5 8V20.5H6ZM14 3.5V8H18.5M9 12.5H15.5M9 16.5H13.5",
        "folder" to "M3.5 6H10L12 8.5H20.5V19H3.5Z",
        "folder_open" to "M3.5 19V6H9.5L11.5 8.5H18.5V11M3.5 19L6.5 11H21.5L18.5 19Z",
        "storage" to "M3.5 5H20.5V9H3.5ZM5 9V19.5H19V9M10 13H14",
        "tiles" to "M4.5 4.5H10.5V10.5H4.5ZM13.5 4.5H19.5V10.5H13.5ZM4.5 13.5H10.5V19.5H4.5ZM13.5 13.5H19.5V19.5H13.5Z",
        "list" to "M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01M9 6.5H19.5M9 12H19.5M9 17.5H19.5",
        // 받기 · 상태
        "download" to "M12 4V15M7.5 10.5L12 15L16.5 10.5M5 20H19",
        "cloud" to "M7 18.5a4 4 0 0 1-.6-7.95a5.5 5.5 0 0 1 10.7-1.3A4.7 4.7 0 0 1 17 18.5Z",
        "done" to "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0M8 12.2L11 15.2L16.2 9.5",
        "warning" to "M12 4.5L21 19.5H3ZM12 10V14.5M12 17.2h.01",
        "pin" to "M9 4H15M10 4V10L7 14H17L14 10V4M12 14V20.5",
        "select_all" to "M4.5 4.5H19.5V19.5H4.5ZM8 12L11 15L16 9",
        "copy" to "M9 8.5H19.5V20.5H9ZM5 16V4H15",
        // 읽기
        "highlight" to "M5 19l1-4L16 5l3 3L9 18zM14 7l3 3M13 20.5H20",
        "review" to "M19 12.5V20H4V5H11.5M9 15l.8-3.2L18 3.6l2.4 2.4-8.2 8.2z",
        "bookmark" to "M7 4H17V20L12 16L7 20Z",
        "brightness" to "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 3v1.5M12 19.5V21M3 12h1.5M19.5 12H21M5.6 5.6l1.1 1.1M17.3 17.3l1.1 1.1M18.4 5.6l-1.1 1.1M6.7 17.3l-1.1 1.1",
        "link" to "M10 8H7.5a4 4 0 0 0 0 8H10M14 8h2.5a4 4 0 0 1 0 8H14M8.5 12h7",
        // 오디오
        "play" to "M8 5L19 12L8 19Z",
        "pause" to "M8.5 5.5V18.5M15.5 5.5V18.5",
        "stop" to "M6.5 6.5H17.5V17.5H6.5Z",
        "prev" to "M6 5.5V18.5M19 6L10 12L19 18Z",
        "next" to "M18 5.5V18.5M5 6L14 12L5 18Z",
        "sleep" to "M19.5 14.5A8 8 0 1 1 9.5 4.5a7.1 7.1 0 0 0 10 10z",
    )

    /** Emoji (without the variation selector) -> icon name. */
    val byEmoji: Map<String, String> = mapOf(
        "⬅" to "back", "🏠" to "home", "☰" to "menu", "⋮" to "more", "🔍" to "search", "🔎" to "search",
        "⚙" to "settings", "➕" to "add", "✖" to "close", "✕" to "close", "🗑" to "delete", "📤" to "share", "🔄" to "refresh",
        "📚" to "shelf", "📖" to "book", "🎧" to "audiobook", "📄" to "file", "📁" to "folder", "📂" to "folder_open",
        "📦" to "storage", "🔲" to "tiles", "📃" to "list",
        "⬇" to "download", "☁" to "cloud", "✅" to "done", "⚠" to "warning", "📌" to "pin", "☑" to "select_all", "📋" to "copy",
        "🖍" to "highlight", "📝" to "review", "🔖" to "bookmark", "☀" to "brightness", "🔗" to "link",
        "▶" to "play", "⏸" to "pause", "⏹" to "stop", "⏮" to "prev", "⏭" to "next", "🌙" to "sleep",
    )

    /**
     * The emoji a text starts with, if it has an icon: its icon name and how many chars it takes
     * (with a following variation selector). "📚 컬렉션" -> ("shelf", 2).
     */
    fun leading(text: String): Pair<String, Int>? {
        if (text.isEmpty()) return null
        val key = String(Character.toChars(text.codePointAt(0)))
        val name = byEmoji[key] ?: return null
        var n = key.length
        if (text.getOrNull(n) == '\uFE0F') n++
        return name to n
    }

    fun nameFor(emoji: String): String? = byEmoji[emoji.replace("️", "")]
}
