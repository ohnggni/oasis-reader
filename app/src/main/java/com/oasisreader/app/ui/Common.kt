package com.oasisreader.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.ui.theme.BarColors

@Composable
fun barColor(): Color = com.oasisreader.app.ui.theme.barColorFor(isAppDark())

/** Plain top bar in the reader's bar colour (two shades off the page). */
/**
 * Top inset for screens outside the reader, where the status bar is always shown: its height even
 * while it is (still) hidden, so the bar never slides under it when the insets come back late
 * after leaving the reader (S20 FE and other One UI 5 phones, TODO 28).
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.shelfTopInset(): Modifier = composed { windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility) }

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    brand: Boolean = false,          // app header: logo + bold name
    brandSub: String? = null,        // app header: one small line under the logo
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = barColor(), contentColor = MaterialTheme.colorScheme.onSurfaceVariant, border = com.oasisreader.app.ui.theme.einkBorder()) {
        Row(
            Modifier.fillMaxWidth().shelfTopInset().height(if (brand) 60.dp else 56.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 16.dp))
            }
            if (brand) {
                BrandMark(brandSub, Modifier.weight(1f))
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
        val icon = OgIcons.forEmoji(emoji)
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        else Text(emoji, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * App header: "OG", an open book and "Reader" (a vector drawing, outlines of Nunito
 * Black) and one small line under it, in the style of a radio app's station header.
 */
@Composable
private fun BrandMark(sub: String?, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull("OG Reader", sub).joinToString(", ") },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(if (isAppDark()) com.oasisreader.app.R.drawable.logo_brand_dark else com.oasisreader.app.R.drawable.logo_brand),
            contentDescription = null,
            modifier = Modifier.size(width = 172.59.dp, height = 21.77.dp),
        )
        if (!sub.isNullOrBlank()) Text(
            sub,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * Two ways to browse a bookoasis server: the organized catalog (oasis-meta) or its OPDS folders.
 * Shown only when the server has a meta API.
 */
@Composable
fun CatalogViewSwitch(organized: Boolean, onOrganized: () -> Unit, onFolders: () -> Unit) {
    // tabs, so they read apart from the filter chips below
    androidx.compose.material3.TabRow(
        selectedTabIndex = if (organized) 0 else 1,
        containerColor = barColor(),
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        listOf(true to "📚 시리즈별", false to "📂 폴더별").forEach { (o, label) ->
            androidx.compose.material3.Tab(
                selected = organized == o,
                onClick = { if (o != organized) { if (o) onOrganized() else onFolders() } },
                modifier = Modifier.height(42.dp),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (organized == o) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * A low search box (about 40dp instead of a text field's 56): 🔍, the text, ✖️ to clear.
 * [onSearch] runs on the keyboard's search key.
 */
@Composable
fun CompactSearchField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, onSearch: () -> Unit,
    modifier: Modifier = Modifier, onClear: (() -> Unit)? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.height(40.dp),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxSize()
                    .border(1.dp, outline.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                EmojiText("🔍", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) Text(
                    "✖️", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onValueChange(""); onClear?.invoke() }.padding(8.dp),
                )
            }
        },
    )
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
