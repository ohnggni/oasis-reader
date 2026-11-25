package com.oasisreader.app.comic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 만화·PDF 하단바 (like the book's): the title and "12 / 180" on top, −/+ (one page) and a slider
 * below. While dragging only the number changes; letting go opens that page. Right-to-left books
 * run the slider the other way.
 */
@Composable
internal fun ComicSeekBar(
    title: String, page: Int, count: Int, rtl: Boolean, barColor: Color, chrome: Color, navPad: Boolean,
    onTouch: () -> Unit, onJump: (Int) -> Unit,
) {
    var drag by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(page) { drag = null }
    val p = drag ?: page
    val last = (count - 1).coerceAtLeast(1)
    fun go(t: Int) { val c = t.coerceIn(0, count - 1); drag = c; onTouch(); onJump(c) }
    Surface(color = barColor, contentColor = chrome, border = com.oasisreader.app.ui.theme.einkBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.let { if (navPad) it.navigationBarsPadding() else it }
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = chrome, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${p + 1} / $count", style = MaterialTheme.typography.labelMedium, color = chrome)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // − + : one page back / on (in reading order)
                com.oasisreader.app.ui.StepButtons(onMinus = { go(p - 1) }, onPlus = { go(p + 1) }, repeat = false)
                Spacer(Modifier.width(8.dp))
                val v = p.toFloat() / last
                com.oasisreader.app.ui.ThinSlider(
                    value = if (rtl) 1f - v else v, range = 0f..1f,
                    onChange = { f -> drag = ((if (rtl) 1f - f else f) * last).roundToInt().coerceIn(0, count - 1); onTouch() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    // let go: open the page the slider rests on
    LaunchedEffect(drag) {
        val d = drag ?: return@LaunchedEffect
        if (d == page) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        onJump(d)
    }
}
