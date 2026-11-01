package com.oasisreader.app.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.HighlightExport
import com.oasisreader.app.data.db.BookmarkEntity
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Search state kept by the reader, so reopening the search shows the last results. */
class FindState {
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<BookSearch.Hit>?>(null)
    var busy by mutableStateOf(false)
    var searched by mutableStateOf("")
    internal var job: Job? = null
}

/** Search the whole book; tapping a result opens the book there with the match marked. */
@Composable
fun FindDialog(
    state: FindState,
    store: BookStore,
    bookId: String,
    pkg: EpubPackage,
    onPick: (BookSearch.Hit, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.hits == null) runCatching { focusRequester.requestFocus() } }

    fun run() {
        val q = state.query.trim()
        if (q.isEmpty()) return
        keyboard?.hide(); focus.clearFocus()
        state.job?.cancel()
        state.busy = true
        state.job = scope.launch {
            val r = runCatching { BookSearch.search(store, bookId, pkg, q) }.getOrDefault(emptyList())
            state.hits = r; state.searched = q; state.busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("본문 검색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    placeholder = { Text("찾을 낱말") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                val hits = state.hits
                when {
                    state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("찾는 중…", style = MaterialTheme.typography.bodySmall)
                    }
                    hits == null -> {}
                    hits.isEmpty() -> Text("「${state.searched}」이(가) 없습니다.", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(
                            if (hits.size >= BookSearch.MAX_HITS) "${BookSearch.MAX_HITS}건 이상 (앞의 ${BookSearch.MAX_HITS}건)" else "${hits.size}건",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val accent = MaterialTheme.colorScheme.primary
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(hits, key = { "${it.spineIndex}:${it.nth}" }) { h ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { onPick(h, state.searched) }.padding(vertical = 8.dp),
                                ) {
                                    val chapter = remember(h.spineIndex) { HighlightExport.chapterTitle(pkg, h.spineIndex) }
                                    Text(
                                        listOfNotNull(chapter.ifBlank { null }, "${(pkg.overall(h.spineIndex, h.fraction) * 100).roundToInt()}%").joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildAnnotatedString {
                                            append(h.before)
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(h.match) }
                                            append(h.after)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = state.query.isNotBlank() && !state.busy, onClick = { run() }) { Text("찾기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** The book's bookmarks: tap to go there, ✕ to remove. [where] labels a bookmark's place. */
@Composable
fun BookmarksDialog(
    list: List<BookmarkEntity>,
    where: (BookmarkEntity) -> String,
    onPick: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("M.d HH:mm", Locale.KOREA) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("책갈피") },
        text = {
            if (list.isEmpty()) Text(
                "아직 없습니다. 상단바의 책갈피 표시를 누르면 지금 쪽이 저장됩니다.",
                style = MaterialTheme.typography.bodyMedium,
            ) else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(list, key = { it.id }) { b ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookmarkGlyph(filled = true, color = MaterialTheme.colorScheme.primary, width = 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${where(b)} · ${fmt.format(Date(b.createdAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(b.snippet.ifBlank { "(내용 없음)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onDelete(b) }) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** A ribbon bookmark shape (outlined or filled). Height is 1.6 × [width]. */
@Composable
fun BookmarkGlyph(filled: Boolean, color: Color, width: Dp = 14.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 1.6f)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w / 2, h * 0.74f); lineTo(0f, h); close()
        }
        if (filled) drawPath(p, color)
        else drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.14f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Is this bookmark the page on screen? Book mode: within half a page; scroll mode: within 1% of the chapter. */
fun bookmarkHere(b: BookmarkEntity, spine: Int, fraction: Float, pagedTotal: Int?): Boolean {
    if (b.spineIndex != spine) return false
    val tol = if (pagedTotal != null && pagedTotal > 1) 0.5f / (pagedTotal - 1) else 0.01f
    return kotlin.math.abs(b.fraction - fraction) <= tol
}
