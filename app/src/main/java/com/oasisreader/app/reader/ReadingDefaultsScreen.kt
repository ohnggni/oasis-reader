package com.oasisreader.app.reader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.ui.PanelChoice
import com.oasisreader.app.ui.PanelSwitch
import com.oasisreader.app.ui.PanelTitle
import com.oasisreader.app.ui.TopBar
import com.oasisreader.app.ui.TurnStylePicker
import com.oasisreader.app.ui.theme.isAppDark
import kotlinx.coroutines.launch

/**
 * 설정 › 읽기 기본값: the settings every book starts with (a book changed in the reader keeps its
 * own), plus the ones that are the same everywhere. Same compact controls as the reader's panels.
 */
@Composable
fun ReadingDefaultsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember { ReaderPrefs(app) }          // no book: the defaults
    val fonts = remember { FontManager(app) }

    var fontId by remember { mutableStateOf(prefs.fontId) }
    var fontPx by remember { mutableIntStateOf(prefs.fontPx) }
    var lineHeight by remember { mutableIntStateOf(prefs.lineHeight) }
    var weight by remember { mutableIntStateOf(prefs.weight) }
    var publisher by remember { mutableStateOf(prefs.publisherStyle) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var turn by remember { mutableStateOf(prefs.turnStyle) }
    var speed by remember { mutableStateOf(prefs.turnSpeed) }
    var mTop by remember { mutableIntStateOf(prefs.marginTop) }
    var mBottom by remember { mutableIntStateOf(prefs.marginBottom) }
    var mLeft by remember { mutableIntStateOf(prefs.marginLeft) }
    var mRight by remember { mutableIntStateOf(prefs.marginRight) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var chromeSet by remember { mutableStateOf(ReadingChrome.of(prefs)) }
    var autoHide by remember { mutableStateOf(prefs.autoHideBars) }
    var fontBusy by remember { mutableStateOf<String?>(null) }
    var fontProgress by remember { mutableFloatStateOf(0f) }
    var own by remember { mutableIntStateOf(prefs.booksWithOwn) }
    var confirmReset by remember { mutableStateOf(false) }

    fun pickFont(f: FontOption) {
        if (fonts.isReady(f)) { fontId = f.id; prefs.fontId = f.id; return }
        fontBusy = f.id; fontProgress = 0f
        scope.launch {
            try {
                fonts.download(f) { d, t -> fontProgress = d.toFloat() / t }
                fontId = f.id; prefs.fontId = f.id
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "글꼴 받기 실패: ${e.message}")
            } finally { fontBusy = null }
        }
    }
    val fontFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fonts.importFont(app, uri) }
                .onSuccess { fontId = it.id; prefs.fontId = it.id }
                .onFailure { com.oasisreader.app.ui.Notice.show(context, "글꼴 추가 실패: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("읽기 기본값", onBack = onBack)
        Preview(theme, FontManager.byId(fontId), fontPx, lineHeight, weight)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "새로 여는 책은 이 설정으로 시작합니다. 책 안에서 바꾼 설정은 그 책에만 남습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            PanelTitle("글자 · 책마다 따로")
            FontPanel(
                fonts = fonts, selectedId = fontId, busyId = fontBusy, progress = fontProgress,
                onPick = ::pickFont,
                onAdd = { fontFile.launch(arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")) },
                onRemove = { f -> fonts.removeCustom(f); fontId = FontManager.byId(null).id; prefs.fontId = fontId },
            )
            SizePanel(
                weight = weight, onWeight = { weight = it; prefs.weight = it },
                value = fontPx, onChange = { fontPx = it; prefs.fontPx = it },
                lineHeight = lineHeight, onLineHeight = { lineHeight = it; prefs.lineHeight = it },
            )
            PanelSwitch("책 디자인 사용 (제목·문단 모양)", null, publisher, { publisher = it; prefs.publisherStyle = it })

            Spacer(Modifier.height(8.dp))
            PanelTitle("쪽 · 책(EPUB·TXT), 책마다 따로")
            PanelChoice("보기", listOf("책처럼 넘기기", "스크롤"), if (mode == ReaderMode.PAGE) 0 else 1) {
                mode = if (it == 0) ReaderMode.PAGE else ReaderMode.SCROLL; prefs.mode = mode
            }
            TurnStylePicker(turn, speed, { turn = it; prefs.turnStyle = it }, { speed = it; prefs.turnSpeed = it })
            MarginPanel(mTop, mBottom, mLeft, mRight) { t, b, l, r ->
                mTop = t; mBottom = b; mLeft = l; mRight = r
                prefs.marginTop = t; prefs.marginBottom = b; prefs.marginLeft = l; prefs.marginRight = r
            }
            ThemeRow(theme) { theme = it; prefs.theme = it }

            Spacer(Modifier.height(8.dp))
            PanelTitle("만화 · PDF (보기는 만화마다 따로)")
            ComicDefaultsPanel()

            Spacer(Modifier.height(8.dp))
            PanelTitle("화면 · 모든 책 공통")
            BrightnessPanel(brightness) { brightness = it; prefs.brightness = it }
            PanelSwitch("화면 켜짐 유지", null, keepOn, { keepOn = it; prefs.keepScreenOn = it })
            PanelSwitch("상단바 자동 숨김", null, autoHide, { autoHide = it; prefs.autoHideBars = it })

            Spacer(Modifier.height(8.dp))
            PanelTitle("화면 표시 · 책·만화·PDF (책마다 ▤ / ☀️ 패널에서 따로 바꿀 수 있음)")
            ChromeSwitches(chromeSet, withNav = true) { c -> chromeSet = c; prefs.saveChrome(c) }
            Text(
                "서재에서는 그대로 보입니다. 숨긴 바는 화면 가장자리에서 쓸어 넘기면 잠깐 나타납니다." +
                    if (chromeSet.hideNav) " 제스처 내비게이션에서는 뒤로·홈이 \"한 번 쓸어 바를 꺼내고, 한 번 더\" 두 번이 될 수 있습니다." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (own > 0) "따로 설정한 책 ${own}권" else "따로 설정한 책이 없습니다. 모든 책이 이 기본값을 따릅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (own > 0) OutlinedButton(onClick = { confirmReset = true }) { Text("모든 책을 기본값으로 되돌리기") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("모든 책을 기본값으로") },
        text = { Text("책마다 따로 바꾼 글꼴·크기·여백·배경·넘김 설정을 지우고 모두 위의 기본값을 따르게 합니다. 읽은 위치와 하이라이트는 그대로입니다.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                prefs.resetAllBooks(); own = 0
                Toast.makeText(context, "모든 책이 기본값을 따릅니다.", Toast.LENGTH_SHORT).show()
            }) { Text("되돌리기") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } },
    )
}

/** A few lines in the chosen background, size, spacing and weight (font: its 명조/고딕 kind). */
@Composable
private fun Preview(theme: ReaderTheme, font: FontOption, px: Int, lineHeight: Int, weight: Int) {
    val t = theme.resolve(isAppDark())
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .background(Color(t.bg), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val size = (px * 0.85f).sp
        Text(
            "우리는 무한의 검은 바다 한가운데 떠 있는 평온한 무지의 섬에 살고 있으며, 멀리까지 항해하도록 되어 있지 않다.",
            color = Color(t.fg),
            fontSize = size,
            lineHeight = size * (lineHeight / 10f),
            fontFamily = if (font.kind == FontOption.Kind.SERIF) FontFamily.Serif else FontFamily.SansSerif,
            fontWeight = when (weight) { 1 -> FontWeight.Medium; 2 -> FontWeight.Bold; else -> FontWeight.Normal },
            maxLines = 3,
        )
    }
}

/** 만화·PDF 기본값: these live in the "comic" prefs the viewer reads (보기 is the start for books not yet set). */
@Composable
private fun ComicDefaultsPanel() {
    val app = LocalContext.current.applicationContext
    val cp = remember { app.getSharedPreferences("comic", android.content.Context.MODE_PRIVATE) }
    // "AUTO" (1.4, by page shape) is gone: it shows and reads as 책처럼 넘기기
    var dMode by remember { mutableStateOf(cp.getString("default_mode", "PAGE") ?: "PAGE") }
    var rtl by remember { mutableStateOf(cp.getBoolean("rtl", false)) }
    var coverAlone by remember { mutableStateOf(cp.getBoolean("cover_alone", true)) }
    var narrow by remember { mutableStateOf(cp.getBoolean("narrow", false)) }
    val modes = listOf("PAGE", "SCROLL")
    PanelChoice("보기", listOf("책처럼 넘기기", "세로 스크롤 (웹툰)"), modes.indexOf(dMode).coerceAtLeast(0)) {
        dMode = modes[it]; cp.edit().putString("default_mode", dMode).apply()
    }
    PanelChoice("방향", listOf("왼쪽 → 오른쪽", "오른쪽 → 왼쪽 (일본 만화)"), if (rtl) 1 else 0) {
        rtl = it == 1; cp.edit().putBoolean("rtl", rtl).apply()
    }
    PanelChoice("맞쪽", listOf("첫 장은 혼자", "첫 장도 짝 지어"), if (coverAlone) 0 else 1) {
        coverAlone = it == 0; cp.edit().putBoolean("cover_alone", coverAlone).apply()
    }
    PanelChoice("폭", listOf("화면 너비에 맞추기", "가운데 좁게"), if (narrow) 1 else 0) {
        narrow = it == 1; cp.edit().putBoolean("narrow", narrow).apply()
    }
}
