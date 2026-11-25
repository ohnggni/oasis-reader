package com.oasisreader.app.comic

import com.oasisreader.app.ui.EmojiText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import com.oasisreader.app.reader.barTopInset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The panels under Aa (98), shown in the bottom card. */
internal val COMIC_AA_PANELS = setOf(com.oasisreader.app.ui.ReadPanel.SCREEN, com.oasisreader.app.ui.ReadPanel.TEXT, com.oasisreader.app.ui.ReadPanel.PAGE)

/** 98: the compact bar: back, title, Aa's small menu and ☰ (it goes while Aa's card is open). */
@Composable
internal fun ComicTopBar(
    barsVisible: Boolean, panel: com.oasisreader.app.ui.ReadPanel, aaMenu: Boolean,
    readingChrome: com.oasisreader.app.reader.ReadingChrome, cut: com.oasisreader.app.reader.CutoutRoom,
    barColor: Color, chrome: Color, title: String,
    onBack: () -> Unit, onAaMenu: (Boolean) -> Unit, onPanel: (com.oasisreader.app.ui.ReadPanel) -> Unit,
    panelBody: @Composable androidx.compose.foundation.layout.ColumnScope.(com.oasisreader.app.ui.ReadPanel) -> Unit,
) {
        AnimatedVisibility(
        visible = barsVisible && panel !in COMIC_AA_PANELS,
        enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
        modifier = Modifier.barTopInset(readingChrome, cut),
    ) {
        Surface(color = barColor, contentColor = chrome, border = com.oasisreader.app.ui.theme.einkBorder()) {
            Column {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 98: Aa (화면 · 보기 · 넘김 → the card at the bottom) · ☰ — like the book screen (90)
                    val on = MaterialTheme.colorScheme.primary
                    Box {
                        IconButton(onClick = { onAaMenu(true) }) {
                            Text("Aa", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (panel in COMIC_AA_PANELS) on else chrome)
                        }
                        com.oasisreader.app.ui.OgMenu(expanded = aaMenu, onDismissRequest = { onAaMenu(false) }) {
                            listOf(Triple(com.oasisreader.app.ui.ReadPanel.SCREEN, "☀️", "화면"), Triple(com.oasisreader.app.ui.ReadPanel.TEXT, "▣", "보기"), Triple(com.oasisreader.app.ui.ReadPanel.PAGE, "⇆", "넘김")).forEach { (pp, e, l) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(l) },
                                    leadingIcon = { EmojiText(e, fontSize = 16.sp) },
                                    onClick = { onAaMenu(false); onPanel(pp) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onPanel(panel.toggle(com.oasisreader.app.ui.ReadPanel.NAV)) }, modifier = Modifier.padding(end = 6.dp)) {
                        EmojiText("☰", fontSize = 20.sp, color = if (panel == com.oasisreader.app.ui.ReadPanel.NAV) on else chrome)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                AnimatedVisibility(
                    visible = panel == com.oasisreader.app.ui.ReadPanel.NAV,
                    enter = com.oasisreader.app.ui.theme.calm(androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically()),
                    exit = com.oasisreader.app.ui.theme.calm(androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        panelBody(panel)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 98: Aa's panels in a card at the bottom, no dimming; tabs move between the three. */
@Composable
internal fun ComicAaCard(
    visible: Boolean, which: com.oasisreader.app.ui.ReadPanel, barColor: Color, chrome: Color, modifier: Modifier,
    onPanel: (com.oasisreader.app.ui.ReadPanel) -> Unit, onClose: () -> Unit,
    panelBody: @Composable androidx.compose.foundation.layout.ColumnScope.(com.oasisreader.app.ui.ReadPanel) -> Unit,
) {
        AnimatedVisibility(
        visible = visible,
        enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
        modifier = modifier,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = barColor.copy(alpha = if (com.oasisreader.app.ui.theme.ThemeMode.eink) 1f else 0.94f), contentColor = chrome,
            border = com.oasisreader.app.ui.theme.einkBorder(), shadowElevation = 6.dp,
            modifier = Modifier.navigationBarsPadding().padding(10.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(Triple(com.oasisreader.app.ui.ReadPanel.SCREEN, "☀️", "화면"), Triple(com.oasisreader.app.ui.ReadPanel.TEXT, "▣", "보기"), Triple(com.oasisreader.app.ui.ReadPanel.PAGE, "⇆", "넘김")).forEach { (pp, e, l) ->
                        com.oasisreader.app.ui.OgChip(
                            selected = which == pp, onClick = { onPanel(pp) },
                            label = { EmojiText("$e $l", fontSize = 14.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Text("✕", color = chrome) }
                }
                Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { panelBody(which) }
            }
        }
    }
}
