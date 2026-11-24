package com.oasisreader.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.reader.ReaderTheme

/** App theme list: system / light / dark and every reading theme, each with a small swatch. 시스템 따라가기 also picks what to wear when light and when dark (99). */
@Composable
fun ThemePickDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var pickFor by remember { mutableStateOf<Boolean?>(null) }   // null: the main list · false: 밝을 때 · true: 어두울 때
    AlertDialog(
        onDismissRequest = { if (pickFor != null) pickFor = null else onDismiss() },
        confirmButton = { TextButton(onClick = { if (pickFor != null) pickFor = null else onDismiss() }) { Text(if (pickFor != null) "뒤로" else "닫기") } },
        title = { Text(when (pickFor) { null -> "앱 테마"; false -> "밝을 때"; true -> "어두울 때" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val dark = pickFor
                if (dark == null) {
                    ThemeMode.ALL.forEach { m -> ThemeLine(m, m == ThemeMode.mode) { ThemeMode.set(app, m) } }
                    if (ThemeMode.mode == ThemeMode.SYSTEM) {
                        Text("시스템 따라가기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        PickLine("밝을 때", ThemeMode.lightPick) { pickFor = false }
                        PickLine("어두울 때", ThemeMode.darkPick) { pickFor = true }
                    }
                } else {
                    val cur = if (dark) ThemeMode.darkPick else ThemeMode.lightPick
                    ThemeMode.PICKS.forEach { m -> ThemeLine(m, m == cur) { ThemeMode.setPick(app, dark, m); pickFor = null } }
                }
            }
        },
    )
}

@Composable
private fun PickLine(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(ThemeMode.label(value) + " ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One theme with its swatch. */
@Composable
private fun ThemeLine(m: String, on: Boolean, onClick: () -> Unit) {
    val t = ThemeMode.readerOf(m)
    val paper = Color(ReaderTheme.PAPER.bg)
    val night = Color(ReaderTheme.NIGHT.bg)
    val (a, b, ink) = when {
        t != null -> Triple(Color(t.bg), Color(t.bg), Color(t.fg))
        m == ThemeMode.LIGHT -> Triple(paper, paper, Color(ReaderTheme.PAPER.fg))
        m == ThemeMode.DARK -> Triple(night, night, Color(ReaderTheme.NIGHT.fg))
        else -> Triple(paper, night, Color(ReaderTheme.PAPER.fg))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(Brush.linearGradient(listOf(a, b)), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("가", color = ink, fontSize = 12.sp) }
        Spacer(Modifier.width(14.dp))
        Text(
            ThemeMode.label(m),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (on) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}
