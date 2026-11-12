package com.oasisreader.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.oasisreader.app.data.Backup
import com.oasisreader.app.data.ServerSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onServer: () -> Unit, onDoc: (String) -> Unit = {}, onReadingDefaults: () -> Unit = {}, onDownloads: () -> Unit = {}, onStorage: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val servers = remember { ServerSettings(app).all() }
    var busy by remember { mutableStateOf<String?>(null) }
    var withBooks by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "백업을 만드는 중…"
        scope.launch {
            try {
                val m = Backup.export(app, uri, withBooks)
                Toast.makeText(context, "백업했습니다: 책 ${m.books}권" + if (m.includesBookFiles) " (파일 포함)" else "", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "백업 실패: ${e.message}")
            } finally {
                busy = null
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    fun startExport(books: Boolean) {
        withBooks = books
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        exporter.launch("ogreader-backup-$day${if (books) "" else "-nobooks"}.zip")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("설정", onBack = onBack)
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section("도움말")
            Item("📘", "사용 안내", "처음 쓰신다면: 서버 연결, 책 받기, 화면 조작", enabled = true) { onDoc(DOC_GUIDE) }

            Section("서버 (OPDS · 파일 서버)")
            Item(
                "☁️", "서버 목록 · 추가",
                if (servers.isEmpty()) "아직 등록하지 않았습니다" else servers.joinToString(" · ") { it.name },
                enabled = busy == null, onClick = onServer,
            )

            Section("화면")
            val tm = com.oasisreader.app.ui.theme.ThemeMode
            var themeOpen by remember { mutableStateOf(false) }
            Item("🌓", "테마: ${tm.label(tm.mode)}", "앱 전체 색. 종이·세피아·분홍·밤 같은 읽기 테마를 서재와 메뉴에도 입힐 수 있습니다", enabled = true) { themeOpen = true }
            if (themeOpen) com.oasisreader.app.ui.theme.ThemePickDialog { themeOpen = false }
            Item("🎨", "아이콘: ${OgIcons.label(OgIcons.style)}", "단색(앱이 직접 그린 선 아이콘) ↔ 이모지 (누를 때마다 바뀝니다). 지금은 화면 위쪽 버튼부터 바뀝니다", enabled = true) {
                OgIcons.set(app, if (OgIcons.style == OgIcons.MONO) OgIcons.EMOJI else OgIcons.MONO)
            }

            Section("읽기")
            Item("📖", "읽기 기본값", "글꼴·크기·여백·배경·넘김 등 새 책이 시작할 설정. 책 안에서 바꾸면 그 책에만 남습니다", enabled = true) { onReadingDefaults() }
            var lookupEdit by remember { mutableStateOf(false) }
            Item("🔍", "찾아보기 앱", "글자를 골라 찾아볼 때 나오는 번역·사전 앱의 표시와 순서", enabled = true) { lookupEdit = true }
            if (lookupEdit) LookupSettingsDialog { lookupEdit = false }
            var voiceEdit by remember { mutableStateOf(false) }
            Item("🎙", "소리 내 읽기 목소리", "음성 엔진(삼성·구글 등)과 목소리, 음높이. 책을 읽는 중에는 재생 막대의 🎙", enabled = true) { voiceEdit = true }
            if (voiceEdit) {
                val ra = remember { com.oasisreader.app.reader.ReadAloud(app) }
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ra.shutdown() } }
                com.oasisreader.app.reader.VoiceDialog(ra) { voiceEdit = false }
            }

            Section("독서 습관")
            var habitOpen by remember { mutableStateOf(false) }
            var habitRev by remember { mutableStateOf(0) }
            val habitSub = remember(habitRev) { com.oasisreader.app.stats.ReadingHabit.summary(app) }
            Item("⏰", "독서 습관 알림: $habitSub", "정한 시각(요일)에 알림을 받고, 누르면 마지막에 읽던 책이 바로 열립니다", enabled = true) { habitOpen = true }
            if (habitOpen) com.oasisreader.app.stats.ReadingHabitDialog { habitOpen = false; habitRev++ }

            Section("동기화")
            var kosyncOpen by remember { mutableStateOf(false) }
            var kosyncRev by remember { mutableStateOf(0) }
            val kosyncSub = remember(kosyncRev) { kosyncSummary(app) }
            Item("☁️", "읽은 위치 동기화", kosyncSub, enabled = true) { kosyncOpen = true }
            if (kosyncOpen) KosyncDialog { kosyncOpen = false; kosyncRev++ }

            Section("리모컨 · 키보드")
            var volumeKeys by remember { mutableStateOf(PageKeys.volumeKeys(app)) }
            Item("🔊", "볼륨 버튼으로 쪽 넘기기: ${if (volumeKeys) "켜짐" else "꺼짐"}",
                "켜면 책을 보는 동안 볼륨 − 는 다음 쪽, + 는 이전 쪽. 블루투스 페이지 리모컨의 ←/→·Page Up/Down·Space 는 늘 쪽을 넘깁니다", enabled = true) {
                volumeKeys = !volumeKeys
                PageKeys.setVolumeKeys(app, volumeKeys)
            }

            var usage by remember { mutableStateOf<Pair<Int, Long>?>(null) }
            var usageKey by remember { mutableStateOf(0) }
            var storage by remember { mutableStateOf<com.oasisreader.app.data.StorageUsage?>(null) }
            LaunchedEffect(usageKey) {
                usage = com.oasisreader.app.data.BookStore.get(app).cacheUsage()
                storage = com.oasisreader.app.data.Storage.usage(app)
            }

            Section("저장 공간")
            val L = com.oasisreader.app.data.Storage::label
            Item(
                "💾",
                storage?.let { "앱 전체 ${L(it.totalBytes)} · 폰 남은 공간 ${L(it.freeBytes)}" } ?: "저장 공간을 세는 중…",
                storage?.let {
                    listOf(
                        "📌 서재에 보관한 책  ${L(it.keptBytes)} (${it.keptBooks}권)",
                        "🎧 받은 오디오북  ${L(it.audioBytes)} (${it.audioBooks}권)",
                        "☁️ 임시 보관  ${L(it.cacheBytes)} (${it.cacheBooks}권)",
                        "🗃️ 표지·글꼴·기타  ${L(it.otherBytes)}",
                        "👉 누르면 📦 보관함 관리 (책별로 보고 한꺼번에 지우기·받기)",
                    ).joinToString("\n")
                } ?: "",
                enabled = busy == null,
            ) { onStorage() }

            Section("바로 읽기 (임시 보관)")
            val cachePrefs = remember { com.oasisreader.app.data.CachePrefs(app) }
            var limit by remember { mutableStateOf(cachePrefs.limitMb) }
            Item(
                "📦", "임시 보관 한도: ${com.oasisreader.app.data.CachePrefs.label(limit)}",
                (usage?.let { (n, bytes) -> "지금 ${n}권 · ${"%.0f".format(bytes / 1048576.0)} MB. " } ?: "") +
                    "넘으면 오래 안 본 책 파일부터 지웁니다 (읽은 위치·하이라이트는 남고, 다시 열면 다시 받습니다). 눌러서 바꾸기",
                enabled = busy == null,
            ) {
                val all = com.oasisreader.app.data.CachePrefs.CHOICES
                limit = all[(all.indexOf(limit) + 1) % all.size]
                cachePrefs.limitMb = limit
                scope.launch { com.oasisreader.app.data.BookStore.get(app).trimCache(cachePrefs.limitBytes); usageKey++ }
            }
            Item("🧹", "임시 보관 파일 지금 비우기", "서재에 보관(📌)한 책은 그대로입니다", enabled = busy == null && (usage?.first ?: 0) > 0) {
                scope.launch {
                    val n = com.oasisreader.app.data.BookStore.get(app).trimCache(0)
                    Toast.makeText(context, "${n}권의 파일을 지웠습니다", Toast.LENGTH_SHORT).show()
                    usageKey++
                }
            }

            Section("백업 · 기기 변경 (실험적 기능)")
            Item("💾", "백업 내보내기 (직접 넣은 책 파일 포함)", "책·위치·하이라이트·통계·설정을 zip 하나로 저장합니다. 서버에서 받은 책과 오디오북은 파일이 빠지고 새 폰에서 서버에서 다시 받습니다", enabled = busy == null) { startExport(true) }
            Item("📄", "백업 내보내기 (책 파일 빼고)", "가볍게. 책 파일은 하나도 넣지 않습니다", enabled = busy == null) { startExport(false) }
            Item("♻️", "백업에서 복원", "지금 서재를 백업 내용으로 바꿉니다", enabled = busy == null) {
                importer.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            Text(
                "서버 비밀번호는 백업에 들어가지 않습니다. 복원한 뒤 서버마다 한 번 다시 입력하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Section("서버 부가 기능")
            Item("📄", "메타 API(oasis-meta) 안내", "설치하면 되는 것(시리즈별 보기·줄거리·필터)과 설치 안내 링크", enabled = true) { onDoc(DOC_META) }

            Section("정보")
            Item("ℹ️", "OG Reader ${BuildConfig.VERSION_NAME}", "EPUB · 만화 · PDF 리더 · 개발 JSLee", enabled = true) { onDoc(DOC_ABOUT) }
            val (checking, checkUpdate) = rememberUpdateChecker()
            Item("🔄", if (checking) "업데이트 확인 중…" else "업데이트 확인",
                "GitHub에서 새 버전을 찾습니다. 서재를 열 때도 자동으로 확인합니다", enabled = !checking) { checkUpdate() }
            Item("📝", "릴리스 노트", null, enabled = true) { onDoc(DOC_RELEASE) }
            Item("⚖️", "오픈소스 라이선스", null, enabled = true) { onDoc(DOC_LICENSES) }
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("백업에서 복원하시겠습니까?") },
            text = { Text("지금 서재의 책, 읽은 위치, 하이라이트, 통계가 백업 내용으로 바뀝니다. 되돌릴 수 없습니다. 복원이 끝나면 앱이 다시 시작됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    busy = "복원하는 중…"
                    scope.launch {
                        try {
                            Backup.restore(app, uri)
                            Backup.restartApp(app)
                        } catch (e: Exception) {
                            busy = null
                            com.oasisreader.app.ui.Notice.show(context, "복원 실패: ${e.message}")
                        }
                    }
                }) { Text("복원") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Item(emoji: String, title: String, sub: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

const val DOC_GUIDE = "guide"
const val DOC_META = "oasis-meta"
const val DOC_ABOUT = "about"
const val DOC_RELEASE = "release-notes"
const val DOC_LICENSES = "licenses"

fun docTitle(id: String) = when (id) {
    DOC_GUIDE -> "사용 안내"
    DOC_META -> "메타 API 안내"
    DOC_ABOUT -> "OG Reader 정보"
    DOC_RELEASE -> "릴리스 노트"
    else -> "오픈소스 라이선스"
}
