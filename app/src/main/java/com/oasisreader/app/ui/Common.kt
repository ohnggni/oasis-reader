package com.oasisreader.app.ui

import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.ui.theme.BarColors

@Composable
fun barColor(): Color = if (isAppDark()) BarColors.Dark else BarColors.Light

/** Plain top bar in the reader's bar colour (two shades off the page). */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    brand: Boolean = false,          // app header: logo + bold name
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = barColor(), contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(if (brand) 72.dp else 56.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 16.dp))
            }
            if (brand) {
                BrandMark(Modifier.weight(1f))
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            actions()
        }
    }
}

@Composable
fun EmojiButton(emoji: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
    }
}

/** Brush script for the app's name (Kaushan Script, printable ASCII only, renamed per the OFL). */
val BrandFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.oasisreader.app.R.font.brand_script),
)

/** App header: the OG mark two lines tall, then a small "Book" over a large "Reader". */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    fun line(size: Int, height: Int, spacing: Float) = androidx.compose.ui.text.TextStyle(
        fontFamily = BrandFont,
        fontSize = size.sp,
        lineHeight = height.sp,
        letterSpacing = spacing.sp,
        color = color,
        // Trim.None: the script face is ~1.45 em tall, and with a line height below that
        // Trim.Both puts the font's own ascent/descent back — that was the gap between the lines
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
    )
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "OG Book Reader" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.oasisreader.app.R.drawable.logo_og),
            contentDescription = null,
            modifier = Modifier.size(50.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Book", style = line(13, 14, 1.5f), maxLines = 1)
            Text("Reader", style = line(37, 36, 0f), maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * Two ways to browse a bookoasis server: the organized catalog (oasis-meta) or its OPDS folders.
 * Shown only when the server has a meta API.
 */
@Composable
fun CatalogViewSwitch(organized: Boolean, onOrganized: () -> Unit, onFolders: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.FilterChip(
            selected = organized, onClick = { if (!organized) onOrganized() },
            label = { Text("📚 시리즈별 보기") },
        )
        androidx.compose.material3.FilterChip(
            selected = !organized, onClick = { if (organized) onFolders() },
            label = { Text("📂 폴더별 보기") },
        )
    }
}

/**
 * While [active] (reading full screen), the system back gesture is switched off on both screen
 * edges so a page-turn swipe from the edge is not taken as "back". Android allows this on at
 * most 200dp of each edge, so it covers the middle band of the screen.
 */
@Composable
fun NoEdgeBackGesture(active: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(active, view) {
        fun apply() {
            if (!active || view.height <= 0) { view.systemGestureExclusionRects = emptyList(); return }
            val band = with(density) { 200.dp.roundToPx() }.coerceAtMost(view.height)
            val edge = with(density) { 48.dp.roundToPx() }
            val top = (view.height - band) / 2
            view.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, top, edge, top + band),
                android.graphics.Rect(view.width - edge, top, view.width, top + band),
            )
        }
        apply()
        val l = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        view.addOnLayoutChangeListener(l)
        onDispose {
            view.removeOnLayoutChangeListener(l)
            view.systemGestureExclusionRects = emptyList()
        }
    }
}
