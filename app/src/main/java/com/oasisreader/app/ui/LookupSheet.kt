package com.oasisreader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * "찾아보기" for a selected word or sentence: the phone's text-processing apps (Papago,
 * Google Translate, dictionary apps — whatever is installed), Naver dictionary / Wikipedia
 * in the browser, or copy. The last choice moves to the top next time.
 */
object Lookup {
    data class Target(val key: String, val label: String, val icon: ImageBitmap?, val emoji: String?, val open: (Context, String) -> Unit)

    private fun enc(t: String) = java.net.URLEncoder.encode(t.trim(), "UTF-8").replace("+", "%20")

    private fun prefs(c: Context) = c.getSharedPreferences("lookup", Context.MODE_PRIVATE)

    /** Korean / Japanese / Chinese (hanzi only) / otherwise English Naver dictionary. */
    fun naverUrl(text: String): Pair<String, String> {
        val q = enc(text)
        return when {
            text.any { it in '가'..'힣' } -> "네이버 국어사전" to "https://ko.dict.naver.com/#/search?query=$q"
            text.any { it in '぀'..'ヿ' } -> "네이버 일본어사전" to "https://ja.dict.naver.com/#/search?query=$q"
            text.any { it in '一'..'鿿' } -> "네이버 중국어사전" to "https://zh.dict.naver.com/#/search?query=$q"
            else -> "네이버 영어사전" to "https://en.dict.naver.com/#/search?query=$q"
        }
    }

    fun targets(c: Context, text: String, includeHidden: Boolean = false): List<Target> {
        val pm = c.packageManager
        val out = ArrayList<Target>()
        val probe = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            .filter { it.activityInfo.packageName != c.packageName }
            .forEach { ri ->
                val pkg = ri.activityInfo.packageName; val cls = ri.activityInfo.name
                val icon = runCatching { ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                out += Target("app:$pkg/$cls", ri.loadLabel(pm).toString(), icon, null) { ctx, t ->
                    ctx.startActivity(
                        Intent(Intent.ACTION_PROCESS_TEXT).setClassName(pkg, cls).setType("text/plain")
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, t)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        val (naverLabel, naver) = naverUrl(text).let { if (text.isBlank()) "네이버 사전 (글자에 맞춰 국어·영어·일본어·중국어)" to it.second else it }
        out += Target("web:naver", naverLabel, null, "📖") { ctx, _ -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(naver)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        out += Target("web:wiki", "위키백과", null, "🌐") { ctx, t ->
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko.wikipedia.org/w/index.php?search=" + enc(t))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        out += Target("copy", "복사", null, "📋") { ctx, t ->
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", t))
            Toast.makeText(ctx, "복사했습니다.", Toast.LENGTH_SHORT).show()
        }
        return arrange(c, out, includeHidden)
    }

    // ---- order / shown (설정 › 찾아보기 앱) ----

    fun order(c: Context): List<String> = prefs(c).getString("order", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet()).orEmpty()
    fun lastFirst(c: Context): Boolean = prefs(c).getBoolean("lastFirst", true)
    fun save(c: Context, order: List<String>, hidden: Set<String>, lastFirst: Boolean) =
        prefs(c).edit().putString("order", order.joinToString("\n")).putStringSet("hidden", hidden).putBoolean("lastFirst", lastFirst).apply()

    /** The user's order (new apps at the end), the last used one first if wanted, hidden ones left out. */
    private fun arrange(c: Context, all: List<Target>, includeHidden: Boolean): List<Target> {
        val order = order(c)
        var list = all.withIndex().sortedBy { (i, t) -> order.indexOf(t.key).let { if (it < 0) 10_000 + i else it } }.map { it.value }
        if (!includeHidden) {
            val hidden = hidden(c)
            list = list.filter { it.key !in hidden }
            val last = prefs(c).getString("last", null)
            if (lastFirst(c) && last != null) list = list.sortedBy { if (it.key == last) 0 else 1 }
        }
        return list
    }

    fun remember(c: Context, t: Target) { if (t.key != "copy") prefs(c).edit().putString("last", t.key).apply() }
}

@Composable
fun LookupDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val targets = remember(text, rev) { Lookup.targets(context, text) }
    var editing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("찾아보기") },
        text = {
            Column {
                Text(
                    "「${text.trim().take(80)}${if (text.trim().length > 80) "…" else ""}」",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.padding(4.dp))
                Box {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 400.dp)) {
                        items(targets, key = { it.key }) { t ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    Lookup.remember(context, t)
                                    runCatching { t.open(context, text) }
                                        .onFailure { Toast.makeText(context, "열지 못했습니다: ${it.message}", Toast.LENGTH_SHORT).show() }
                                    onDismiss()
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TargetIcon(t)
                                Spacer(Modifier.width(14.dp))
                                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                    // more below: a fade and a hint at the bottom edge
                    if (listState.canScrollForward) {
                        val bg = AlertDialogDefaults.containerColor
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
                            Text(
                                "⌄ 아래로 더 있습니다",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().background(bg).padding(bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { editing = true }) { Text("순서·표시 편집") } },
    )
    if (editing) LookupSettingsDialog { editing = false; rev++ }
}

@Composable
private fun TargetIcon(t: Lookup.Target) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (t.icon != null) Image(t.icon, contentDescription = null, modifier = Modifier.size(28.dp))
        else Text(t.emoji.orEmpty(), fontSize = 19.sp)
    }
}

/** 찾아보기 앱: which ones show, in which order, and whether the last used one jumps to the top. */
@Composable
fun LookupSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all = remember { Lookup.targets(context, "", includeHidden = true) }
    var order by remember { mutableStateOf(all.map { it.key }) }
    var hidden by remember { mutableStateOf(Lookup.hidden(context)) }
    var lastFirst by remember { mutableStateOf(Lookup.lastFirst(context)) }
    val byKey = remember { all.associateBy { it.key } }
    fun save() = Lookup.save(context, order, hidden, lastFirst)
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in order.indices) return
        order = order.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
        save()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("찾아보기 앱") },
        text = {
            Column {
                Text(
                    "보여 줄 것을 켜고, ▲▼로 순서를 정합니다. 폰에 새로 깐 번역·사전 앱은 맨 아래에 붙습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().clickable { lastFirst = !lastFirst; save() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("마지막으로 쓴 것을 맨 위에", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = lastFirst, onCheckedChange = { lastFirst = it; save() }, modifier = Modifier.scale(0.8f))
                }
                HorizontalDivider(thickness = 0.5.dp)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(order, key = { _, k -> k }) { i, key ->
                        val t = byKey[key] ?: return@itemsIndexed
                        val shown = key !in hidden
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = shown, onCheckedChange = {
                                hidden = if (it) hidden - key else hidden + key; save()
                            })
                            TargetIcon(t)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t.label, style = MaterialTheme.typography.bodyMedium,
                                color = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            TextButton(enabled = i > 0, onClick = { move(i, -1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp)) { Text("▲") }
                            TextButton(enabled = i < order.size - 1, onClick = { move(i, 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp)) { Text("▼") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
    )
}
