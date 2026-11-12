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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.reader.ReaderTheme

/** App theme list: system / light / dark and every reading theme, each with a small swatch. */
@Composable
fun ThemePickDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("앱 테마") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ThemeMode.ALL.forEach { m ->
                    val t = ThemeMode.readerOf(m)
                    val paper = Color(ReaderTheme.PAPER.bg)
                    val night = Color(ReaderTheme.NIGHT.bg)
                    val (a, b, ink) = when {
                        t != null -> Triple(Color(t.bg), Color(t.bg), Color(t.fg))
                        m == ThemeMode.LIGHT -> Triple(paper, paper, Color(ReaderTheme.PAPER.fg))
                        m == ThemeMode.DARK -> Triple(night, night, Color(ReaderTheme.NIGHT.fg))
                        else -> Triple(paper, night, Color(ReaderTheme.PAPER.fg))
                    }
                    val on = m == ThemeMode.mode
                    Row(
                        Modifier.fillMaxWidth().clickable { ThemeMode.set(app, m) }.padding(vertical = 10.dp),
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
            }
        },
    )
}
