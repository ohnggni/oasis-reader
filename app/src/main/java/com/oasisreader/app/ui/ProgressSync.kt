package com.oasisreader.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.oasisreader.app.data.KoSync
import kotlinx.coroutines.delay

/**
 * Progress sync for one open book (bookoasis kosync). Does nothing unless sync is set up and
 * the book has a document id.
 *  - on open (once [ready]): asks the server; a newer place from another device -> [onRemote]
 *  - while reading: uploads a few seconds after the place stops changing, when the screen
 *    goes to the background and when the book is closed — but only once the book has really
 *    been read this time (a minute, or two page turns), so a quick peek leaves no trace.
 * [place] gives (progress, percentage) for the current position; [positionKey] changes with it.
 * [differs] tells whether a server position is somewhere else than here.
 */
@Composable
fun ProgressSync(
    bookId: String,
    document: String?,
    lastReadAt: Long?,
    ready: Boolean,
    positionKey: Any,
    place: () -> Pair<String, Float>,
    differs: (KoSync.Remote) -> Boolean,
    onRemote: (KoSync.Remote) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val account = remember { KoSync.account(app) }
    if (account == null || document == null) return
    val currentPlace by rememberUpdatedState(place)
    val currentDiffers by rememberUpdatedState(differs)
    val openedAt = remember { System.currentTimeMillis() }
    var moves by remember { mutableIntStateOf(-1) }   // the first position is where the book opened
    var pulled by remember { mutableStateOf(false) }
    val startLastRead = remember { lastReadAt }

    fun read() = moves >= 2 || System.currentTimeMillis() - openedAt >= 60_000
    fun upload() {
        if (!read()) return
        val (progress, pct) = currentPlace()
        KoSync.pushInBackground(app, document, progress, pct)
    }

    LaunchedEffect(ready) {
        if (!ready || pulled) return@LaunchedEffect
        pulled = true
        KoSync.clearAhead(bookId)
        val r = KoSync.pull(account, document) ?: return@LaunchedEffect
        if (KoSync.isNewerElsewhere(app, r, startLastRead, currentDiffers(r))) onRemote(r)
    }

    LaunchedEffect(positionKey) {
        moves++
        delay(8_000)
        upload()
    }
    // reading for a minute without turning also counts
    LaunchedEffect(Unit) {
        delay(61_000)
        upload()
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) upload() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            upload()
        }
    }
}

/** "Galaxy Tab에서 42%까지 읽었습니다" — jump there, or keep reading here. */
@Composable
fun RemoteProgressDialog(where: String, onGo: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("다른 기기에서 더 읽었습니다") },
        text = { Text(where) },
        confirmButton = { TextButton(onClick = onGo) { Text("그 위치로 이동") } },
        dismissButton = { TextButton(onClick = onStay) { Text("여기서 계속") } },
    )
}

/** Device label for the dialog: the bookoasis web viewer, an OG Reader phone, a KOReader device. */
fun remoteDeviceLabel(r: KoSync.Remote): String = when {
    r.deviceId == "bookoasis-web" -> "북오아시스 웹"
    r.device.isNullOrBlank() -> "다른 기기"
    else -> r.device.removePrefix("OG Reader (").removeSuffix(")")
}
