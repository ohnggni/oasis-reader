package com.oasisreader.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
    style: TextStyle = LocalTextStyle.current,
) {
    val lead = if (OgIcons.style == OgIcons.EMOJI) null else OgIconPaths.leading(text)
    val icon = lead?.let { OgIcons.icon(it.first) }
    if (lead == null || icon == null) {
        Text(
            text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
            textAlign, lineHeight, overflow, softWrap, maxLines, minLines, style = style,
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
    Text(
        body, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inlineContent = inline, style = style,
    )
}
