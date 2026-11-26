package com.oasisreader.app.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EntryRow(
    entry: OpdsEntry,
    client: OpdsClient,
    owned: Boolean,
    showDate: Boolean = false,
    progress: Float?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isBook) {
            RemoteCover(entry.thumb, entry.title, load = { client.bytes(it) }, modifier = Modifier.width(44.dp).height(64.dp))
        } else {
            Box(Modifier.width(44.dp).height(44.dp), contentAlignment = Alignment.Center) {
                EmojiText("📁", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(entry.author, if (showDate) entry.updated?.let { shortDate(it) } else null).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.isBook) {
            Spacer(Modifier.width(8.dp))
            when {
                owned -> EmojiText("✅", style = MaterialTheme.typography.titleMedium)
                entry.readable != null -> DownloadControl(entry.id, progress, onDownload)
                else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EntryTile(
    entry: OpdsEntry,
    client: OpdsClient,
    owned: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            if (entry.isBook) {
                RemoteCover(entry.thumb, entry.title, load = { client.bytes(it) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.68f))
            } else {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.68f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { EmojiText("📁", style = MaterialTheme.typography.displaySmall) }
            }
            if (entry.isBook) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).heightIn(min = 36.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        owned -> EmojiText("✅")
                        entry.readable != null -> DownloadControl(entry.id, progress, onDownload, size = 32.dp)
                        else -> Text("—")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
