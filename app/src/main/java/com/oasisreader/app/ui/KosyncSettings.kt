package com.oasisreader.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.KoSync
import com.oasisreader.app.data.ServerSettings
import kotlinx.coroutines.launch

/** One-line state for the settings row. */
fun kosyncSummary(context: android.content.Context): String {
    val a = KoSync.account(context) ?: return "꺼짐 · 다른 기기·KOReader와 읽은 위치를 맞춥니다"
    val host = a.baseUrl.substringAfter("://").substringBefore('/')
    return "켜짐 · ${a.username} @ $host"
}

/**
 * Set up progress sync: which catalog's bookoasis, the user name and the sync password
 * (set beforehand on the bookoasis website). Saved only after a successful login check.
 */
@Composable
fun KosyncDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val servers = remember { ServerSettings(app).all().filter { it.url.isNotBlank() && !it.isRemote } }
    val (savedServer, savedUser, hasKey) = remember { KoSync.saved(app) }
    var serverId by remember { mutableStateOf(savedServer?.takeIf { id -> servers.any { it.id == id } } ?: servers.firstOrNull()?.id) }
    val server = servers.firstOrNull { it.id == serverId }
    var username by remember { mutableStateOf(savedUser ?: server?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = remember { KoSync.isEnabled(app) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("북오아시스 진행 동기화") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "책마다 읽은 위치를 북오아시스에 올리고, 다른 기기(OG Reader·KOReader)에서 이어 읽습니다. " +
                        "만화·PDF는 북오아시스 웹의 읽은 위치와도 맞춰집니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "먼저 북오아시스 웹의 계정 메뉴 → KOReader 동기화에서 동기화 비밀번호를 정하세요. 로그인 비밀번호와 다를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (servers.isEmpty()) {
                    Text("먼저 설정 › OPDS 서버를 추가하세요.", color = MaterialTheme.colorScheme.error)
                } else if (servers.size > 1) {
                    servers.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { serverId = s.id; if (username.isBlank()) username = s.username },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.id == serverId, onClick = { serverId = s.id })
                            Text("${s.name} (${s.host})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                server?.let { s -> KoSync.baseUrl(s)?.let { Text("서버: $it", style = MaterialTheme.typography.labelSmall) } }
                OutlinedTextField(
                    value = username, onValueChange = { username = it.trim() }, singleLine = true,
                    label = { Text("북오아시스 아이디") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(if (hasKey) "동기화 비밀번호 (바꿀 때만 입력)" else "동기화 비밀번호") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (enabled) TextButton(onClick = {
                    KoSync.disable(app)
                    Toast.makeText(context, "진행 동기화를 껐습니다.", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }, modifier = Modifier.padding(top = 2.dp)) { Text("동기화 끄기", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && server != null && username.isNotBlank() && (password.isNotEmpty() || hasKey),
                onClick = {
                    val s = server ?: return@TextButton
                    val base = KoSync.baseUrl(s) ?: return@TextButton
                    val key = if (password.isNotEmpty()) KoSync.md5(password)
                        else com.oasisreader.app.data.SecureStore(app).get("kosync_key") ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        val r = KoSync.checkAuth(KoSync.Account(s.id, base, username, key))
                        busy = false
                        r.onSuccess {
                            KoSync.save(app, s.id, username, key)
                            Toast.makeText(context, "연결했습니다. 진행 동기화를 켰습니다.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }.onFailure { error = it.message ?: "연결하지 못했습니다" }
                    }
                },
            ) { Text(if (busy) "확인 중…" else "연결 확인 후 켜기") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") } },
    )
}
