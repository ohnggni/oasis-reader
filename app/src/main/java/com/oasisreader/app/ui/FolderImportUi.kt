package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.FolderImport
import com.oasisreader.app.data.Storage

/** While the picked folder is being looked through. */
@Composable
fun FolderScanningDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { EmojiText("📁 폴더를 살펴보는 중…") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.padding(horizontal = 8.dp)); Text("하위 폴더까지 책 파일을 찾고 있습니다") } },
        confirmButton = {},
    )
}

/** What importing the folder will do, and how to group the books into 컬렉션. */
@Composable
fun FolderImportDialog(plan: FolderImport.Plan, onDismiss: () -> Unit, onStart: (FolderImport.Group) -> Unit) {
    val context = LocalContext.current
    val free = remember { context.applicationContext.filesDir.usableSpace }
    var group by remember { mutableStateOf(if (plan.folders > 1) FolderImport.Group.BOOK_FOLDER else FolderImport.Group.PICKED_FOLDER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { EmojiText("📁 「${plan.rootName}」 가져오기") },
        text = {
            Column {
                if (plan.items.isEmpty()) {
                    Text(
                        if (plan.already > 0) "새로 가져올 책이 없습니다. 이 폴더의 ${plan.already}권은 이미 서재에 있습니다."
                        else "이 폴더와 하위 폴더에 EPUB · TXT · PDF · CBZ/ZIP 파일이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("책 ${plan.items.size}권 · ${Storage.label(plan.bytes)} (폴더 ${plan.folders}개)", style = MaterialTheme.typography.bodyLarge)
                    if (plan.already > 0) Text("이미 서재에 있는 ${plan.already}권은 건너뜁니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "폰 남은 공간 ${Storage.label(free)} · 책은 앱 안으로 복사됩니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (plan.bytes * 2 > free) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    EmojiText("📚 컬렉션으로 묶기", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        FolderImport.Group.BOOK_FOLDER to "책 폴더마다 (예: 「${plan.items.first().folder}」)",
                        FolderImport.Group.PICKED_FOLDER to "「${plan.rootName}」 하나로",
                        FolderImport.Group.NONE to "묶지 않기",
                    ).forEach { (g, label) ->
                        Row(Modifier.fillMaxWidth().clickable { group = g }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = group == g, onClick = { group = g })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "앱을 닫아도 계속 가져오고, 알림에 진행 상황이 나옵니다. 나중에 폴더에 책을 더 넣고 다시 가져오면 새 책만 추가됩니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { if (plan.items.isNotEmpty()) TextButton(onClick = { onStart(group) }) { Text("가져오기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (plan.items.isEmpty()) "닫기" else "취소") } },
    )
}
