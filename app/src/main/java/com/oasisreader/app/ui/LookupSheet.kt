package com.oasisreader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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

    fun targets(c: Context, text: String): List<Target> {
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
        val (naverLabel, naver) = naverUrl(text)
        out += Target("web:naver", naverLabel, null, "📖") { ctx, _ -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(naver)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        out += Target("web:wiki", "위키백과", null, "🌐") { ctx, t ->
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko.wikipedia.org/w/index.php?search=" + enc(t))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        out += Target("copy", "복사", null, "📋") { ctx, t ->
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", t))
            Toast.makeText(ctx, "복사했습니다.", Toast.LENGTH_SHORT).show()
        }
        val last = prefs(c).getString("last", null)
        return out.sortedBy { if (it.key == last) 0 else 1 }
    }

    fun remember(c: Context, t: Target) { if (t.key != "copy") prefs(c).edit().putString("last", t.key).apply() }
}

@Composable
fun LookupDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val targets = remember(text) { Lookup.targets(context, text) }
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
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
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
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                if (t.icon != null) Image(t.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                                else Text(t.emoji.orEmpty(), fontSize = 19.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(t.label, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
