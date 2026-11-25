package com.oasisreader.app.reader

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** A touch that only ends a panel or the bars (no page turn): everything under it is swallowed, [onEnd] runs when the finger is up. */
internal fun Modifier.swallowTouch(onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
        onEnd()
    }
}

/** Fetch the files of a book that is not on this phone: [onRetry] opens it again, [onEnd] runs at the end either way. */
internal fun fetchMissingBook(
    scope: kotlinx.coroutines.CoroutineScope, app: android.content.Context, context: android.content.Context,
    bookId: String, url: String, onRetry: () -> Unit, onEnd: () -> Unit,
) {
    scope.launch {
        try {
            com.oasisreader.app.data.Downloads.receiveMissing(app, bookId, url)
            onRetry()
        } catch (e: Exception) {
            com.oasisreader.app.ui.Notice.show(context, "다시 받기 실패: ${e.message}")
        } finally {
            onEnd()
        }
    }
}

internal fun copyToClipboard(context: android.content.Context, t: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("text", t))
    // Android 13+ shows its own copied notice
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
}
