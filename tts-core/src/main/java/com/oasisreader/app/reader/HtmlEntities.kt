package com.oasisreader.app.reader

/** The HTML entities a chapter's text is read with: numeric ones and the few names books use. */
object HtmlEntities {

    fun decode(s: String): String = s
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&nbsp;", "\u00A0").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&hellip;", "…").replace("&middot;", "·").replace("&amp;", "&")
}
