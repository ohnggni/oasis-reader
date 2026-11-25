package com.oasisreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.unit.Dp

/** After following a link: the way back to where the reading was. */
@Composable
internal fun BoxScope.ReaderJumpPill(jumpBack: Pair<Target, Int>?, showBars: Boolean, onGo: (Target) -> Unit, onClose: () -> Unit) {
        jumpBack?.let { (from, pct) ->
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (showBars) 104.dp else 44.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↩️ 원래 보던 곳으로 · $pct%",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onClose(); onGo(from) }
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                )
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onClose() }.padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                        .semantics { contentDescription = "여기서 계속 읽기" },
                )
            }
        }
    }
}

/** 91: the top-right corner puts a bookmark in / takes it out; this page bookmarked shows a small ribbon. */
@Composable
internal fun BoxScope.ReaderBookmarkCorner(
    ready: Boolean, showBars: Boolean, panelNone: Boolean, marked: Boolean,
    readingChrome: ReadingChrome, sidePad: Dp, onToggle: () -> Unit,
) {
        // 91: the top-right corner puts a bookmark in / takes it out (where the ribbon shows)
    if (ready && !showBars && panelNone) Box(
        Modifier.align(Alignment.TopEnd).let { if (readingChrome.hideStatus) it else it.statusBarsPadding() }
            .size(56.dp)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onToggle() }
            .semantics { contentDescription = if (marked) "책갈피 빼기" else "책갈피 꽂기" },
    )

    // this page is bookmarked: a small ribbon in the top corner
    if (marked && ready && !showBars) BookmarkGlyph(
        filled = true, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), width = 11.dp,
        modifier = Modifier.align(Alignment.TopEnd).let { if (readingChrome.hideStatus) it else it.statusBarsPadding() }.padding(end = 18.dp + sidePad),
    )
}

/** Page indicator — book mode: "3 / 34 · 12%", scroll mode: "12%". */
@Composable
internal fun BoxScope.ReaderPageLabel(
    readingChrome: ReadingChrome, pageCovered: Boolean, ready: Boolean, turnShown: Boolean,
    mode: ReaderMode, pageLabel: String, overall: Float, paper: Color, chrome: Color,
) {
        // page indicator — book mode: "3 / 34 · 12%", scroll mode: "12%"
    if (readingChrome.pageNum && !pageCovered && (ready || turnShown)) {
        val pct = "${(overall * 100).roundToInt()}%"
        val label = if (mode == ReaderMode.PAGE && pageLabel.isNotEmpty()) "$pageLabel  ·  $pct" else pct
        if (mode == ReaderMode.SCROLL) {
            // the text runs under the label: give it a solid strip of page colour
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).background(paper))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = chrome.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
    }
}
