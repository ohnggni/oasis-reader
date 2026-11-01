package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
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

/** One on/off setting: label (and a hint) on the left, a switch on the right. */
@Composable
fun PanelSwitch(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A row of chips where one is chosen. */
@Composable
fun PanelChoice(label: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column {
        label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> FilterChip(selected = i == selected, onClick = { onPick(i) }, label = { Text(o) }) }
        }
    }
}

/** An action in the 이동 panel: emoji + label, the whole row tappable. */
@Composable
fun PanelAction(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Page-turn effect and its speed, as chips (the curl falls back to 덮기 on phones without it). */
@Composable
fun TurnStylePicker(current: TurnStyle, speed: TurnSpeed, onPick: (TurnStyle) -> Unit, onSpeed: (TurnSpeed) -> Unit) {
    val styles = TurnStyle.entries
    PanelChoice("넘김 효과", styles.map { it.label }, styles.indexOf(current)) { onPick(styles[it]) }
    if (current == TurnStyle.CURL || current == TurnStyle.COVER) {
        val speeds = TurnSpeed.entries
        PanelChoice("넘김 속도", speeds.map { it.label }, speeds.indexOf(speed)) { onSpeed(speeds[it]) }
    }
}

/**
 * Footer of the per-book panels: whether this book has its own settings, saving them as
 * everyone's default, or going back to the defaults.
 */
@Composable
fun BookSettingsRow(hasOwn: Boolean, onSaveAsDefault: () -> Unit, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (hasOwn) "이 책에만 적용 중" else "기본 설정을 따르는 중",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSaveAsDefault) { Text("기본값으로 저장") }
        if (hasOwn) TextButton(onClick = onReset) { Text("되돌리기") }
    }
}
