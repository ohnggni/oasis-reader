package com.oasisreader.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.oasisreader.app.reader.ReaderTheme

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
    scaleText: Boolean = true,     // false where a theme is nested in another (the reader): scale once only
    content: @Composable () -> Unit,
) {
    // A screen may ask for the other darkness than the app theme's (a book with its own reading
    // theme): then the app's reading-theme colours do not fit, and the plain light / dark set is used.
    val worn = ThemeMode.reader?.takeIf { it.dark == darkTheme }
    val scheme = if (worn != null) remember(worn) { schemeOf(worn) } else if (darkTheme) DarkColors else LightColors
    // Always the same shape, whatever the size step: switching between "provide" and "don't" at
    // 보통 (factor 1) would drop and rebuild everything below, and the app's screen (route) with it.
    val d = LocalDensity.current
    val f = if (scaleText) UiScale.factor else 1f
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * f)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Bar colour for a screen of the given darkness: the worn reading theme's rule colour when it fits. */
fun barColorFor(dark: Boolean): Color =
    ThemeMode.reader?.takeIf { it.dark == dark }?.let { if (it == ReaderTheme.EINK) Color.White else Color(it.rule) }
        ?: if (dark) BarColors.Dark else BarColors.Light

/** E-INK: outlines and dividers are a dark grey; solid black lines were too heavy next to the text. */
private val EinkLine = Color(0xFF707070)

/** E-INK: bars are white like the page, so they get an outline instead of a shade. */
fun einkBorder(): BorderStroke? = if (ThemeMode.eink) BorderStroke(1.5.dp, EinkLine) else null

/** E-INK: no fades or slides (the panel would ghost through every frame). */
fun calm(e: EnterTransition): EnterTransition = if (ThemeMode.eink) EnterTransition.None else e
fun calm(e: ExitTransition): ExitTransition = if (ThemeMode.eink) ExitTransition.None else e

/** A whole Material colour set out of a reading theme's five colours. */
private fun schemeOf(t: ReaderTheme): ColorScheme {
    val bg = Color(t.bg); val fg = Color(t.fg); val accent = Color(t.accent); val rule = Color(t.rule)
    // small grey text needs more contrast than the reading page's "muted" gives on light paper
    val soft = lerp(Color(t.muted), fg, if (t.dark) 0.10f else 0.35f)
    fun tone(f: Float) = lerp(bg, fg, f)
    val base = if (t.dark) darkColorScheme() else lightColorScheme()
    // E-INK: no greys at all. Every surface is white, every line black, and what is selected is inverted.
    if (t == ReaderTheme.EINK) return base.copy(
        primary = fg, onPrimary = bg, primaryContainer = fg, onPrimaryContainer = bg, inversePrimary = bg,
        secondary = fg, onSecondary = bg, secondaryContainer = fg, onSecondaryContainer = bg,
        tertiary = fg, onTertiary = bg, tertiaryContainer = fg, onTertiaryContainer = bg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg,
        surfaceVariant = bg, onSurfaceVariant = fg, surfaceTint = bg,
        inverseSurface = fg, inverseOnSurface = bg,
        outline = EinkLine, outlineVariant = EinkLine, scrim = fg,
        surfaceBright = bg, surfaceDim = bg,
        surfaceContainerLowest = bg, surfaceContainerLow = bg, surfaceContainer = bg,
        surfaceContainerHigh = bg, surfaceContainerHighest = bg,
        error = fg, onError = bg, errorContainer = bg, onErrorContainer = fg,
    )
    return base.copy(
        primary = accent, onPrimary = bg,
        primaryContainer = lerp(bg, accent, 0.22f), onPrimaryContainer = fg,
        inversePrimary = lerp(accent, bg, 0.5f),
        secondary = accent, onSecondary = bg,
        secondaryContainer = tone(0.10f), onSecondaryContainer = fg,
        tertiary = accent, onTertiary = bg,
        tertiaryContainer = lerp(bg, accent, 0.14f), onTertiaryContainer = fg,
        background = bg, onBackground = fg,
        surface = bg, onSurface = fg,
        surfaceVariant = tone(0.07f), onSurfaceVariant = soft,
        surfaceTint = accent,
        inverseSurface = fg, inverseOnSurface = bg,
        outline = soft, outlineVariant = rule,
        surfaceBright = bg, surfaceDim = tone(0.08f),
        surfaceContainerLowest = bg, surfaceContainerLow = tone(0.02f), surfaceContainer = tone(0.04f),
        surfaceContainerHigh = tone(0.06f), surfaceContainerHighest = tone(0.09f),
    )
}
