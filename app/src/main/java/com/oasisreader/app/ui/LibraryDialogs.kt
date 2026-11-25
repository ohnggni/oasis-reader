package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** ⋮ menu of a book: what can be done with it. */
@Composable
internal fun BookMenuDialog(
    b: BookEntity, links: List<com.oasisreader.app.data.db.BookCollectionEntity>, ctx: android.content.Context, askNotif: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope, onOpen: (BookEntity) -> Unit, onReview: (BookEntity) -> Unit,
    onSourceFolder: ((BookEntity) -> Unit)?, onPin: (BookEntity, Boolean) -> Unit,
    onDismiss: () -> Unit, onInfo: () -> Unit, onPickCol: () -> Unit, onRename: () -> Unit,
    onExport: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(b.title) },
        text = {
            Column {
                Text(
                    (if (b.format == AudioLibraryLink.FORMAT)
                        listOfNotNull(b.author, if (b.pinned && b.fileBytes > 0) "🎧 폰에 받음 · %.1f MB".format(b.fileBytes / 1048576.0) else "🎧 스트리밍 (폰에 없음)")
                    else listOfNotNull(b.author, "%.1f MB".format(b.fileBytes / 1048576.0), b.sourceUrl?.let { "서버에서 받음" }))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                // 82: straight into reading aloud (books that are text: EPUB · TXT)
                if (b.format == com.oasisreader.app.data.BookStore.FORMAT_EPUB || b.format == com.oasisreader.app.data.BookStore.FORMAT_TXT)
                    TextButton(onClick = { onDismiss(); com.oasisreader.app.reader.ReadAloudStart.pending = b.id; onOpen(b) }) { EmojiText("🔊 소리 내 읽기로 열기") }
                TextButton(onClick = { onDismiss(); onInfo() }) { Text("ℹ️ 책 정보") }
                TextButton(onClick = { onDismiss(); onReview(b) }) { EmojiText("📝 독후감") }
                TextButton(onClick = { onDismiss(); onPickCol() }) {
                    val n = links.count { it.bookId == b.id }
                    EmojiText("📚 컬렉션에 넣기" + if (n > 0) " (${n}곳에 있음)" else "")
                }
                onSourceFolder?.takeIf { com.oasisreader.app.reader.canOpenOrigin(b) }?.let { open -> TextButton(onClick = { onDismiss(); open(b) }) { EmojiText("📂 있던 폴더 열기") } }
                TextButton(onClick = { onDismiss(); onRename() }) { Text("✏️ 제목 바꾸기") }
                val isAudio = b.format == AudioLibraryLink.FORMAT
                if (isAudio) {
                    // 오프라인: copy the tracks to the phone, or go back to streaming
                    val ad = com.oasisreader.app.audio.AudioDownloads
                    val dl = ad.progress[b.id]
                    val err = ad.errors[b.id]
                    fun startDl() {
                        onDismiss()
                        askNotif()
                        ad.enqueue(ctx, b)
                        com.oasisreader.app.ui.Notice.show(ctx, "「${b.title}」 받기를 시작했습니다. 앱을 닫아도 계속 받고, 알림에서 진행을 볼 수 있습니다.")
                    }
                    err?.let { EmojiText("⚠️ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
                    val paused = remember(b.id, ad.version) { ad.isPaused(ctx, b.id) }
                    when {
                        dl != null || paused -> Row {
                            if (paused) TextButton(onClick = { ad.resume(ctx, b.id) }) { EmojiText("▶️ 이어 받기") }
                            else TextButton(onClick = { ad.pause(ctx, b.id) }) { EmojiText("⏸️ 일시정지 ${((dl ?: 0f) * 100).roundToInt()}%") }
                            TextButton(onClick = { ad.cancel(ctx, b.id) }) { EmojiText("✖️ 받기 취소") }
                        }
                        b.pinned && b.fileBytes > 0 -> {}
                        b.fileBytes > 0 -> TextButton(onClick = { startDl() }) { EmojiText("⬇️ 나머지 트랙도 받기") }
                        else -> TextButton(onClick = { startDl() }) { EmojiText("⬇️ 폰에 받기 (오프라인으로 듣기)") }
                    }
                    if (b.fileBytes > 0) TextButton(onClick = {
                        onDismiss()
                        scope.launch { ad.remove(ctx, b) }
                    }) { EmojiText("☁️ 폰에서 지우고 스트리밍으로 듣기") }
                } else TextButton(onClick = { onDismiss(); onExport() }) { EmojiText("📝 하이라이트 내보내기") }
                if (b.sourceUrl != null && !isAudio) TextButton(onClick = { onDismiss(); onPin(b, !b.pinned) }) {
                    Text(if (b.pinned) "☁️ 임시로 두기" else "📌 서재에 보관")
                }
                TextButton(onClick = { onDismiss(); onSelect() }) { EmojiText("☑️ 여러 권 고르기") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onDelete() }) {
                EmojiText("🗑️ 서재에서 지우기", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** Rename a book's title. */
@Composable
internal fun RenameBookDialog(b: BookEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(b.id) { mutableStateOf(b.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("제목 바꾸기") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onDismiss(); onSave(text.trim()) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Delete a book from the shelf (its highlights are kept unless unticked). */
@Composable
internal fun DeleteBookDialog(b: BookEntity, ctx: android.content.Context, onDismiss: () -> Unit, onDelete: (BookEntity, Boolean) -> Unit) {
    // highlights can outlive the book (하이라이트 화면 › 🗑️ 지운 책), kept unless unticked
    val hlCount by produceState(0, b.id) {
        value = com.oasisreader.app.data.db.AppDatabase.get(ctx).highlights().countFor(b.id)
    }
    var keepHl by remember(b.id) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("서재에서 지우시겠습니까?") },
        text = {
            Column {
                Text(
                    "「${b.title}」 파일이 이 폰에서 지워집니다. 독서 시간 통계는 남습니다." +
                        if (hlCount > 0 && !keepHl) " 하이라이트 ${hlCount}개도 지워집니다." else "",
                )
                if (hlCount > 0) Row(
                    Modifier.fillMaxWidth().clickable { keepHl = !keepHl }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = keepHl, onCheckedChange = { keepHl = it })
                    EmojiText("🖍️ 하이라이트·메모 ${hlCount}개는 남기기", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onDelete(b, hlCount > 0 && keepHl) }) {
                Text("지우기", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 선택 모드: take the files of the chosen books off the phone. */
@Composable
internal fun BulkRemoveFilesDialog(chosen: List<BookEntity>, app: android.content.Context, onDismiss: () -> Unit, onClear: () -> Unit) {
    val list = chosen
    val can by produceState<List<BookEntity>?>(null, list) {
        value = withContext(Dispatchers.IO) { list.filter { ShelfActions.canRemoveFiles(app, it) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { EmojiText("☁️ 폰에서 지우기") },
        text = {
            val n = can?.size
            Text(
                if (n == null) "세는 중…"
                else "${n}권의 파일을 폰에서 지웁니다. 서재·읽은 위치·하이라이트는 남고, 열면 서버에서 다시 받습니다." +
                    if (list.size > n) "\n직접 넣은 책이나 이미 폰에 없는 책 ${list.size - n}권은 그대로 둡니다." else "",
            )
        },
        confirmButton = {
            TextButton(enabled = !can.isNullOrEmpty(), onClick = {
                onDismiss()
                val go = can.orEmpty()
                onClear()
                ShelfActions.scope.launch { ShelfActions.removeFiles(app, go) }
            }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
