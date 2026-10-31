package com.oasisreader.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// oasis green accent
private val Oasis = Color(0xFF2F5D50)
private val OasisLight = Color(0xFF9CC8B4)

// warm paper tones, same as the reader page (beige light / dark green dark)
private val LightColors = lightColorScheme(
    primary = Oasis,
    onPrimary = Color.White,
    background = Color(0xFFF6EFE1),
    onBackground = Color(0xFF2B2620),
    surface = Color(0xFFF6EFE1),
    onSurface = Color(0xFF2B2620),
    surfaceVariant = Color(0xFFEBE2D0),
    secondaryContainer = Color(0xFFE5DAC4),
    outlineVariant = Color(0xFFDCCFB8),
)

private val DarkColors = darkColorScheme(
    primary = OasisLight,
    onPrimary = Color(0xFF0E2A21),
    background = Color(0xFF1B2420),
    surface = Color(0xFF1F2925),
    surfaceVariant = Color(0xFF2A3630),
    secondaryContainer = Color(0xFF2F3D36),
    onBackground = Color(0xFFD6DDD6),
    onSurface = Color(0xFFD6DDD6),
    onSurfaceVariant = Color(0xFFA5B1A9),
    outlineVariant = Color(0xFF34423B),
)

/** Bars sit two shades off the page (reader top/bottom bars). */
object BarColors {
    val Light = Color(0xFFDFD3BB)
    val Dark = Color(0xFF34423B)
}

@Composable
fun OasisTheme(
    darkTheme: Boolean = isAppDark(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
