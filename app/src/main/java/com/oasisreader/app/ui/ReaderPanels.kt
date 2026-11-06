package com.oasisreader.app.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures

/*
 * The pieces of the reading screens' four panels (화면 · 글자/보기 · 쪽/넘김 · 이동), shared by the
 * book reader and the comic/PDF viewer so both look and behave the same.
 */

/** The four panel buttons in the top bar. [second] / [third] name the middle two for this screen. */
enum class ReadPanel { NONE, SCREEN, TEXT, PAGE, NAV;
    fun toggle(p: ReadPanel) = if (this == p) NONE else p
}

/** Small heading inside a panel. */
@Composable
fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** One on/off setting in a single short line (hints stay out: panels must leave the page visible). */
@Composable
fun PanelSwitch(
    label: String, @Suppress("UNUSED_PARAMETER") hint: String?, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true,
) {
    Row(
        modifier.height(36.dp).clickable(enabled = enabled) { onChange(!checked) }
            .let { if (enabled) it else it.alpha(0.45f) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.scale(0.75f))
    }
}

/** A setting line: a short label column, then the control(s). */
@Composable
fun PanelRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        content()
    }
}

/**
 * Slim slider: 3dp track, small dot (the stock one is far too tall for a reading panel).
 * With [step], small − + buttons sit before it (one place for both thumbs is easier than the two ends).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    modifier: Modifier = Modifier, steps: Int = 0, enabled: Boolean = true, step: Float = 0f,
) {
    val accent = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (step > 0f) {
            StepButtons(
                onMinus = { onChange((value - step).coerceIn(range.start, range.endInclusive)) },
                onPlus = { onChange((value + step).coerceIn(range.start, range.endInclusive)) },
                enabled = enabled,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
            thumb = { Box(Modifier.size(14.dp).background(if (enabled) accent else rest, CircleShape)) },
            track = { st ->
                androidx.compose.material3.SliderDefaults.Track(
                    sliderState = st, modifier = Modifier.height(3.dp), enabled = enabled,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        activeTrackColor = accent, inactiveTrackColor = rest,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                        disabledActiveTrackColor = rest, disabledInactiveTrackColor = rest,
                    ),
                    drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                )
            },
            modifier = Modifier.weight(1f).height(28.dp),
        )
    }
}

/** − and + side by side, each in a round rim. Held down, they repeat ([repeat]). */
@Composable
fun StepButtons(onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true, repeat: Boolean = true) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        StepButton("−", "줄이기", onMinus, enabled, repeat)
        StepButton("+", "늘리기", onPlus, enabled, repeat)
    }
}

@Composable
private fun StepButton(glyph: String, label: String, onClick: () -> Unit, enabled: Boolean, repeat: Boolean) {
    val latest by androidx.compose.runtime.rememberUpdatedState(onClick)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .border(1.3.dp, if (enabled) accent.copy(alpha = 0.7f) else line, CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(enabled, repeat) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    latest()
                    val job = if (repeat) scope.launch {
                        kotlinx.coroutines.delay(420)
                        while (true) { latest(); kotlinx.coroutines.delay(90) }
                    } else null
                    tryAwaitRelease()
                    job?.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 15.sp, lineHeight = 15.sp, color = if (enabled) accent else line)
    }
}

/** A choice from a list, shown as one small "value ▾" button that opens a drop-down. */
@Composable
fun PanelDropdown(
    current: String, items: List<String>, onPick: (Int) -> Unit, modifier: Modifier = Modifier,
    footer: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp))
                .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, label ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { CheckMark(label == current) },
                    onClick = { open = false; onPick(i) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(40.dp),
                )
            }
            footer?.invoke { open = false }
        }
    }
}

/** A labelled choice from a short list (a drop-down). */
@Composable
fun PanelChoice(label: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    PanelRow(label.orEmpty()) { PanelDropdown(options.getOrElse(selected) { "" }, options, onPick) }
}

/** An action in the 이동 panel: emoji + label, the whole row tappable. */
@Composable
fun PanelAction(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Page-turn effect and (for curl / cover) its speed, on one line. */
@Composable
fun TurnStylePicker(current: TurnStyle, speed: TurnSpeed, onPick: (TurnStyle) -> Unit, onSpeed: (TurnSpeed) -> Unit) {
    val styles = TurnStyle.entries
    val speeds = TurnSpeed.entries
    PanelRow("넘김") {
        PanelDropdown(current.label, styles.map { it.label }, { onPick(styles[it]) })
        if (current == TurnStyle.CURL || current == TurnStyle.COVER) {
            Spacer(Modifier.width(8.dp))
            PanelDropdown(speed.label, speeds.map { it.label }, { onSpeed(speeds[it]) })
        }
    }
}

/**
 * Footer of the per-book panels: whether this book has its own settings, saving them as
 * everyone's default, or going back to the defaults.
 */
@Composable
fun BookSettingsRow(hasOwn: Boolean, onSaveAsDefault: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (hasOwn) "이 책에만 적용 중" else "기본 설정을 따르는 중",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSaveAsDefault, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("기본값으로 저장", style = MaterialTheme.typography.labelMedium)
        }
        if (hasOwn) TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("되돌리기", style = MaterialTheme.typography.labelMedium)
        }
    }
}
