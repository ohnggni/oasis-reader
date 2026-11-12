package com.oasisreader.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/**
 * [Text] for labels that start with an emoji ("📚 컬렉션", "⬇️"): with the "단색" icon style the
 * leading emoji is drawn as the app's own icon in the text colour; otherwise, or when the emoji
 * has no icon, this is exactly a [Text]. Same parameters, in the same order, as [Text].
 */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val lead = if (OgIcons.style == OgIcons.EMOJI) null else OgIconPaths.leading(text)
    val icon = lead?.let { OgIcons.icon(it.first) }
    if (lead == null || icon == null) {
        M3Text(
            text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
            textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout = onTextLayout ?: {}, style = style,
        )
        return
    }
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val body = buildAnnotatedString {
        appendInlineContent("og", text.substring(0, lead.second))
        append(text.substring(lead.second))
    }
    val inline = mapOf(
        "og" to InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
        },
    )
    M3Text(
        body, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inlineContent = inline, onTextLayout = onTextLayout ?: {}, style = style,
    )
}

/**
 * The app's Text: screens import this one instead of Material's, so any label that starts with an
 * emoji the app has an icon for is drawn with that icon (see [EmojiText]). Same parameters as Material's.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = EmojiText(
    text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
    textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
)

/** Styled text passes straight through to Material's. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = M3Text(
    text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
    textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
)

/**
 * Appends [text], putting an inline icon in place of every emoji the app has an icon for (not just
 * a leading one). The text it goes into needs [ogInline] as its inlineContent. With [icons] false
 * this is a plain append.
 */
fun AnnotatedString.Builder.appendWithIcons(text: String, icons: Boolean) {
    if (!icons) { append(text); return }
    var i = 0
    var plain = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val len = Character.charCount(cp)
        val name = if (cp < 0x2190) null else OgIconPaths.byEmoji[text.substring(i, i + len)]
        if (name == null) { i += len; continue }
        append(text.substring(plain, i))
        var end = i + len
        if (text.getOrNull(end) == '\uFE0F') end++
        appendInlineContent("og:$name", text.substring(i, end))
        i = end
        plain = end
    }
    append(text.substring(plain))
}

/** Inline content for every icon [appendWithIcons] may have put in, in the current text colour. */
@Composable
fun ogInline(): Map<String, InlineTextContent> {
    val tint = LocalContentColor.current
    return remember(tint) {
        OgIconPaths.paths.keys.associate { name ->
            "og:$name" to InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
                OgIcons.icon(name)?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}
