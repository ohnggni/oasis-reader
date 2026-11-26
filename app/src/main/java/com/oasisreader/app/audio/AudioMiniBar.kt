package com.oasisreader.app.audio

import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.ui.RemoteCover

/** Small bar at the bottom of the library while an audiobook is loaded: title, ⏯, tap for the player. */
@Composable
fun AudioMiniBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val b = AudioPlayback.book ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // a small cover: the 서재 copy when there is one (offline too), else the server's
            val context = LocalContext.current
            val local = b.artworkFile?.let { java.io.File(it) }?.takeIf { it.isFile }
            val coverMod = Modifier.padding(vertical = 6.dp).size(40.dp).clip(RoundedCornerShape(6.dp))
            val w = AudioPlayback.web
            when {
                local != null -> com.oasisreader.app.ui.BookCover(local, b.title, coverMod)
                w != null && b.coverUrl != null -> RemoteCover(b.coverUrl, b.title, { u -> w.bytes(u) }, coverMod)
                else -> EmojiText("🎧", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${AudioPlayback.trackIndex + 1} / ${b.tracks.size} · ${hms(AudioPlayback.positionMs / 1000.0)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { AudioPlayback.toggle() }) { Text(if (AudioPlayback.playing) "⏸" else "▶", fontSize = 20.sp) }
            // stop and put the player away (the place is saved)
            IconButton(onClick = { AudioPlayback.close(context) }, modifier = Modifier.semantics { contentDescription = "재생 끝내기" }) {
                EmojiText("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 1:05:09 / 4:07 */
internal fun hms(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
