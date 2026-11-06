package com.oasisreader.app.ui

/**
 * 서재 검색: ignores spaces and case, and a Korean initial consonant in the query (ㅇㅍㅍㅅ)
 * matches any syllable that starts with it — mixed is fine too ("원ㅍ" finds 원피스).
 * Also the natural order (1, 2, …, 10) for titles and the fast-scroll initial.
 */
object ShelfSearch {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** The initial consonant (compatibility jamo) of a Hangul syllable, or null. */
    fun choseong(c: Char): Char? = if (c in '가'..'힣') CHO[(c - '가') / 588] else null

    private fun isCho(c: Char) = CHO.indexOf(c) >= 0

    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) if (!c.isWhitespace()) append(c.lowercaseChar())
    }

    /** Does [text] contain [query] (spaces/case ignored, initials allowed)? Blank query: yes. */
    fun matches(text: String?, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        if (text.isNullOrEmpty()) return false
        val t = normalize(text)
        if (t.length < q.length) return false
        outer@ for (i in 0..t.length - q.length) {
            for (j in q.indices) {
                val qc = q[j]
                val tc = t[i + j]
                if (tc == qc) continue
                if (isCho(qc) && choseong(tc) == qc) continue
                continue@outer
            }
            return true
        }
        return false
    }

    /** The fast-scroll bubble for a name: the initial consonant, a capital letter or the first sign. */
    fun initial(s: String?): String {
        val c = s?.trim()?.firstOrNull() ?: return "·"
        choseong(c)?.let { return it.toString() }
        return if (c.isLetter()) c.uppercaseChar().toString() else if (c.isDigit()) "#" else c.toString()
    }

    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

    /** Titles in reading order: digit runs compare as numbers ("2권" before "10권"). */
    val natural: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val da = a[i].isDigit()
            val db = b[j].isDigit()
            val ei = run { var k = i; while (k < a.length && a[k].isDigit() == da) k++; k }
            val ej = run { var k = j; while (k < b.length && b[k].isDigit() == db) k++; k }
            val pa = a.substring(i, ei)
            val pb = b.substring(j, ej)
            val c = if (da && db) {
                val na = pa.trimStart('0')
                val nb = pb.trimStart('0')
                if (na.length != nb.length) na.length - nb.length else na.compareTo(nb).let { if (it != 0) it else pa.length - pb.length }
            } else collator.compare(pa, pb)
            if (c != 0) return c
            i = ei; j = ej
        }
        return (a.length - i) - (b.length - j)
    }
}
