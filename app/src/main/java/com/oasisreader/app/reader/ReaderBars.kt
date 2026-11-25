package com.oasisreader.app.reader

import com.oasisreader.app.ui.EmojiText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape

/** The compact overlay bar: back, title, 🎧, Aa's small menu and ☰. It only ever covers the top margin, never text. */
@Composable
internal fun ReaderTopBar(
    showBars: Boolean, panel: Panel, barColor: Color, chrome: Color,
    readingChrome: ReadingChrome, cut: CutoutRoom, title: String,
    hasBook: Boolean, aloud: Boolean, aaMenu: Boolean,
    onBack: () -> Unit, onAloud: () -> Unit, onAaMenu: (Boolean) -> Unit, onPanel: (Panel) -> Unit,
    panelBody: @Composable ColumnScope.(Panel) -> Unit,
) {
    // 96: while Aa's card is open the bars go, so the page shows the change clearly
        AnimatedVisibility(visible = showBars && panel !in AA_PANELS, enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut())) {
        Surface(
            color = barColor, contentColor = chrome, border = com.oasisreader.app.ui.theme.einkBorder(),
            modifier = Modifier.fillMaxWidth().let { if (readingChrome.hideStatus) it.padding(start = cut.left, end = cut.right) else it },
        ) {
            Column {
                Row(
                    Modifier.height(TOP_BAR_DP.dp).padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = chrome,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    // 90: 🎧 소리 내 읽기 · Aa 보기 (부메뉴 → 아래 카드) · ☰ 이동 — three roomy buttons
                    val on = MaterialTheme.colorScheme.primary
                    if (hasBook) IconButton(onClick = onAloud) {
                        EmojiText("🎧", fontSize = 20.sp, color = if (aloud) on else chrome)
                    }
                    Box {
                        IconButton(onClick = { onAaMenu(true) }) {
                            Text("Aa", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (panel in AA_PANELS) on else chrome)
                        }
                        com.oasisreader.app.ui.OgMenu(expanded = aaMenu, onDismissRequest = { onAaMenu(false) }) {
                            listOf(Triple(Panel.SCREEN, "☀️", "화면"), Triple(Panel.TEXT, "가", "글자"), Triple(Panel.PAGE, "▤", "쪽")).forEach { (pp, e, l) ->
                                DropdownMenuItem(
                                    text = { Text(l) },
                                    leadingIcon = { EmojiText(e, fontSize = 16.sp) },
                                    onClick = { onAaMenu(false); onPanel(pp) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onPanel(panel.toggle(Panel.NAV)) }) {
                        EmojiText("☰", fontSize = 20.sp, color = if (panel == Panel.NAV) on else chrome)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                AnimatedVisibility(
                    visible = panel == Panel.NAV,
                    enter = com.oasisreader.app.ui.theme.calm(expandVertically() + fadeIn()),
                    exit = com.oasisreader.app.ui.theme.calm(shrinkVertically() + fadeOut()),
                ) {
                    Column {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            panelBody(panel)
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/** 90: Aa's panels (화면 · 글자 · 쪽) in a card at the bottom (at the top while the bottom margin is set, 97). */
@Composable
internal fun ReaderAaCard(
    visible: Boolean, which: Panel, cardTop: Boolean, barColor: Color, chrome: Color, modifier: Modifier,
    onPanel: (Panel) -> Unit, onClose: () -> Unit,
    panelBody: @Composable ColumnScope.(Panel) -> Unit,
) {
        AnimatedVisibility(
        visible = visible,
        enter = com.oasisreader.app.ui.theme.calm(fadeIn()),
        exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
        modifier = modifier,
    ) {
            Surface(
            shape = RoundedCornerShape(20.dp),
            color = barColor.copy(alpha = if (com.oasisreader.app.ui.theme.ThemeMode.eink) 1f else 0.94f), contentColor = chrome,
            border = com.oasisreader.app.ui.theme.einkBorder(), shadowElevation = 6.dp,
            modifier = (if (cardTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding()).padding(10.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(Triple(Panel.SCREEN, "☀️", "화면"), Triple(Panel.TEXT, "가", "글자"), Triple(Panel.PAGE, "▤", "쪽")).forEach { (pp, e, l) ->
                        com.oasisreader.app.ui.OgChip(
                            selected = which == pp, onClick = { onPanel(pp) },
                            label = { EmojiText("$e $l", fontSize = 14.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Text("✕", color = chrome) }
                }
                Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { panelBody(which) }
            }
        }
    }
}
