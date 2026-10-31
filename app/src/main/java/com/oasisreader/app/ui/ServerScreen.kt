package com.oasisreader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.opds.OpdsClient
import kotlinx.coroutines.launch

/** Add (serverId == null) or edit one OPDS catalog. */
@Composable
fun ServerScreen(serverId: String?, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    val settings = remember { ServerSettings(context.applicationContext) }
    val initial = remember {
        settings.get(serverId)
            ?: Server("", if (settings.all().isEmpty()) "북오아시스" else "", "", "", "")
    }
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var meta by remember { mutableStateOf(initial.metaUrl) }
    var metaDb by remember { mutableStateOf(initial.metaDb) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun current() = Server(initial.id, name.trim(), url.trim().trimEnd('/'), user.trim(), pass, meta.trim().trimEnd('/'), metaDb)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(if (initial.id.isEmpty()) "OPDS 서버 추가" else "OPDS 서버 설정", onBack = onDone) {
            if (initial.id.isNotEmpty()) EmojiButton("🗑️", "서버 삭제") { confirmDelete = true }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(20.dp)
                .widthIn(max = 560.dp),
        ) {
            Text(
                "OPDS 주소와 로그인(Basic 인증) 계정입니다. 공개 카탈로그면 아이디·비밀번호를 비워두세요. 비밀번호는 이 폰 안에서만 암호화해서 보관합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("이름 (예: 북오아시스)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it; status = null },
                label = { Text("OPDS 주소") }, singleLine = true,
                placeholder = { Text("https://book.example.com/opds") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            val plain = url.trim().startsWith("http://", ignoreCase = true)
            Text(
                if (plain) "⚠️ http:// 는 암호화되지 않습니다. 아이디·비밀번호와 책이 그대로 오가니 집 안 네트워크(예: 192.168.x.x)에서만 쓰세요."
                else "북오아시스 OPDS 주소입니다. 보통 서버 주소 뒤에 /opds 를 붙입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = if (plain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it; status = null },
                label = { Text("아이디") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it; status = null },
                label = { Text("비밀번호") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = meta, onValueChange = { meta = it; status = null },
                label = { Text("메타 API 주소 (선택)") }, singleLine = true,
                placeholder = { Text("https://meta.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "북오아시스 옆에 직접 띄우는 oasis-meta 주소입니다 (없으면 비워두세요 · 만드는 법은 설정 › 메타 API 안내). 있으면 시리즈·표지·정리된 제목·실제 날짜·줄거리로 된 목록을 보여 줍니다. 같은 아이디/비밀번호를 씁니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("메타 DB", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                listOf("general" to "일반", "adult" to "성인").forEach { (v, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = metaDb == v,
                        onClick = { metaDb = v; status = null },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Text(
                "북오아시스 성인 DB 카탈로그면 \"성인\"을 고르세요 (관리자 계정만 볼 수 있습니다).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !busy && current().isComplete,
                    onClick = {
                        busy = true; status = "확인하는 중…"
                        scope.launch {
                            status = try {
                                val feed = OpdsClient(current()).fetch(current().url)
                                val metaLine = if (current().metaUrl.isBlank()) "" else try {
                                    val libs = com.oasisreader.app.meta.MetaApi(current()).libraries()
                                    "\n✅ 메타 API (${if (current().metaDb == "adult") "성인" else "일반"} DB) · 라이브러리 ${libs.size}개"
                                } catch (e: Exception) {
                                    "\n⚠️ 메타 API: ${e.message ?: e.javaClass.simpleName}"
                                }
                                "✅ 연결되었습니다 · ${feed.title} (${feed.entries.size}개 항목)$metaLine"
                            } catch (e: Exception) {
                                "⚠️ ${e.message ?: e.javaClass.simpleName}"
                            }
                            busy = false
                        }
                    },
                ) { Text("연결 확인") }
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy && current().isComplete,
                    onClick = { settings.save(current()); onDone() },
                ) { Text("저장") }
            }
            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${initial.name}」 서버를 지우시겠습니까?") },
            text = { Text("서재에 받아둔 책은 그대로 남습니다.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { settings.delete(initial.id); confirmDelete = false; onDone() }) {
                    Text("지우기", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** Every catalog, to edit them or add one more. */
@Composable
fun ServerListScreen(onBack: () -> Unit, onEdit: (String?) -> Unit, onOpen: ((String) -> Unit)? = null) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val servers = remember { ServerSettings(context.applicationContext).all() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(if (onOpen != null) "어디서 받으시겠습니까?" else "OPDS 서버", onBack = onBack) {
            EmojiButton("➕", "서버 추가") { onEdit(null) }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (servers.isEmpty()) {
                Text(
                    "등록한 서버가 없습니다. ➕로 추가하세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            servers.forEach { s ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (onOpen != null) onOpen(s.id) else onEdit(s.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("☁️", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(s.username.ifBlank { null }, s.url).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (onOpen != null) EmojiButton("⚙️", "서버 설정") { onEdit(s.id) }
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
