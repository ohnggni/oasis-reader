package com.oasisreader.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
                        }) { EmojiText("📋 설치 스크립트 복사") }
                        androidx.compose.material3.TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, name)
                                putExtra(Intent.EXTRA_TEXT, body)
                            }
                            context.startActivity(Intent.createChooser(send, "$name 공유"))
                        }) { EmojiText("📤 공유") }
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
            // <details><summary>제목</summary> … </details>: folded until tapped
            line.trimStart().startsWith("<details>") -> {
                val title = Regex("<summary>(.*?)</summary>").find(line)?.groupValues?.get(1)?.trim().orEmpty()
                val block = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("</details>")) { block.add(lines[i]); i++ }
                androidx.compose.runtime.key(i) { Details(title, block.joinToString("\n")) }
            }
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
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].startsWith("|")) {
                    // skip the |---|---| separator line
                    if (lines[i].replace(Regex("[|\\-: ]"), "").isNotEmpty()) rows.add(lines[i].trim().trim('|').split('|').map { it.trim() })
                    i++
                }
                i--
                MdTable(rows)
            }
            line.startsWith("# ") -> Heading(line.removePrefix("# "), 22)
            line.startsWith("## ") -> Heading(line.removePrefix("## "), 18)
            line.startsWith("### ") -> Heading(line.removePrefix("### "), 16)
            line.startsWith(">") -> Text(
                rich(line.removePrefix(">").trim()),
                inlineContent = ogInline(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
            )
            line.trimStart().startsWith("- ") -> {
                val indent = line.length - line.trimStart().length
                Row(Modifier.padding(start = (indent * 6).dp, top = 2.dp, bottom = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(rich(line.trimStart().removePrefix("- ")), inlineContent = ogInline(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            line.isBlank() -> Spacer(Modifier.height(8.dp))
            line.startsWith("---") -> Spacer(Modifier.height(12.dp))
            else -> Text(rich(line), inlineContent = ogInline(), style = MaterialTheme.typography.bodyMedium)
        }
        i++
    }
}

@Composable
private fun Details(title: String, body: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(if (open) "▾  " else "▸  ", style = MaterialTheme.typography.bodyMedium)
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (open) Column(Modifier.padding(start = 8.dp, top = 6.dp)) { Markdown(body) }
    }
}

@Composable
private fun Heading(t: String, size: Int) {
    Spacer(Modifier.height(10.dp))
    Text(clean(t), fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size + 6).sp)
    Spacer(Modifier.height(4.dp))
}

/**
 * A real table: one row per line, cells side by side. Columns get a share of the width by how
 * much text they hold (so short columns stay narrow), the header row is bold on a tint.
 */
@Composable
private fun MdTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    val weights = (0 until cols).map { c ->
        rows.maxOf { r -> clean(r.getOrNull(c).orEmpty()).length }.coerceIn(3, 26).toFloat()
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val tint = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .border(0.5.dp, line, RoundedCornerShape(6.dp)),
    ) {
        rows.forEachIndexed { ri, r ->
            Row(
                Modifier.fillMaxWidth().then(if (ri == 0) Modifier.background(tint) else Modifier)
                    .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            ) {
                (0 until cols).forEach { c ->
                    Text(
                        rich(r.getOrNull(c).orEmpty()),
                        inlineContent = ogInline(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (ri == 0) FontWeight.SemiBold else null,
                        modifier = Modifier.weight(weights[c]).padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                    if (c < cols - 1) Box(Modifier.width(0.5.dp).fillMaxHeight().background(line))
                }
            }
            if (ri < rows.size - 1) Box(Modifier.fillMaxWidth().height(0.5.dp).background(line))
        }
    }
}

/** Inline Markdown: **bold**, `code`, [text](url), and bare https:// addresses or e-mail addresses as links. */
@Composable
private fun rich(s: String): androidx.compose.ui.text.AnnotatedString {
    val linkStyle = androidx.compose.ui.text.TextLinkStyles(
        androidx.compose.ui.text.SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        ),
    )
    val icons = OgIcons.style != OgIcons.EMOJI
    return remember(s, linkStyle, icons) {
        val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s)]+)|([\w.+-]+@[\w-]+(?:\.[\w-]+)+)""")
        androidx.compose.ui.text.buildAnnotatedString {
            var last = 0
            for (m in re.findAll(s)) {
                appendWithIcons(s.substring(last, m.range.first), icons)
                val g = m.groupValues
                when {
                    g[1].isNotEmpty() -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { appendWithIcons(g[1], icons) }
                    g[2].isNotEmpty() -> append(g[2])
                    g[3].isNotEmpty() -> withLink(androidx.compose.ui.text.LinkAnnotation.Url(g[4], linkStyle)) { append(g[3]) }
                    g[5].isNotEmpty() -> withLink(androidx.compose.ui.text.LinkAnnotation.Url(g[5], linkStyle)) { append(g[5]) }
                    g[6].isNotEmpty() -> withLink(androidx.compose.ui.text.LinkAnnotation.Url("mailto:" + g[6], linkStyle)) { append(g[6]) }
                }
                last = m.range.last + 1
            }
            appendWithIcons(s.substring(last), icons)
        }
    }
}

/** Drop Markdown emphasis marks; keep `code` text as it is. */
private fun clean(s: String) = s.replace("**", "").replace("`", "")
