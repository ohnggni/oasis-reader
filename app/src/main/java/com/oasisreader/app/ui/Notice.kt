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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Messages that would not fit a Toast (Android 12+ shows two lines, then "…"): a card at the
 * bottom that shows every line, goes after a few seconds (longer for longer text), tap to close.
 * Short messages still go as a Toast.
 */
object Notice {
    private const val TOAST_MAX = 34

    var text by mutableStateOf<String?>(null)
        private set
    var seq by mutableIntStateOf(0)
        private set

    fun show(context: Context, message: String) {
        if (message.length <= TOAST_MAX) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        text = message
        seq++
    }

    fun dismiss() { text = null }
}

/** Hosted once over the whole app (MainActivity). */
@Composable
fun NoticeHost() {
    val t = Notice.text
    LaunchedEffect(Notice.seq) {
        val shown = Notice.text ?: return@LaunchedEffect
        delay(3_500L + shown.length * 45L)
        if (Notice.text == shown) Notice.dismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = t != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 72.dp)
                    .widthIn(max = 480.dp).clickable { Notice.dismiss() },
            ) {
                Text(t.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}
