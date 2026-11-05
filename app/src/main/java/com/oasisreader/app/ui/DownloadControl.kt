package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.BookQueue

/**
 * The receive button of a book in a list: ⬇️ starts, a ring with ⏸ while it comes (tap = pause),
 * ▶️ when paused (tap = go on; 🔁 after a failure), and ✖️ next to it to cancel.
 * [key] is the queue key (OPDS entry id or the meta catalog's key).
 */
@Composable
fun DownloadControl(key: String, progress: Float?, onStart: () -> Unit, size: Dp = 40.dp) {
    val context = LocalContext.current
    val v = BookQueue.version
    val job = remember(key, v) { BookQueue.get(context, key) }
    val ring = size * 0.6f
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            progress != null -> Box(
                Modifier.size(size).clickable { BookQueue.pause(context, key) }.semantics { contentDescription = "일시정지" },
                contentAlignment = Alignment.Center,
            ) {
                if (progress < 0f) CircularProgressIndicator(Modifier.size(ring), strokeWidth = 2.5.dp)
                else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(ring), strokeWidth = 2.5.dp)
                Text("⏸", fontSize = (size.value * 0.28f).sp, color = MaterialTheme.colorScheme.primary)
            }
            job != null && job.state != BookQueue.State.WAITING -> Box(
                Modifier.size(size).clickable { BookQueue.resume(context, key) }
                    .semantics { contentDescription = if (job.state == BookQueue.State.FAILED) "다시 받기" else "이어 받기" },
                contentAlignment = Alignment.Center,
            ) { Text(if (job.state == BookQueue.State.FAILED) "🔁" else "▶️", style = MaterialTheme.typography.titleMedium) }
            else -> Box(
                Modifier.size(size).clickable(onClick = onStart).semantics { contentDescription = "서재에 담기" },
                contentAlignment = Alignment.Center,
            ) { Text("⬇️", style = MaterialTheme.typography.titleMedium) }
        }
        if (progress != null || job != null) Text(
            "✖️", fontSize = 13.sp,
            modifier = Modifier.clickable { BookQueue.cancel(context, key) }.padding(4.dp).semantics { contentDescription = "받기 취소" },
        )
    }
}
