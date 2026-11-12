package com.oasisreader.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 서재로 가기: [home] opens the 서재 from anywhere (the 🏠 button of the server screens), [to]
 * opens it at one book — its 종류 tab, its 컬렉션 or 묶음, scrolled to it. MainActivity watches
 * [seq] for the route, LibraryScreen takes [pendingBook] and then [scrollTo].
 */
object ShelfJump {
    var seq by mutableIntStateOf(0)
        private set
    var handled = 0
    var pendingBook by mutableStateOf<String?>(null)
    var scrollTo by mutableStateOf<String?>(null)

    fun home() { pendingBook = null; seq++ }
    fun to(bookId: String) { pendingBook = bookId; seq++ }
}

/** 🏠 서재로: on the server screens, where the 서재 can be several steps back. */
@Composable
fun HomeButton() = EmojiButton("🏠", "서재로") { ShelfJump.home() }

/**
 * Messages that would not fit a Toast (Android 12+ shows two lines, then "…"): a card at the
 * bottom that shows every line, goes after a few seconds (longer for longer text), tap to close.
 * Short messages still go as a Toast.
 *
 * [added]: "서재에 담았습니다" with a way there — the card stays 5 seconds (the ring runs out),
 * and 서재에서 보기 opens the 서재 at that book.
 */
object Notice {
    private const val TOAST_MAX = 34
    const val ADDED_MS = 5_000

    var text by mutableStateOf<String?>(null)
        private set
    var seq by mutableIntStateOf(0)
        private set
    /** the book 서재에서 보기 goes to (null: a plain message) */
    var book by mutableStateOf<String?>(null)
        private set

    fun show(context: Context, message: String) {
        if (message.length <= TOAST_MAX) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        book = null
        text = message
        seq++
    }

    /** A book (or the first of several) went into the 서재. */
    fun added(message: String, bookId: String) {
        book = bookId
        text = message
        seq++
    }

    fun dismiss() { text = null; book = null }
}

/** Hosted once over the whole app (MainActivity). */
@Composable
fun NoticeHost() {
    val t = Notice.text
    val target = Notice.book
    val ring = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Notice.seq) {
        val shown = Notice.text ?: return@LaunchedEffect
        if (Notice.book != null) {
            ring.snapTo(1f)
            if (com.oasisreader.app.ui.theme.ThemeMode.eink) delay(Notice.ADDED_MS.toLong())
            else ring.animateTo(0f, androidx.compose.animation.core.tween(Notice.ADDED_MS, easing = androidx.compose.animation.core.LinearEasing))
        } else delay(3_500L + shown.length * 45L)
        if (Notice.text == shown) Notice.dismiss()
    }
    // what is being received now (TODO 44): a ring with the percent, gone when it is done
    val rec by com.oasisreader.app.data.Downloads.receiving.collectAsState()
    val lastRec = androidx.compose.runtime.remember { arrayOfNulls<com.oasisreader.app.data.Downloads.Receiving>(1) }
    rec?.let { lastRec[0] = it }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      androidx.compose.foundation.layout.Column(
          Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 72.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
      ) {
        AnimatedVisibility(visible = rec != null, enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut())) {
            val r = lastRec[0]
            if (r != null) Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 480.dp),
            ) {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        if (r.fraction >= 0f) androidx.compose.material3.CircularProgressIndicator(
                            progress = { r.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.inversePrimary,
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f),
                        ) else androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(36.dp), color = MaterialTheme.colorScheme.inversePrimary, strokeWidth = 3.dp,
                        )
                        if (r.fraction >= 0f) Text("${(r.fraction * 100).toInt()}", style = MaterialTheme.typography.labelSmall)
                    }
                    androidx.compose.foundation.layout.Column(Modifier.padding(start = 12.dp).weight(1f, fill = false)) {
                        Text(
                            r.title.ifBlank { "받는 중" }, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "⬇️ 받는 중" + (if (r.fraction >= 0f) " ${(r.fraction * 100).toInt()}%" else "…") + (if (r.left > 1) " · 남은 ${r.left}권" else ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = t != null, enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut())) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 480.dp).clickable { Notice.dismiss() },
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (target != null) 3 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    if (target != null) androidx.compose.foundation.layout.Row(
                        Modifier.clickable(onClickLabel = "서재에서 보기") { Notice.dismiss(); ShelfJump.to(target) }
                            .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // the ring runs out in 5 seconds: then the card goes
                            androidx.compose.material3.CircularProgressIndicator(
                                progress = { ring.value },
                                modifier = Modifier.size(30.dp),
                                color = MaterialTheme.colorScheme.inversePrimary,
                                strokeWidth = 3.dp,
                                trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f),
                            )
                            EmojiText("📚", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            "서재에서 보기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inversePrimary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
      }
    }
}
