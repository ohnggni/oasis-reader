package com.oasisreader.app.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import com.oasisreader.app.ui.TopBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun fmtMinutes(m: Int): String = when {
    m < 60 -> "${m}분"
    m % 60 == 0 -> "${m / 60}시간"
    else -> "${m / 60}시간 ${m % 60}분"
}

@Composable
fun StatsScreen(ui: StatsUi, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    BackHandler(onBack = onBack)
    var period by rememberSaveable { mutableIntStateOf(0) }   // 0 day, 1 month, 2 year
    var showAllBooks by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    TopBar("독서 통계", onBack = onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            if (ui.isEmpty) {
                Text(
                    "아직 쌓인 기록이 없습니다.\n책을 읽거나 오디오북을 듣거나 끝까지 읽으면 여기에 모입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            // ── summary cards ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("📖 오늘", fmtMinutes(ui.todayMinutes), Modifier.weight(1f), sub = ui.todayListenMinutes.takeIf { it > 0 }?.let { "🎧 ${fmtMinutes(it)}" })
                StatCard("🔥 연속", "${ui.streakDays}일", Modifier.weight(1f))
                StatCard("🗓 이번 달", "${ui.monthReads}권", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("📚 올해", "${ui.yearReads}권", Modifier.weight(1f))
                StatCard("⏱ 총 독서", fmtMinutes(ui.totalMinutes), Modifier.weight(1f), sub = ui.totalListenMinutes.takeIf { it > 0 }?.let { "🎧 들은 시간 ${fmtMinutes(it)}" })
                StatCard("🖍 하이라이트", "${ui.highlightsTotal}", Modifier.weight(1f), sub = "이번 달 ${ui.highlightsMonth}")
            }
            Text(
                "최근 30일 하루 평균 ${fmtMinutes(ui.avgMinutes30)} · 완독 누적 ${ui.totalReads}권",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ── period ──
            SectionTitle("기간별")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("일간", "월간", "연간").forEachIndexed { i, t ->
                    FilterChip(selected = period == i, onClick = { period = i }, label = { Text(t) })
                }
            }
            Spacer(Modifier.height(12.dp))
            when (period) {
                0 -> {
                    Heatmap(ui.heat)
                    Spacer(Modifier.height(16.dp))
                    Text("최근 7일", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    BarChart(ui.last7, byMinutes = true)
                }
                1 -> BarChart(ui.monthly, byMinutes = false)
                else -> BarChart(ui.yearly, byMinutes = false)
            }

            // ── books ──
            SectionTitle("책별 독서 시간 (🎧 들은 시간 포함)")
            val list = if (showAllBooks) ui.books else ui.books.take(8)
            val maxMin = (list.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
            if (list.isEmpty()) {
                Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.forEach { b -> BookRow(b, maxMin, onClick = { onOpenBook(b.id) }) }
            if (ui.books.size > 8) {
                TextButton(onClick = { showAllBooks = !showAllBooks }) {
                    Text(if (showAllBooks) "접기" else "모두 보기 (${ui.books.size})")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(
        t,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun heatLevel(min: Int, reads: Int): Int = when {
    min >= 60 -> 4
    min >= 30 -> 3
    min >= 10 -> 2
    min >= 1 || reads > 0 -> 1
    else -> 0
}

/** GitHub-style: columns = weeks, rows = Mon..Sun. */
@Composable
private fun Heatmap(weeks: List<List<DayCell>>) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val levels = listOf(empty, base.copy(alpha = 0.25f), base.copy(alpha = 0.45f), base.copy(alpha = 0.7f), base)
    val cell = 14.dp
    val gap = 3.dp

    Row(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE))) {
        Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(end = 4.dp, top = 16.dp)) {
            listOf("월", "", "수", "", "금", "", "일").forEach { d ->
                Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                    Text(d, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        weeks.forEachIndexed { wi, week ->
            Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(end = gap)) {
                // month label when the month changes
                val first = week.first().date
                val showMonth = wi == 0 || week.any { it.date.dayOfMonth == 1 }
                Box(Modifier.height(13.dp).width(cell)) {
                    if (showMonth) {
                        val m = week.firstOrNull { it.date.dayOfMonth == 1 }?.date ?: first
                        Text("${m.monthValue}월", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
                week.forEach { c ->
                    Box(
                        Modifier
                            .size(cell)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (c.inFuture) Color.Transparent else levels[heatLevel(c.minutes, c.reads)])
                    )
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text("적음", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        levels.forEach { Box(Modifier.padding(horizontal = 1.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(it)) }
        Spacer(Modifier.width(4.dp))
        Text("많음 (분 기준)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vertical bars: reads (편) with minutes underneath, or minutes only for daily bars. */
@Composable
private fun BarChart(bars: List<Bar>, byMinutes: Boolean) {
    val values = bars.map { if (byMinutes) it.minutes else it.reads }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { i, b ->
            val v = values[i]
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(
                    if (v == 0) "" else if (byMinutes) "${v}" else "$v",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height((110f * v / max).coerceAtLeast(if (v > 0) 3f else 1f).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (v > 0) color else MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(4.dp))
                Text(b.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
    Text(
        if (byMinutes) "단위: 분" else "단위: 완독 권수 · 독서 시간 " + fmtMinutes(bars.sumOf { it.minutes }),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun BookRow(b: BookStat, maxMin: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (b.finished) "✅ " else "") + (if (b.audio) "🎧 " else "") + b.title,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                fmtMinutes(b.minutes) + if (b.finished) "" else " · ${(b.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth(b.minutes.toFloat() / maxMin).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
