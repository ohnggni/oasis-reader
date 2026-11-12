package com.oasisreader.app.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 독후감: the text work that needs no screen (quoting highlights, joining, exporting, reading a file). */
object ReviewText {
    fun stars(rating: Int): String {
        val r = rating.coerceIn(0, 5)
        return "★".repeat(r) + "☆".repeat(5 - r)
    }

    /** [addition] after [current], a blank line between. */
    fun append(current: String, addition: String): String =
        if (current.isBlank()) addition.trim() else current.trimEnd() + "\n\n" + addition.trim()

    /** A highlight as a markdown quote, its note (the reader's own words) under it. */
    fun quote(text: String, note: String?): String =
        text.trim().lines().joinToString("\n") { "> $it".trimEnd() } +
            (note?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")

    fun quotes(items: List<Pair<String, String?>>): String = items.joinToString("\n\n") { quote(it.first, it.second) }

    /** What 📤 공유 sends: title, stars (when given), the text. */
    fun shareText(title: String, rating: Int, text: String): String =
        (listOf("「$title」") + listOfNotNull(stars(rating).takeIf { rating > 0 })).joinToString("  ") + "\n\n" + text.trim()

    /** One book's 독후감 as a markdown file, its highlights after it. */
    fun markdown(title: String, author: String?, rating: Int, finished: String?, text: String, highlights: List<Pair<String, String?>>): String = buildString {
        append("# ").append(title).append("\n\n")
        val facts = listOfNotNull(
            author?.takeIf { it.isNotBlank() }?.let { "- 저자: $it" },
            rating.takeIf { it > 0 }?.let { "- 별점: ${stars(it)}" },
            finished?.let { "- 다 읽은 날: $it" },
        )
        if (facts.isNotEmpty()) append(facts.joinToString("\n")).append("\n\n")
        if (text.isNotBlank()) append("## 독후감\n\n").append(text.trim()).append("\n")
        if (highlights.isNotEmpty()) {
            if (text.isNotBlank()) append("\n")
            append("## 하이라이트\n\n").append(quotes(highlights)).append("\n")
        }
    }

    /** A text file's bytes as text: UTF-8 (a BOM is dropped), else the Korean Windows code page. */
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) {
            val korean = runCatching { Charset.forName("x-windows-949") }.getOrElse { runCatching { Charset.forName("EUC-KR") }.getOrDefault(Charsets.ISO_8859_1) }
            String(body, korean)
        }
    }
}
