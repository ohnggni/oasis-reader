package com.oasisreader.app.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oasisreader.app.ui.Text
import kotlinx.coroutines.launch

private const val SIGNUP = "https://azure.microsoft.com/free/"
private const val CREATE = "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"
private const val PORTAL = "https://portal.azure.com/"
private const val SAMPLE = "안녕하세요. 이 목소리로 책을 읽어 드립니다. 2026년 11월 19일, 오늘은 3시간 읽었습니다."

private fun open(c: Context, url: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shown until the voice list is read from Azure (or when it cannot be). */
private val FALLBACK = listOf(
    AzureVoice.VoiceInfo("ko-KR-SunHiNeural", "선히", "Female", "ko-KR", "Neural"),
    AzureVoice.VoiceInfo("ko-KR-InJoonNeural", "인준", "Male", "ko-KR", "Neural"),
)

/** Azure voices, women and men apart, as chips: a tap picks the voice and plays a sample. */
@Composable
fun AzureVoicePanel(ra: ReadAloud, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var voice by remember { mutableStateOf(AzureVoice.voice(ctx)) }
    var voices by remember { mutableStateOf(FALLBACK) }
    LaunchedEffect(Unit) {
        val key = AzureVoice.key(ctx) ?: return@LaunchedEffect
        runCatching { AzureVoice.voices(key, AzureVoice.region(ctx)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { voices = it }
    }
    Text("목소리 · 누르면 골라서 들려줍니다", style = MaterialTheme.typography.labelLarge)
    listOf("Female" to "여성", "Male" to "남성").forEach { (g, label) ->
        val group = voices.filter { it.Gender.equals(g, true) }
        if (group.isEmpty()) return@forEach
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp, end = 8.dp))
            ChipFlow {
                group.forEach { v ->
                    FilterChip(
                        selected = voice == v.ShortName,
                        onClick = {
                            voice = v.ShortName; AzureVoice.setVoice(ctx, v.ShortName); ra.azureChanged()
                            if (!ra.playing) ra.previewAzure(v.ShortName, SAMPLE)
                        },
                        label = { Text(voiceName(v), maxLines = 1) },
                    )
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "이번 달 이 폰에서 약 ${"%,d".format(AzureVoice.usedThisMonth(ctx))}자 / 무료 ${"%,d".format(AzureVoice.FREE_CHARS)}자",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSetup) { Text("설정·사용법") }
    }
}

private fun voiceName(v: AzureVoice.VoiceInfo): String {
    val multi = v.ShortName.contains("Multilingual")
    val base = v.LocalName.replace("Multilingual", "").trim()
        .ifBlank { v.ShortName.substringAfterLast('-').removeSuffix("Neural").removeSuffix("Multilingual") }
    return if (multi) "$base 다국어" else base
}

/**
 * How to get a key (step by step, with links), then the key and the region, a connection test
 * that costs nothing (the voice list), and what is sent where.
 */
@Composable
fun AzureSetupDialog(ra: ReadAloud, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(AzureVoice.key(ctx).orEmpty()) }
    var region by remember { mutableStateOf(AzureVoice.region(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Azure 음성 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Microsoft의 공식 음성 서비스를 내 키로 씁니다. 무료(F0) 등급은 한 달에 50만 자(책 1~2권쯤)까지 무료이고, " +
                        "넘으면 그달은 더 읽지 않고 멈춥니다(요금이 나가지 않음). 처음 한 번 10분쯤 걸립니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Step("1", "Azure 무료 계정 만들기", "Microsoft 계정으로 가입합니다. 본인 확인용으로 카드와 전화번호를 묻습니다(무료 등급은 결제되지 않음).") {
                    TextButton(onClick = { open(ctx, SIGNUP) }) { Text("가입 페이지 열기") }
                }
                Step("2", "Speech 리소스 만들기", "가격 책정 계층은 꼭 \"Free F0\", 지역은 \"Korea Central\"을 고릅니다. 이름은 아무거나 괜찮습니다.") {
                    TextButton(onClick = { open(ctx, CREATE) }) { Text("만들기 화면 열기") }
                }
                Step("3", "키 복사하기", "만든 리소스 › \"키 및 엔드포인트\"에서 \"키 1\"을 복사합니다. 같은 화면의 \"위치/지역\"도 확인합니다.") {
                    TextButton(onClick = { open(ctx, PORTAL) }) { Text("Azure 포털 열기") }
                }
                Text("4  아래에 붙여 넣고 연결 확인", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim(); status = null },
                    label = { Text("키") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("지역", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AzureVoice.REGIONS.forEach { (id, label) ->
                        FilterChip(selected = region == id, onClick = { region = id; status = null }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = key.isNotBlank() && !busy, onClick = {
                        busy = true; status = "확인하는 중…"
                        scope.launch {
                            val r = runCatching { AzureVoice.voices(key, region) }
                            busy = false
                            r.onSuccess { list ->
                                AzureVoice.save(ctx, key, region)
                                AzureVoice.setOn(ctx, true)
                                ra.azureChanged()
                                status = "✅ 연결됐습니다. 한국어 목소리 ${list.size}개. 이제 Azure 목소리로 읽습니다."
                            }.onFailure { status = "⚠️ " + AzureVoice.reason(it) + ". 키와 지역을 확인해 주세요." }
                        }
                    }) { Text("연결 확인") }
                    if (AzureVoice.hasKey(ctx)) TextButton(onClick = {
                        AzureVoice.forget(ctx); key = ""; ra.azureChanged(); status = "키를 지웠습니다."
                    }) { Text("키 지우기") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "알아 두실 것\n" +
                        "· 읽는 문장이 Microsoft 서버로 보내집니다(책 파일은 보내지 않음).\n" +
                        "· 데이터를 씁니다: 1시간 듣기에 약 20MB. 한 번 들은 문장은 폰에 남아 다시 받지 않습니다.\n" +
                        "· 남은 무료 한도는 이 폰에서 보낸 글자 수로 어림합니다. 정확한 사용량은 Azure 포털에서 볼 수 있습니다.\n" +
                        "· 무료 계정은 30일 뒤 \"종량제로 전환\"을 묻습니다. 전환해도 Free F0 리소스는 한도를 넘으면 멈출 뿐 요금이 나가지 않습니다(Microsoft 안내 기준).\n" +
                        "· 키는 이 폰에만 암호화해 두고 백업에 넣지 않습니다.\n" +
                        "· 인터넷이 끊기거나 한도를 다 쓰면 휴대폰 목소리로 이어 읽습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun Step(n: String, title: String, body: String, action: @Composable () -> Unit) {
    Column {
        Text("$n  $title", style = MaterialTheme.typography.labelLarge)
        Text(body, style = MaterialTheme.typography.bodySmall)
        action()
    }
}
