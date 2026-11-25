package com.oasisreader.app.reader

import androidx.compose.material3.AlertDialog
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.TocItem
import java.io.File

/** The reader's pop-ups: 목차, the book's highlights, the picture viewer, and deleting a highlight. */
@Composable
internal fun ReaderTailDialogs(
    pkg: EpubPackage?, curIndex: Int, section: TocItem?,
    tocOpen: Boolean, onTocPick: (TocItem) -> Unit, onTocDismiss: () -> Unit,
    hlListOpen: Boolean, bookId: String, db: AppDatabase, onHlPick: (HighlightEntity) -> Unit, onHlDismiss: () -> Unit,
    viewerFile: File?, onViewerClose: () -> Unit,
    deleteTarget: String?, onDeleteDismiss: () -> Unit, onDeleteConfirm: (String) -> Unit,
) {
    if (tocOpen && pkg != null) {
        TocDialog(
            pkg = pkg,
            currentIndex = curIndex,
            current = section,
            onPick = onTocPick,
            onDismiss = onTocDismiss,
        )
    }

    if (hlListOpen) {
        BookHighlightsDialog(
            bookId = bookId,
            db = db,
            onPick = onHlPick,
            onDismiss = onHlDismiss,
        )
    }

    viewerFile?.let { f -> ImageViewer(file = f, onClose = onViewerClose) }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
            title = { Text("하이라이트 삭제") },
            text = { Text("이 하이라이트를 지우시겠습니까?") },
            confirmButton = {
                TextButton(onClick = { onDeleteConfirm(id) }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = onDeleteDismiss) { Text("취소") } },
        )
    }
}
