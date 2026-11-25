package com.oasisreader.app.comic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.oasisreader.app.reader.saveChrome
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ColumnScope
import android.content.SharedPreferences

/** ☀ 화면: brightness, screen-on, bar auto-hide, volume keys and what the bars show for this book. */
@Composable
internal fun ColumnScope.ComicScreenPanel(
    readerPrefs: com.oasisreader.app.reader.ReaderPrefs,
    brightness: Float, onBrightness: (Float) -> Unit,
    keepOn: Boolean, onKeepOn: (Boolean) -> Unit,
    autoHide: Boolean, onAutoHide: (Boolean) -> Unit,
    readingChrome: com.oasisreader.app.reader.ReadingChrome, onChrome: (com.oasisreader.app.reader.ReadingChrome) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.oasisreader.app.reader.BrightnessPanel(brightness) { onBrightness(it); readerPrefs.brightness = it }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        com.oasisreader.app.ui.PanelSwitch("화면 켜짐 유지", null, keepOn, { onKeepOn(it); readerPrefs.keepScreenOn = it }, Modifier.weight(1f))
        com.oasisreader.app.ui.PanelSwitch("바 자동 숨김", null, autoHide, {
            onAutoHide(it); readerPrefs.autoHideBars = it
        }, Modifier.weight(1f))
    }
    // 🔊 볼륨 버튼으로 쪽 넘기기 (the same setting as in 설정, easier to find here)
    var volKeys by remember { mutableStateOf(com.oasisreader.app.ui.PageKeys.volumeKeys(context)) }
    com.oasisreader.app.ui.PanelSwitch("🔊 볼륨 버튼으로 쪽 넘기기", null, volKeys, { volKeys = it; com.oasisreader.app.ui.PageKeys.setVolumeKeys(context, it) })
    // 이 책만 (책별 설정, 기본값은 읽기 기본값)
    com.oasisreader.app.reader.ChromeSwitches(readingChrome, comic = true) { c ->
        onChrome(c)
        readerPrefs.saveChrome(c)
    }
}

/** ▣ 보기: page or webtoon scroll, direction, facing pages, width. */
@Composable
internal fun ColumnScope.ComicViewPanel(
    prefs: SharedPreferences, modeKey: String,
    mode: ComicMode, onMode: (ComicMode) -> Unit,
    rtl: Boolean, onRtl: (Boolean) -> Unit,
    coverAlone: Boolean, onCoverAlone: (Boolean) -> Unit,
    narrow: Boolean, onNarrow: (Boolean) -> Unit,
) {
    com.oasisreader.app.ui.PanelChoice("보기", listOf("책처럼 넘기기", "세로 스크롤 (웹툰)"), if (mode == ComicMode.PAGE) 0 else 1) { i ->
        val m = if (i == 0) ComicMode.PAGE else ComicMode.SCROLL
        onMode(m)
        prefs.edit().putString(modeKey, m.name).apply()
    }
    if (mode == ComicMode.PAGE) {
        com.oasisreader.app.ui.PanelChoice("방향", listOf("왼쪽 → 오른쪽", "오른쪽 → 왼쪽 (일본 만화)"), if (rtl) 1 else 0) { i ->
            onRtl(i == 1); prefs.edit().putBoolean("rtl", i == 1).apply()
        }
        com.oasisreader.app.ui.PanelChoice("맞쪽", listOf("첫 장은 혼자", "첫 장도 짝 지어"), if (coverAlone) 0 else 1) { i ->
            onCoverAlone(i == 0); prefs.edit().putBoolean("cover_alone", i == 0).apply()
        }
    } else {
        com.oasisreader.app.ui.PanelChoice("폭", listOf("화면 너비에 맞추기", "가운데 좁게"), if (narrow) 1 else 0) { i ->
            onNarrow(i == 1); prefs.edit().putBoolean("narrow", i == 1).apply()
        }
    }
}

/** ⇆ 넘김: the page-turn effect (none in the webtoon scroll). */
@Composable
internal fun ColumnScope.ComicTurnPanel(
    readerPrefs: com.oasisreader.app.reader.ReaderPrefs, paged: Boolean,
    turnStyle: com.oasisreader.app.ui.TurnStyle, onTurnStyle: (com.oasisreader.app.ui.TurnStyle) -> Unit,
    turnSpeed: com.oasisreader.app.ui.TurnSpeed, onTurnSpeed: (com.oasisreader.app.ui.TurnSpeed) -> Unit,
) {
    if (paged) com.oasisreader.app.ui.TurnStylePicker(
        current = turnStyle, speed = turnSpeed,
        onPick = { st -> onTurnStyle(st); readerPrefs.turnStyle = st },
        onSpeed = { sp -> onTurnSpeed(sp); readerPrefs.turnSpeed = sp },
    ) else Text(
        "세로 스크롤에서는 넘김 효과가 없습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** ☰ 이동: bookmarks, from the start, read / unread, next volume, the folder it came from. */
@Composable
internal fun ColumnScope.ComicNavPanel(
    bookmarkCount: Int, finished: Boolean, canOrigin: Boolean,
    onMarks: () -> Unit, onRestart: () -> Unit, onFinished: () -> Unit, onNext: () -> Unit, onFolder: () -> Unit,
) {
    fun T(e: String, l: String, f: () -> Unit) = com.oasisreader.app.ui.PanelTile(e, l, f)
    com.oasisreader.app.ui.PanelGrid(buildList {
        add(T("🔖", if (bookmarkCount == 0) "책갈피" else "책갈피 $bookmarkCount", onMarks))
        add(T("⏮️", "처음부터", onRestart))
        add(T(if (finished) "↩️" else "✅", if (finished) "읽음 취소" else "다 읽음", onFinished))
        add(T("⏭️", "다음 권", onNext))
        if (canOrigin) add(T("📂", "있던 폴더", onFolder))
    })
}
