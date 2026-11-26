package com.oasisreader.app.reader

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * A layer over the page that takes a tap or swipe for itself and answers it with [onEnd] when the
 * finger lifts — but not one the system took away for its back gesture: that touch ends already
 * consumed (cancelled), and answering it would put the bars away before back arrives (104).
 */
internal fun Modifier.swallowTouch(onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        var taken: Boolean
        do {
            val e = awaitPointerEvent()
            taken = e.changes.any { it.isConsumed }
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
        if (!taken) onEnd()
    }
}

/** Fetch the files of a book that is not on this phone: [onRetry] opens it again, [onEnd] runs at the end either way. */
internal fun fetchMissingBook(
    scope: kotlinx.coroutines.CoroutineScope, app: android.content.Context, context: android.content.Context,
    bookId: String, url: String, mobile: Boolean, onRetry: () -> Unit, onEnd: () -> Unit,
) {
    scope.launch {
        try {
            if (com.oasisreader.app.data.Downloads.receiveOrQueue(app, bookId, url, mobile)) onRetry()
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
