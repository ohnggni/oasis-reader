package com.oasisreader.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A Markdown document bundled with the app (assets/[asset]), with copy and share. */
@Composable
fun DocScreen(title: String, asset: String, onBack: () -> Unit, shareable: Boolean = false, script: String? = null) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val text = remember(asset) { runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("") }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(title, onBack = onBack) {
            if (shareable) {
                EmojiButton("📋", "복사") {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
                    Toast.makeText(context, "문서 전체를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
                EmojiButton("📤", "공유") {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "$title 공유"))
                }
            }
        }
        script?.let { name ->
            // a file to run elsewhere (e.g. the server install script): copy or share it whole
            val body = remember(name) { runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("") }
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("설치 스크립트 · $name", style = MaterialTheme.typography.titleSmall)
                    Text("${body.lines().size}줄 · 서버에 저장한 뒤 bash $name 으로 실행합니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        androidx.compose.material3.TextButton(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText(name, body))
                            Toast.makeText(context, "설치 스크립트를 복사했습니다.", Toast.LENGTH_SHORT).show()
                        }) { Text("📋 설치 스크립트 복사") }
                        androidx.compose.material3.TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, name)
                                putExtra(Intent.EXTRA_TEXT, body)
                            }
                            context.startActivity(Intent.createChooser(send, "$name 공유"))
                        }) { Text("📤 공유") }
                    }
                }
            }
        }
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp).widthIn(max = 760.dp),
            ) { Markdown(text) }
        }
    }
}

/** Just enough Markdown for the bundled documents: headings, quotes, lists, tables and code blocks. */
@Composable
private fun Markdown(text: String) {
    val lines = text.lines()
    var i = 0
    val mono = FontFamily.Monospace
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val block = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) { block.add(lines[i]); i++ }
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(codeBg, RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                ) { Text(block.joinToString("\n"), fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            line.startsWith("|") -> {
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].startsWith("|")) {
                    if (!lines[i].replace(Regex("[|\\-: ]"), "").isEmpty()) rows.add(lines[i])
                    i++
                }
                i--
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
                    Text(
                        rows.joinToString("\n") { r -> r.trim('|').split('|').joinToString("  │  ") { clean(it.trim()) } },
                        fontFamily = mono, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }
            line.startsWith("# ") -> Heading(line.removePrefix("# "), 22)
            line.startsWith("## ") -> Heading(line.removePrefix("## "), 18)
            line.startsWith("### ") -> Heading(line.removePrefix("### "), 16)
            line.startsWith(">") -> Text(
                clean(line.removePrefix(">").trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
            )
            line.trimStart().startsWith("- ") -> {
                val indent = line.length - line.trimStart().length
                Row(Modifier.padding(start = (indent * 6).dp, top = 2.dp, bottom = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(clean(line.trimStart().removePrefix("- ")), style = MaterialTheme.typography.bodyMedium)
                }
            }
            line.isBlank() -> Spacer(Modifier.height(8.dp))
            line.startsWith("---") -> Spacer(Modifier.height(12.dp))
            else -> Text(clean(line), style = MaterialTheme.typography.bodyMedium)
        }
        i++
    }
}

@Composable
private fun Heading(t: String, size: Int) {
    Spacer(Modifier.height(10.dp))
    Text(clean(t), fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size + 6).sp)
    Spacer(Modifier.height(4.dp))
}

/** Drop Markdown emphasis marks; keep `code` text as it is. */
private fun clean(s: String) = s.replace("**", "").replace("`", "")
