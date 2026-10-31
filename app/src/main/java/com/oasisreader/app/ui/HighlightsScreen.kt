package com.oasisreader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.HighlightEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All highlights, newest book first. Tap one to open the book right there. */
@Composable
fun HighlightsScreen(onBack: () -> Unit, onOpen: (HighlightEntity) -> Unit) {
    BackHandler(onBack = onBack)
    val db = AppDatabase.get(LocalContext.current.applicationContext)
    val highlights by remember { db.highlights().observeAll() }.collectAsState(initial = null)
    val books by remember { db.books().observeAll() }.collectAsState(initial = emptyList())
    val titles = remember(books) { books.associate { it.id to it.title } }
    val fmt = remember { SimpleDateFormat("yyyy.M.d", Locale.KOREA) }

    // group by book, books ordered by their newest highlight; inside a book, reading order
    val groups = remember(highlights) {
        highlights.orEmpty().groupBy { it.bookId }
            .entries.sortedByDescending { e -> e.value.maxOf { it.createdAt } }
            .map { it.key to it.value.sortedWith(compareBy<HighlightEntity> { h -> h.spineIndex }.thenBy { h -> h.createdAt }) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("하이라이트", onBack = onBack)
        if (highlights != null && highlights!!.isEmpty()) {
            Text(
                "아직 하이라이트가 없습니다.\n책에서 글을 길게 눌러 고르고 색을 누르면 여기에 모입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groups.forEach { (bookId, list) ->
                item(key = "h_$bookId") {
                    Text(
                        "📖 " + (titles[bookId] ?: "지운 책"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    )
                }
                items(list, key = { it.id }) { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = titles.containsKey(bookId)) { onOpen(h) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Box(
                            Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp))
                                .background(parseColor(h.color))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(
                                fmt.format(Date(h.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFFFF176))

