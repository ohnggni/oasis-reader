package com.oasisreader.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-wide light / dark choice: follow the system, or force one. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL = listOf(SYSTEM, LIGHT, DARK)

    var mode by mutableStateOf(SYSTEM)
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun load(c: Context) { mode = prefs(c).getString("theme", SYSTEM) ?: SYSTEM }

    fun set(c: Context, m: String) {
        mode = m
        prefs(c).edit().putString("theme", m).apply()
    }

    fun label(m: String) = when (m) { LIGHT -> "라이트"; DARK -> "다크"; else -> "시스템 따라가기" }
}

/** Use instead of isSystemInDarkTheme(): honours the app's own choice. */
@Composable
fun isAppDark(): Boolean = when (ThemeMode.mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    else -> isSystemInDarkTheme()
}
