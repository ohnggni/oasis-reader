package com.oasisreader.app.reader

import android.webkit.WebView
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption

/** The ☀ 화면 panel: brightness, page colours, screen-on and bar auto-hide, volume keys. */
@Composable
internal fun ColumnScope.ScreenPanelBody(
    brightness: Float, onBrightness: (Float) -> Unit,
    theme: ReaderTheme, onTheme: (ReaderTheme) -> Unit,
    keepOn: Boolean, onKeepOn: (Boolean) -> Unit,
    autoHide: Boolean, onAutoHide: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    BrightnessPanel(value = brightness, onChange = onBrightness)
    ThemeRow(theme, onTheme)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        com.oasisreader.app.ui.PanelSwitch("화면 켜짐 유지", null, keepOn, onKeepOn, Modifier.weight(1f))
        com.oasisreader.app.ui.PanelSwitch("바 자동 숨김", null, autoHide, onAutoHide, Modifier.weight(1f))
    }
    // 🔊 볼륨 버튼으로 쪽 넘기기 (the same setting as in 설정, easier to find here)
    var volKeys by remember { mutableStateOf(com.oasisreader.app.ui.PageKeys.volumeKeys(context)) }
    com.oasisreader.app.ui.PanelSwitch("🔊 볼륨 버튼으로 쪽 넘기기", null, volKeys, { volKeys = it; com.oasisreader.app.ui.PageKeys.setVolumeKeys(context, it) })
}

/** The 가 글자 panel: font, weight, size, line height, the book's own design. */
@Composable
internal fun ColumnScope.TextPanelBody(
    prefs: ReaderPrefs, fonts: FontManager, webView: () -> WebView?,
    fontId: String, fontBusy: String?, fontProgress: Float,
    onPickFont: (FontOption) -> Unit, onAddFont: () -> Unit, onRemoveFont: (FontOption) -> Unit,
    weight: Int, onWeight: (Int) -> Unit,
    fontPx: Int, onFontPx: (Int) -> Unit,
    lineHeight: Int, onLineHeight: (Int) -> Unit,
    publisherStyle: Boolean, onPublisherStyle: (Boolean) -> Unit,
    liftHeads: Boolean, onLiftHeads: (Boolean) -> Unit,
    onReload: () -> Unit, onSaveDefaults: () -> Unit, onReset: () -> Unit,
) {
    FontPanel(
        fonts = fonts,
        selectedId = fontId,
        busyId = fontBusy,
        progress = fontProgress,
        onPick = onPickFont,
        onAdd = onAddFont,
        onRemove = onRemoveFont,
    )
    SizePanel(
        weight = weight,
        onWeight = { w ->
            onWeight(w); prefs.weight = w
            webView()?.evaluateJavascript("Reader.setWeight($w)", null)
        },
        value = fontPx,
        onChange = { v ->
            if (v != fontPx) {
                onFontPx(v); prefs.fontPx = v
                webView()?.evaluateJavascript("Reader.setFont($v)", null)
            }
        },
        lineHeight = lineHeight,
        onLineHeight = { lh ->
            onLineHeight(lh); prefs.lineHeight = lh
            webView()?.evaluateJavascript("Reader.setLineHeight(${lh / 10f})", null)
        },
    )
    com.oasisreader.app.ui.PanelSwitch("책 디자인 사용 (제목·문단 모양)", null, publisherStyle, {
        onPublisherStyle(it); prefs.publisherStyle = it; onReload()
    })
    com.oasisreader.app.ui.PanelSwitch("작은 소제목 키우기", null, liftHeads, {
        onLiftHeads(it); prefs.liftHeads = it; onReload()
    })
    com.oasisreader.app.ui.BookSettingsRow(prefs.hasOwn, onSaveDefaults, onReset)
}

/** The ▤ 쪽 panel: view mode, spreads, page turn, margins, what the bars show. */
@Composable
internal fun ColumnScope.PagePanelBody(
    prefs: ReaderPrefs, webView: () -> WebView?,
    mode: ReaderMode, onMode: (ReaderMode) -> Unit,
    spreadMode: Int, onSpreadMode: (Int) -> Unit,
    turnStyle: com.oasisreader.app.ui.TurnStyle, turnSpeed: com.oasisreader.app.ui.TurnSpeed,
    onTurnStyle: (com.oasisreader.app.ui.TurnStyle) -> Unit, onTurnSpeed: (com.oasisreader.app.ui.TurnSpeed) -> Unit,
    mTop: Int, mBottom: Int, mLeft: Int, mRight: Int, onMargins: (Int, Int, Int, Int) -> Unit,
    readingChrome: ReadingChrome, onChrome: (ReadingChrome) -> Unit,
    onSaveDefaults: () -> Unit, onReset: () -> Unit,
) {
    com.oasisreader.app.ui.PanelChoice("보기", listOf("책처럼 넘기기", "스크롤"), if (mode == ReaderMode.PAGE) 0 else 1) { i ->
        onMode(if (i == 0) ReaderMode.PAGE else ReaderMode.SCROLL)
    }
    if (mode == ReaderMode.PAGE) com.oasisreader.app.ui.PanelChoice("두 쪽 보기", SPREAD_LABELS, spreadMode) { i ->
        onSpreadMode(i); prefs.spreadMode = i
    }
    if (mode == ReaderMode.PAGE) com.oasisreader.app.ui.TurnStylePicker(
        current = turnStyle, speed = turnSpeed,
        onPick = { st ->
            onTurnStyle(st); prefs.turnStyle = st
            webView()?.evaluateJavascript("Reader.setTurn('${st.id}')", null)
        },
        onSpeed = { sp -> onTurnSpeed(sp); prefs.turnSpeed = sp },
    )
    MarginPanel(
        top = mTop, bottom = mBottom, left = mLeft, right = mRight,
        onChange = { t, b, l, r ->
            onMargins(t, b, l, r)
            prefs.marginTop = t; prefs.marginBottom = b; prefs.marginLeft = l; prefs.marginRight = r
        },
    )
    ChromeSwitches(readingChrome, onChange = onChrome)
    com.oasisreader.app.ui.BookSettingsRow(prefs.hasOwn, onSaveDefaults, onReset)
}
