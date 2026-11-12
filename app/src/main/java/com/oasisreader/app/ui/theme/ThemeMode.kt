package com.oasisreader.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oasisreader.app.reader.ReaderTheme

/**
 * App-wide theme: follow the system, force light / dark, or wear one of the reading themes
 * (stored by the reading theme's id) so the shelf and menus take its colours too (TODO 46).
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL: List<String>
        get() = listOf(SYSTEM, LIGHT, DARK) + ReaderTheme.entries.filter { it != ReaderTheme.AUTO }.map { it.id }

    fun readerOf(m: String): ReaderTheme? = ReaderTheme.entries.firstOrNull { it != ReaderTheme.AUTO && it.id == m }

    /** The reading theme the app is wearing, or null for system / light / dark. */
    val reader: ReaderTheme? get() = readerOf(mode)

    var mode by mutableStateOf(SYSTEM)
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun load(c: Context) { mode = prefs(c).getString("theme", SYSTEM) ?: SYSTEM }

    fun set(c: Context, m: String) {
        mode = m
        prefs(c).edit().putString("theme", m).apply()
    }

    fun label(m: String) = when (m) { LIGHT -> "라이트"; DARK -> "다크"; SYSTEM -> "시스템 따라가기"; else -> readerOf(m)?.label ?: "시스템 따라가기" }
}

/** Use instead of isSystemInDarkTheme(): honours the app's own choice. */
@Composable
fun isAppDark(): Boolean = when (ThemeMode.mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    else -> ThemeMode.reader?.dark ?: isSystemInDarkTheme()
}
