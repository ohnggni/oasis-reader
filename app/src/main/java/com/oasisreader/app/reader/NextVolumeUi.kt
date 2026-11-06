package com.oasisreader.app.reader

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookOrigin
import com.oasisreader.app.data.NextVolume
import com.oasisreader.app.data.NextVolumes
import com.oasisreader.app.data.db.BookEntity

/** Opens [next]: straight away from the 서재, or received first (바로 읽기) from the server. */
fun openNextVolume(context: Context, next: NextVolume, onOpenBook: (String) -> Unit) {
    when (next) {
        is NextVolume.InLibrary -> onOpenBook(next.book.id)
        is NextVolume.OnServer -> {
            Toast.makeText(context, "「${next.title}」을(를) 받는 중입니다…", Toast.LENGTH_SHORT).show()
            next.receive(context) { b -> onOpenBook(b.id) }
        }
    }
}

/** ⏭️ 다음 권 from a menu: look it up (server too), then open, or say there is none. */
suspend fun findAndOpenNext(context: Context, book: BookEntity?, known: NextVolume?, onOpenBook: (String) -> Unit) {
    val b = book ?: return
    val n = known ?: NextVolumes.find(context.applicationContext, b)
    if (n == null) Toast.makeText(context, "다음 권을 찾지 못했습니다. 같은 컬렉션에 넣어 두거나 서버에서 받은 책이면 찾을 수 있습니다.", Toast.LENGTH_LONG).show()
    else openNextVolume(context, n, onOpenBook)
}

/** The book came from a server folder or series that can be shown again. */
fun canOpenOrigin(book: BookEntity?): Boolean = BookOrigin.decode(book?.origin)?.openable == true

/** The card at the end of a book: "다음 권: ○○ 2권 · ▶ 읽기", with ✕. */
@Composable
fun NextVolumeCard(next: NextVolume, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 420.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false).clickable(onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Text(
                    if (next is NextVolume.InLibrary) "다음 권 · 서재에 있음" else "다음 권 · 서버에서 받기",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text("▶ ${next.title}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "✕",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onDismiss).padding(start = 6.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
                    .semantics { contentDescription = "닫기" },
            )
        }
    }
}
