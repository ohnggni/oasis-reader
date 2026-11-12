package com.oasisreader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The small chip of the filter rows (서재, 서버 목록, 정리된 카탈로그, 오디오북): emoji + a few
 * words, tight padding, so a whole row fits a regular phone without sideways scrolling.
 * Material chips are 32dp tall with 16dp padding; this one is ~28dp with 8dp.
 * [plain]: no outline — a quiet switch (보기 방식·타일 크기) beside the filters.
 * [description] names the chip for TalkBack.
 */
@Composable
fun MiniChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
    plain: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) cs.secondaryContainer else Color.Transparent,
        contentColor = if (selected) cs.onSecondaryContainer else if (plain) cs.primary else cs.onSurface,
        border = if (selected || plain) null else BorderStroke(0.8.dp, cs.outlineVariant),
        modifier = modifier.heightIn(min = 28.dp).let { m -> if (description != null) m.semantics { contentDescription = description } else m },
    ) {
        Box(Modifier.padding(horizontal = if (plain) 4.dp else 8.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
            EmojiText(
                text,
                fontSize = 12.5.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Leading slot of a drop-down item: ✔️ when chosen, the same width either way so the words line up. */
@Composable
fun CheckMark(on: Boolean) {
    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
        if (on) Text("✔️", fontSize = 13.sp)
    }
}

/** Gap between [MiniChip]s in a row. */
val MiniChipGap = 6.dp

/** "N권"-style count under a list's filters: how many match in all (or how many are loaded so far). */
@Composable
fun CountLine(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrEmpty()) return
    Text(
        text,
        fontSize = 11.5.sp, lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 12345 → "12,345". */
fun Int.grouped(): String = "%,d".format(this)

/** What to say when only a part is known: "40개 이상" while more pages remain, "12개" otherwise. */
fun loadedCount(n: Int, more: Boolean, unit: String = "개"): String = if (more) "${n.grouped()}$unit 이상" else "${n.grouped()}$unit"
