package com.oasisreader.app.reader

import com.oasisreader.app.data.BookStore
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

internal const val ASSET_HOST = BookStore.ASSET_HOST
internal const val BAR_DP = 52
/** Height of the top bar itself; the page keeps reserving [BAR_DP] + 10 so text does not reflow. */
internal const val TOP_BAR_DP = 60
internal const val RUNNING_HEAD_DP = 30
internal const val SIDEWAYS_PAD_DP = 24
internal const val SIDEWAYS_EXTRA_BOTTOM = 14   // a little more air above the page label
internal val CompactPad = PaddingValues(horizontal = 8.dp)

/** Fold is open and turned sideways: the hinge runs horizontally. */
@Composable
internal fun rememberSideways(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val info by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val cfg = LocalConfiguration.current
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    if (fold != null) return fold.orientation == FoldingFeature.Orientation.HORIZONTAL
    // No hinge reported (Samsung often reports none while the fold lies flat): the big inner
    // screen (smallest width >= 600dp; the cover screen is far below) held taller than wide.
    return cfg.smallestScreenWidthDp >= 600 && cfg.screenHeightDp > cfg.screenWidthDp
}

/**
 * true = show two facing pages.
 * Foldables report a FoldingFeature: spread only while it is vertical (hinge between
 * left and right pages). Other devices: wide landscape windows only. [spreadMode] (읽기 기본값 ›
 * 두 쪽 보기) can turn that into 한 쪽만 or 가로일 때만 (a Fold held sideways).
 */
@Composable
internal fun rememberSpread(spreadMode: Int = 0): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val cfg = LocalConfiguration.current
    val info by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val wide = cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp
    if (spreadMode == 1) return false
    if (spreadMode == 2) return wide
    return if (fold != null) {
        fold.orientation == FoldingFeature.Orientation.VERTICAL
    } else {
        cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp
    }
}

/** 두 쪽 보기 choices, in [ReaderPrefs.spreadMode] order. */
internal val SPREAD_LABELS = listOf("자동", "한 쪽만", "가로일 때만")

/** Which margin is being set (0 위 · 1 아래 · 2 왼쪽 · 3 오른쪽) — the Aa card goes to the top for 아래 (97). */
internal object MarginEdit { var side by mutableIntStateOf(0) }

/** The panels under Aa (90), shown in the bottom card. */
internal val AA_PANELS = setOf(Panel.SCREEN, Panel.TEXT, Panel.PAGE)

internal enum class Panel {
    NONE, SCREEN, TEXT, PAGE, NAV;
    fun toggle(p: Panel) = if (this == p) NONE else p
}

@Composable
internal fun MenuEmoji(e: String) = Text(e, fontSize = 17.sp)

@Composable
internal fun PanelLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** -1 = follow the system brightness. */
@Composable
internal fun BrightnessPanel(value: Float, onChange: (Float) -> Unit) {
    val auto = value < 0f
    com.oasisreader.app.ui.PanelRow("밝기") {
        com.oasisreader.app.ui.ThinSlider(
            value = if (auto) 0.5f else value, range = 0.02f..1f,
            onChange = { onChange(it.coerceIn(0.02f, 1f)) },
            modifier = Modifier.weight(1f).alpha(if (auto) 0.45f else 1f),
            step = 0.05f,
        )
        Spacer(Modifier.width(8.dp))
        com.oasisreader.app.ui.PanelDropdown(if (auto) "자동" else "직접", listOf("자동", "직접"), { onChange(if (it == 0) -1f else 0.5f) })
    }
}

@Composable
internal fun SizePanel(
    weight: Int, onWeight: (Int) -> Unit,
    value: Int, onChange: (Int) -> Unit,
    lineHeight: Int, onLineHeight: (Int) -> Unit,
) {
    com.oasisreader.app.ui.PanelRow("크기") {
        com.oasisreader.app.ui.ThinSlider(
            value = value.toFloat(),
            range = ReaderPrefs.MIN_FONT.toFloat()..ReaderPrefs.MAX_FONT.toFloat(),
            steps = ReaderPrefs.MAX_FONT - ReaderPrefs.MIN_FONT - 1,
            onChange = { onChange(it.roundToInt()) },
            modifier = Modifier.weight(1f),
            step = 1f,
        )
        Text("${value}px", style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 40.dp), textAlign = TextAlign.End)
    }
    com.oasisreader.app.ui.PanelRow("줄간격") {
        com.oasisreader.app.ui.ThinSlider(
            value = lineHeight.toFloat(), range = 14f..24f, steps = 9,
            onChange = { onLineHeight(it.roundToInt()) },
            modifier = Modifier.weight(1f),
            step = 1f,
        )
        Text(String.format("%.1f", lineHeight / 10f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 40.dp), textAlign = TextAlign.End)
    }
    val weights = listOf("보통", "조금 굵게", "굵게")
    com.oasisreader.app.ui.PanelRow("굵기") {
        com.oasisreader.app.ui.PanelDropdown(weights.getOrElse(weight) { weights[0] }, weights, onWeight)
    }
}

@Composable
internal fun FontPanel(
    fonts: FontManager,
    selectedId: String,
    busyId: String?,
    progress: Float,
    onPick: (FontOption) -> Unit,
    onAdd: () -> Unit,
    onRemove: (FontOption) -> Unit,
) {
    // one drop-down: my fonts, then 명조, then 고딕 (⬇ = downloads on first use)
    val all = FontManager.customs + FontManager.ALL.filter { it.kind == FontOption.Kind.SERIF } + FontManager.ALL.filter { it.kind != FontOption.Kind.SERIF }
    fun name(f: FontOption): String {
        val base = f.label.substringBefore(" ·")
        return when {
            busyId == f.id -> "$base ${(progress * 100).toInt()}%"
            f in FontManager.customs -> "$base (내 글꼴)"
            !fonts.isReady(f) -> "⬇ $base"
            else -> base
        }
    }
    val current = all.firstOrNull { it.id == selectedId }
    com.oasisreader.app.ui.PanelRow("글꼴") {
        com.oasisreader.app.ui.PanelDropdown(
            current?.let(::name) ?: "기본",
            all.map(::name),
            { i -> if (busyId == null) onPick(all[i]) },
            modifier = Modifier.weight(1f, fill = false),
            footer = { close ->
                HorizontalDivider()
                DropdownMenuItem(text = { Text("＋ 글꼴 파일 추가") }, onClick = { close(); onAdd() })
                current?.takeIf { it in FontManager.customs }?.let { f ->
                    DropdownMenuItem(
                        text = { Text("이 글꼴 삭제", color = MaterialTheme.colorScheme.error) },
                        onClick = { close(); onRemove(f) },
                    )
                }
            },
        )
    }
}

/** Page margins: pick a side, then one slim slider for it (relative to the default; 0 = as before). */
@Composable
internal fun MarginPanel(top: Int, bottom: Int, left: Int, right: Int, onChange: (Int, Int, Int, Int) -> Unit) {
    val side = MarginEdit.side
    val sides = listOf("위", "아래", "왼쪽", "오른쪽")
    val v = listOf(top, bottom, left, right)[side]
    val range = if (side < 2) ReaderPrefs.MARGIN_V else ReaderPrefs.MARGIN_H
    fun set(x: Int) = when (side) {
        0 -> onChange(x, bottom, left, right)
        1 -> onChange(top, x, left, right)
        2 -> onChange(top, bottom, x, right)
        else -> onChange(top, bottom, left, x)
    }
    com.oasisreader.app.ui.PanelRow("여백") {
        com.oasisreader.app.ui.PanelDropdown(sides[side], sides, { MarginEdit.side = it })
        Spacer(Modifier.width(6.dp))
        com.oasisreader.app.ui.ThinSlider(
            value = v.toFloat(), range = range.first.toFloat()..range.last.toFloat(),
            onChange = { set((it / 4f).roundToInt() * 4) },
            modifier = Modifier.weight(1f),
            step = 4f,
        )
        Text(if (v == 0) "기본" else if (v > 0) "+$v" else "$v", style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 36.dp), textAlign = TextAlign.End)
        if (top != 0 || bottom != 0 || left != 0 || right != 0) {
            TextButton(onClick = { onChange(0, 0, 0, 0) }, contentPadding = CompactPad) { Text("초기화", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Page colour swatches in one short line: tap to choose; the half/half one ("자동") follows light/dark. */
@Composable
internal fun ThemeRow(current: ReaderTheme, onPick: (ReaderTheme) -> Unit) {
    com.oasisreader.app.ui.PanelRow("배경") {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderTheme.entries.forEach { t ->
                Box(
                    Modifier.size(26.dp)
                        .background(
                            if (t == ReaderTheme.AUTO) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(ReaderTheme.PAPER.bg), Color(ReaderTheme.NIGHT.bg)))
                            else androidx.compose.ui.graphics.SolidColor(Color(t.bg)),
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .border(
                            if (t == current) 2.dp else 0.8.dp,
                            if (t == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .clickable { onPick(t) }
                        .semantics { contentDescription = t.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("가", color = Color(if (t == ReaderTheme.AUTO) ReaderTheme.PAPER.fg else t.fg), fontSize = 11.sp)
                }
            }
        }
    }
}
