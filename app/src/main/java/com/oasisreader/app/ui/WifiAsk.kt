package com.oasisreader.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.audio.AudioDownloads
import com.oasisreader.app.data.BookQueue
import com.oasisreader.app.data.DownloadPrefs

/**
 * 103: receiving the person asks for while "와이파이에서만 받기" would keep it waiting. Every such
 * place goes through [run]; background work (다음 회차 미리 받기, 동기화) does not.
 */
object WifiAsk {
    internal var waiting by mutableStateOf<List<() -> Unit>>(emptyList())
        private set

    /** [receive] at once when nothing would wait; else after the choice ([WifiAskDialog]), or never (취소). */
    fun run(context: Context, receive: () -> Unit) {
        if (!DownloadPrefs.mustWait(context)) receive() else waiting = waiting + receive
    }

    internal fun cancel() { waiting = emptyList() }

    /** Into the queue as it is (it comes once Wi-Fi is there), and the phone's Wi-Fi panel. */
    internal fun turnOnWifi(context: Context) {
        proceed()
        Toast.makeText(context.applicationContext, "와이파이에 연결되면 받습니다.", Toast.LENGTH_SHORT).show()
        runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }

    /** The switch off — what already waits starts over mobile data too — then this one. */
    internal fun useMobileData(context: Context) {
        AudioDownloads.setWifiOnly(context, false)
        BookQueue.rekick(context)
        proceed()
    }

    private fun proceed() {
        val list = waiting
        waiting = emptyList()
        list.forEach { it() }
    }
}

/** Hosted once over the whole app (MainActivity). */
@Composable
fun WifiAskDialog() {
    if (WifiAsk.waiting.isEmpty()) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { WifiAsk.cancel() },
        title = { Text("📶 와이파이 연결 없음") },
        text = {
            Column {
                WifiChoice("와이파이 켜기", "연결되면 받습니다.") { WifiAsk.turnOnWifi(context) }
                WifiChoice("와이파이에서만 받기 끄기", "모바일 데이터로 받습니다.") { WifiAsk.useMobileData(context) }
            }
        },
        confirmButton = { TextButton(onClick = { WifiAsk.cancel() }) { Text("취소") } },
    )
}

@Composable
private fun WifiChoice(title: String, note: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
