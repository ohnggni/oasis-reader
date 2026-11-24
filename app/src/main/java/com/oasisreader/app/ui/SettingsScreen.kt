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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
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
            // 80 (2.7): one short line per setting — its value at the end; what it means is told
            // where it is chosen (the choice window), not under every row
            Section("도움말")
            Item("📘", "사용 안내", enabled = true) { onDoc(DOC_GUIDE) }

            Section("서버 (OPDS · 파일 서버)")
            Item(
                "☁️", "서버 목록 · 추가",
                sub = if (servers.isEmpty()) "아직 없음" else servers.joinToString(" · ") { it.name },
                enabled = busy == null, onClick = onServer,
            )

            Section("화면")
            val tm = com.oasisreader.app.ui.theme.ThemeMode
            var themeOpen by remember { mutableStateOf(false) }
            Item("🌓", "테마", value = if (tm.mode == tm.SYSTEM) "시스템 · ${tm.label(tm.lightPick)} / ${tm.label(tm.darkPick)}" else tm.label(tm.mode), enabled = true) { themeOpen = true }
            if (themeOpen) com.oasisreader.app.ui.theme.ThemePickDialog { themeOpen = false }
            val us = com.oasisreader.app.ui.theme.UiScale
            var scaleOpen by remember { mutableStateOf(false) }
            Item("🔤", "글자 크기", value = us.label, enabled = true) { scaleOpen = true }
            if (scaleOpen) ChoiceDialog(
                "글자 크기", note = "서재 · 메뉴 · 설정의 글자. 책 본문은 읽는 화면의 가에서 정합니다.",
                options = us.FACTORS.indices.map { i -> Choice(i, us.labelOf(i)) }, selected = us.step,
                onPick = { us.set(app, it) }, onDismiss = { scaleOpen = false },
            )
            var iconOpen by remember { mutableStateOf(false) }
            Item("🎨", "아이콘", value = OgIcons.label(OgIcons.style), enabled = true) { iconOpen = true }
            if (iconOpen) ChoiceDialog(
                "아이콘",
                options = listOf(Choice(OgIcons.MONO, OgIcons.label(OgIcons.MONO), "앱이 그린 선 아이콘"), Choice(OgIcons.EMOJI, OgIcons.label(OgIcons.EMOJI))),
                selected = OgIcons.style, onPick = { OgIcons.set(app, it) }, onDismiss = { iconOpen = false },
            )

            Section("읽기")
            Item("📖", "읽기 기본값", enabled = true) { onReadingDefaults() }
            var lookupEdit by remember { mutableStateOf(false) }
            Item("🔍", "찾아보기 앱", enabled = true) { lookupEdit = true }
            if (lookupEdit) LookupSettingsDialog { lookupEdit = false }
            var voiceEdit by remember { mutableStateOf(false) }
            Item("🎙", "소리 내 읽기 목소리", enabled = true) { voiceEdit = true }
            if (voiceEdit) {
                val ra = remember { com.oasisreader.app.reader.ReadAloud(app) }
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ra.shutdown() } }
                com.oasisreader.app.reader.VoiceDialog(ra) { voiceEdit = false }
            }
            var volumeKeys by remember { mutableStateOf(PageKeys.volumeKeys(app)) }
            SwitchItem("🔊", "볼륨 버튼으로 쪽 넘기기", volumeKeys) {
                volumeKeys = it
                PageKeys.setVolumeKeys(app, it)
            }
            var resumeAloud by remember { mutableStateOf(com.oasisreader.app.reader.ReadAloud.resumeOn(app)) }
            SwitchItem("🔊", "소리로 읽던 책은 이어 듣기", resumeAloud) {
                resumeAloud = it
                com.oasisreader.app.reader.ReadAloud.setResumeOn(app, it)
            }

            Section("독서 습관 · 동기화")
            var habitOpen by remember { mutableStateOf(false) }
            var habitRev by remember { mutableStateOf(0) }
            val habitSub = remember(habitRev) { com.oasisreader.app.stats.ReadingHabit.summary(app) }
            Item("⏰", "독서 습관 알림", value = habitSub, enabled = true) { habitOpen = true }
            if (habitOpen) com.oasisreader.app.stats.ReadingHabitDialog { habitOpen = false; habitRev++ }
            var kosyncOpen by remember { mutableStateOf(false) }
            var kosyncRev by remember { mutableStateOf(0) }
            val kosyncSub = remember(kosyncRev) { kosyncSummary(app) }
            Item("🔄", "읽은 위치 동기화", value = kosyncSub.substringBefore(" · "), enabled = true) { kosyncOpen = true }
            if (kosyncOpen) KosyncDialog { kosyncOpen = false; kosyncRev++ }

            var usage by remember { mutableStateOf<Pair<Int, Long>?>(null) }
            var usageKey by remember { mutableStateOf(0) }
            var storage by remember { mutableStateOf<com.oasisreader.app.data.StorageUsage?>(null) }
            LaunchedEffect(usageKey) {
                usage = com.oasisreader.app.data.BookStore.get(app).cacheUsage()
                storage = com.oasisreader.app.data.Storage.usage(app)
            }

            Section("저장 공간")
            val L = com.oasisreader.app.data.Storage::label
            var storageOpen by remember { mutableStateOf(false) }
            Item(
                "💾", "저장 공간",
                value = storage?.let { "${L(it.totalBytes)} · 남은 ${L(it.freeBytes)}" } ?: "세는 중…",
                enabled = busy == null,
            ) { storageOpen = true }
            if (storageOpen) storage?.let { s ->
                AlertDialog(
                    onDismissRequest = { storageOpen = false },
                    title = { Text("저장 공간") },
                    text = {
                        Column {
                            listOf(
                                "📌 서재에 보관한 책" to "${L(s.keptBytes)} · ${s.keptBooks}권",
                                "🎧 받은 오디오북" to "${L(s.audioBytes)} · ${s.audioBooks}권",
                                "☁️ 임시 보관" to "${L(s.cacheBytes)} · ${s.cacheBooks}권",
                                "🗃️ 표지 · 글꼴 · 기타" to L(s.otherBytes),
                                "앱 전체" to L(s.totalBytes),
                                "폰 남은 공간" to L(s.freeBytes),
                            ).forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    EmojiText(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { storageOpen = false; onStorage() }) { EmojiText("📦 보관함 관리") } },
                    dismissButton = { TextButton(onClick = { storageOpen = false }) { Text("닫기") } },
                )
            }

            Section("바로 읽기 (임시 보관)")
            val cachePrefs = remember { com.oasisreader.app.data.CachePrefs(app) }
            val C = com.oasisreader.app.data.CachePrefs
            var limit by remember { mutableStateOf(cachePrefs.limitMb) }
            var limitOpen by remember { mutableStateOf(false) }
            Item("📦", "임시 보관 한도", value = C.label(limit), enabled = busy == null) { limitOpen = true }
            if (limitOpen) ChoiceDialog(
                "임시 보관 한도",
                note = (usage?.let { (n, bytes) -> "지금 ${n}권 · ${"%.0f".format(bytes / 1048576.0)} MB. " } ?: "") +
                    "넘으면 오래 안 본 책 파일부터 지웁니다. 다시 열면 다시 받습니다.",
                options = C.CHOICES.map { Choice(it, C.label(it)) }, selected = limit,
                onPick = {
                    limit = it
                    cachePrefs.limitMb = it
                    scope.launch { com.oasisreader.app.data.BookStore.get(app).trimCache(cachePrefs.limitBytes); usageKey++ }
                },
                onDismiss = { limitOpen = false },
            )
            var dropMode by remember { mutableStateOf(cachePrefs.dropMode) }
            var dropOpen by remember { mutableStateOf(false) }
            val dropLabel = { m: Int -> when (m) { C.DROP_CACHE -> "임시 책만"; C.DROP_SHELF -> "서재 책까지"; else -> "끔" } }
            Item("🧽", "다 읽은 책 파일 지우기", value = dropLabel(dropMode), enabled = busy == null) { dropOpen = true }
            if (dropOpen) ChoiceDialog(
                "다 읽은 책 파일 지우기",
                note = "다 읽고 서재로 나오면 파일만 지웁니다. 목록 · 위치 · 하이라이트는 남고, 다시 열면 받습니다.",
                options = listOf(
                    Choice(C.DROP_OFF, dropLabel(C.DROP_OFF)),
                    Choice(C.DROP_CACHE, dropLabel(C.DROP_CACHE), "바로 읽기로 연 책"),
                    Choice(C.DROP_SHELF, dropLabel(C.DROP_SHELF), "서재에 담은 책도 (폰에서 가져온 책 · 오디오북 제외)"),
                ),
                selected = dropMode, onPick = { dropMode = it; cachePrefs.dropMode = it }, onDismiss = { dropOpen = false },
            )
            var prefetch by remember { mutableStateOf(cachePrefs.prefetchNext) }
            SwitchItem("⬇️", "다음 회차 미리 받기", prefetch) {
                prefetch = it
                cachePrefs.prefetchNext = it
            }
            Item(
                "🧹", "임시 보관 지금 비우기", value = usage?.first?.takeIf { it > 0 }?.let { "${it}권" },
                enabled = busy == null && (usage?.first ?: 0) > 0, chevron = false,
            ) {
                scope.launch {
                    val n = com.oasisreader.app.data.BookStore.get(app).trimCache(0)
                    Toast.makeText(context, "${n}권의 파일을 지웠습니다 (📌 보관한 책은 그대로)", Toast.LENGTH_SHORT).show()
                    usageKey++
                }
            }

            Section("백업 · 기기 변경 (실험적)")
            var exportOpen by remember { mutableStateOf(false) }
            Item("💾", "백업 내보내기", enabled = busy == null) { exportOpen = true }
            if (exportOpen) ChoiceDialog(
                "백업 내보내기",
                note = "책 · 위치 · 하이라이트 · 통계 · 설정을 zip 하나로. 서버 비밀번호는 들어가지 않아서 복원 뒤 다시 입력합니다.",
                options = listOf(
                    Choice(true, "책 파일 포함", "폰에서 직접 넣은 책까지 (서버 책은 새 폰에서 다시 받음)"),
                    Choice(false, "책 파일 빼고", "가볍게"),
                ),
                selected = null, onPick = { startExport(it) }, onDismiss = { exportOpen = false },
            )
            Item("♻️", "백업에서 복원", enabled = busy == null) {
                importer.launch(arrayOf("application/zip", "application/octet-stream"))
            }

            Section("서버 부가 기능")
            Item("📄", "메타 API(oasis-meta) 안내", enabled = true) { onDoc(DOC_META) }

            Section("정보")
            Item("ℹ️", "OG Reader", value = BuildConfig.VERSION_NAME, enabled = true) { onDoc(DOC_ABOUT) }
            val (checking, checkUpdate) = rememberUpdateChecker()
            Item("🔄", if (checking) "업데이트 확인 중…" else "업데이트 확인", enabled = !checking, chevron = false) { checkUpdate() }
            Item("📝", "릴리스 노트", enabled = true) { onDoc(DOC_RELEASE) }
            Item("⚖️", "오픈소스 라이선스", enabled = true) { onDoc(DOC_LICENSES) }
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

/**
 * One setting on one line: icon · title · its value at the end (› when it opens something).
 * [sub] only for a short state worth seeing without opening (서버 이름 등) — never how-to text.
 */
@Composable
private fun Item(
    emoji: String, title: String, sub: String? = null, value: String? = null,
    enabled: Boolean, chevron: Boolean = true, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiText(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        value?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        if (chevron && enabled) Text(" ›", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** An on/off setting: the switch is the value. */
@Composable
private fun SwitchItem(emoji: String, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiText(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

private class Choice<T>(val key: T, val label: String, val note: String? = null)

/** Pick one: what the setting means ([note]) and a short line per choice live here, not on the row. */
@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Choice<T>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit, note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(o.key); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o.key == selected, onClick = { onPick(o.key); onDismiss() })
                        Column {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge)
                            o.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
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
