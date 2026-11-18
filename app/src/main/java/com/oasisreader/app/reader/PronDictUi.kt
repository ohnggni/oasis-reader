package com.oasisreader.app.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.ui.EmojiButton
import com.oasisreader.app.ui.ShelfActions
import com.oasisreader.app.ui.Text
import kotlinx.coroutines.launch

/** The row in the voice dialog that opens the dictionary. */
@Composable
fun PronDictRow(ra: ReadAloud) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { PronDict.load(ctx) } }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("발음 사전", style = MaterialTheme.typography.labelLarge)
            Text(
                if (PronDict.entries.isEmpty()) "이름·외래어처럼 잘못 읽는 말을 바로잡습니다" else "${PronDict.entries.size}개",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium)
    }
    if (open) PronDictDialog(ra, onDismiss = { open = false })
}

/**
 * 발음 사전: add "표현 → 읽는 법", hear it, change or delete. Plain text, no patterns; the
 * longest match wins. Applies to every book, on top of 숫자·기호 읽기.
 */
@Composable
fun PronDictDialog(ra: ReadAloud, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var word by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }   // word being changed
    fun clear() { word = ""; reading = ""; editing = null }
    fun save() {
        val w = word; val r = reading; val old = editing
        ShelfActions.scope.launch { runCatching { PronDict.put(ctx, w, r, old) }; ra.respeak() }
        clear()
    }
    val ok = word.isNotBlank() && reading.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("발음 사전") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("책에 나오는 말을 이렇게 읽습니다. 모든 책에 쓰입니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = word, onValueChange = { word = it.take(PronDict.MAX_WORD) },
                    label = { Text("표현 (예: GPT)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reading, onValueChange = { reading = it.take(PronDict.MAX_READING) },
                    label = { Text("읽는 법 (예: 지피티)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = reading.isNotBlank() || word.isNotBlank(), onClick = {
                        // hear the reading as the book would: dictionary (with this entry) + numbers
                        val dict = PronDict.entries.filter { it.first != editing } + (if (ok) listOf(word.trim() to reading.trim()) else emptyList())
                        val text = ReadAloudNormalize.speak(word.ifBlank { reading }.trim(), dict, ra.normalize)
                        ra.preview(null, text)
                    }) { Text("▶ 들어 보기") }
                    if (editing != null) TextButton(onClick = { clear() }) { Text("취소") }
                    Row(Modifier.weight(1f)) {}
                    TextButton(enabled = ok, onClick = { save() }) { Text(if (editing != null) "고치기" else "추가") }
                }
                HorizontalDivider(thickness = 0.5.dp)
                if (PronDict.entries.isEmpty()) {
                    Text("아직 없습니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                } else LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(PronDict.entries, key = { it.first }) { (w, r) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { word = w; reading = r; editing = w },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(w, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("→ $r", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            EmojiButton("▶️", "들어 보기") { ra.preview(null, r) }
                            EmojiButton("🗑️", "지우기") {
                                if (editing == w) clear()
                                ShelfActions.scope.launch { runCatching { PronDict.remove(ctx, w) }; ra.respeak() }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
