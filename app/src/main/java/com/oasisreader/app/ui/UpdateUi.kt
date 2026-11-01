package com.oasisreader.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.BuildConfig
import com.oasisreader.app.data.AppRelease
import com.oasisreader.app.data.Updater
import kotlinx.coroutines.launch
import java.io.File

/** Library banner: a newer build on GitHub (checked when the library opens, at most hourly). */
@Composable
fun UpdateBanner() {
    val app = LocalContext.current.applicationContext
    var found by remember { mutableStateOf<AppRelease?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { found = Updater.dailyCheck(app) }

    val r = found ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "새 버전 ${r.versionName}이 있습니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { Updater.skip(app, r); found = null }) { Text("나중에") }
            TextButton(onClick = { open = true }) { Text("업데이트") }
        }
    }
    if (open) UpdateDialog(r) { open = false }
}

/** Settings row action: check now; opens the update dialog or says it is up to date. */
@Composable
fun rememberUpdateChecker(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<AppRelease?>(null) }
    found?.let { r -> UpdateDialog(r) { found = null } }
    return checking to {
        if (!checking) {
            checking = true
            scope.launch {
                val r = runCatching { Updater.latest() }
                checking = false
                r.onSuccess { rel ->
                    if (Updater.isNewer(rel)) found = rel
                    else Toast.makeText(context, "최신 버전입니다 (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "업데이트를 확인하지 못했습니다: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Release notes, then download -> system installer (asks once to allow installs from this app). */
@Composable
fun UpdateDialog(r: AppRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }   // null = not started, -1 = unknown size
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun install(file: File) {
        runCatching { context.startActivity(Updater.installIntent(app, file)) }
            .onFailure { error = "설치 화면을 열지 못했습니다: ${it.message}" }
    }

    fun start() {
        error = null
        if (!Updater.canInstall(app)) {
            Toast.makeText(context, "\"이 출처 허용\"을 켠 뒤 돌아와서 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
            runCatching { context.startActivity(Updater.allowInstallIntent(app)) }
            return
        }
        apk?.let { install(it); return }
        progress = 0f
        scope.launch {
            runCatching { Updater.download(app, r) { p -> scope.launch { progress = p } } }
                .onSuccess { apk = it; install(it) }
                .onFailure { progress = null; error = it.message ?: "다운로드하지 못했습니다" }
        }
    }

    val downloading = progress != null && apk == null
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("새 버전 ${r.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "지금 ${BuildConfig.VERSION_NAME} · 내려받기 " +
                        (if (r.apkBytes > 0) "%.1f MB".format(r.apkBytes / 1048576.0) else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.notes.isNotBlank()) {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(r.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                when {
                    apk != null -> Text("설치 화면에서 \"업데이트\"를 눌러 주세요.", style = MaterialTheme.typography.bodyMedium)
                    downloading -> {
                        val p = progress ?: 0f
                        if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(2.dp))
                Text(
                    "업데이트해도 서재·읽은 위치·하이라이트·설정은 그대로 남습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = { start() }) {
                Text(if (apk != null) "다시 설치 화면 열기" else "받아서 설치")
            }
        },
        dismissButton = { TextButton(enabled = !downloading, onClick = onDismiss) { Text("닫기") } },
    )
}
