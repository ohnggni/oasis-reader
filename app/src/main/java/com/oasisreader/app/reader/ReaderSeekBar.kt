package com.oasisreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.epub.EpubPackage
import kotlin.math.roundToInt

/**
 * Where a server position points in this book. OG Reader positions are exact percentages;
 * a KOReader xpointer gives the chapter (/body/DocFragment[n]), placed inside it by percentage.
 */
internal fun remoteTarget(p: EpubPackage, r: com.oasisreader.app.data.KoSync.Remote): Pair<Int, Float>? {
    if (p.spine.isEmpty()) return null
    val pct = r.percentage
    if (r.fromOgReader && pct != null) return seekTarget(p, pct)
    val frag = Regex("""DocFragment\[(\d+)]""").find(r.progress.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
    if (frag != null && frag in p.spine.indices) {
        val within = if (pct == null) 0f
            else ((pct * p.totalChars - p.charsBefore(frag)) / p.spine[frag].chars.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        return frag to within
    }
    return pct?.let { seekTarget(p, it) }
}

/** Whole-book fraction -> (spine index, fraction inside that chapter), by character counts. */
internal fun seekTarget(p: EpubPackage, v: Float): Pair<Int, Float> {
    if (p.spine.isEmpty()) return 0 to 0f
    val target = v.coerceIn(0f, 1f) * p.totalChars
    var before = 0L
    p.spine.forEachIndexed { i, s ->
        val n = s.chars.coerceAtLeast(1)
        if (target < before + n || i == p.spine.lastIndex) return i to ((target - before) / n).toFloat().coerceIn(0f, 1f)
        before += n
    }
    return p.spine.lastIndex to 1f
}

/** Bottom bar: chapter title and % of the place under the thumb, over a thin whole-book slider. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSeekBar(
    pkg: EpubPackage,
    overall: Float,
    barColor: Color,
    chrome: Color,
    onDrag: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    // keep showing where we jumped to until the book reports its new place
    LaunchedEffect(overall) { drag = null }
    val v = drag ?: overall
    val chapter = remember(pkg, v) { chapterTitleFor(pkg, seekTarget(pkg, v).first) }
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = barColor, contentColor = chrome, border = com.oasisreader.app.ui.theme.einkBorder(), modifier = Modifier.fillMaxWidth()) {
        // lifted well clear of the bottom edge: with gesture navigation a swipe up from there
        // means home / recent apps, and the slider must not catch it (it may cover a line of text)
        Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapter.ifEmpty { " " },
                    style = MaterialTheme.typography.labelMedium,
                    color = chrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${(v * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = chrome)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
            // − + : 1% of the book back / on (no repeat: each one opens the place)
            com.oasisreader.app.ui.StepButtons(
                onMinus = { val t = (v - 0.01f).coerceIn(0f, 1f); drag = t; onDrag(); onSeek(t) },
                onPlus = { val t = (v + 0.01f).coerceIn(0f, 1f); drag = t; onDrag(); onSeek(t) },
                repeat = false,
            )
            Spacer(Modifier.width(8.dp))
            Slider(
                value = v,
                onValueChange = { drag = it; onDrag() },
                onValueChangeFinished = { drag?.let(onSeek) },
                thumb = {
                    Box(Modifier.size(14.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                },
                track = { st ->
                    androidx.compose.material3.SliderDefaults.Track(
                        sliderState = st,
                        modifier = Modifier.height(3.dp),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            activeTrackColor = accent,
                            inactiveTrackColor = chrome.copy(alpha = 0.25f),
                        ),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
                modifier = Modifier.weight(1f).height(28.dp),
            )
            }
        }
    }
}
