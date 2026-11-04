package com.oasisreader.app.stats

import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.data.db.ReadingTimeEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DayCell(val date: LocalDate, val minutes: Int, val reads: Int, val inFuture: Boolean)
data class Bar(val label: String, val reads: Int, val minutes: Int)
data class BookStat(val id: String, val title: String, val minutes: Int, val progress: Float, val finished: Boolean, val audio: Boolean = false)

data class StatsUi(
    val loaded: Boolean = false,
    val todayMinutes: Int = 0,
    val monthReads: Int = 0,
    val yearReads: Int = 0,
    val totalReads: Int = 0,
    val streakDays: Int = 0,
    val totalMinutes: Int = 0,
    val avgMinutes30: Int = 0,
    val todayListenMinutes: Int = 0,   // the part of the minutes that was 오디오북 listening
    val totalListenMinutes: Int = 0,
    val highlightsTotal: Int = 0,
    val highlightsMonth: Int = 0,
    val heat: List<List<DayCell>> = emptyList(),   // weeks (Mon..Sun columns)
    val last7: List<Bar> = emptyList(),
    val monthly: List<Bar> = emptyList(),
    val yearly: List<Bar> = emptyList(),
    val books: List<BookStat> = emptyList(),
) {
    val isEmpty get() = loaded && totalReads == 0 && totalMinutes == 0
}

/** Everything is local: reading seconds per (day, book), finished dates, highlights. */
object StatsCalculator {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun compute(
        books: List<BookEntity>,
        time: List<ReadingTimeEntity>,
        highlights: List<HighlightEntity>,
        today: LocalDate = LocalDate.now(),
    ): StatsUi {
        val secByDay = HashMap<LocalDate, Long>()
        val secByBook = HashMap<String, Long>()
        time.forEach { t ->
            runCatching { LocalDate.parse(t.day) }.getOrNull()?.let { d -> secByDay[d] = (secByDay[d] ?: 0) + t.seconds }
            secByBook[t.bookId] = (secByBook[t.bookId] ?: 0) + t.seconds
        }

        // 오디오북 time counts as reading time (heatmap, streak, bars) and is also shown on its own
        val audioIds = books.filter { it.format == "audiobook" }.map { it.id }.toSet()
        val listenToday = time.filter { it.bookId in audioIds && it.day == today.toString() }.sumOf { it.seconds }
        val listenTotal = time.filter { it.bookId in audioIds }.sumOf { it.seconds }

        val readDay = books.mapNotNull { b -> b.finishedAt?.let { b.id to day(it) } }
        val readsByDay = readDay.groupingBy { it.second }.eachCount()

        fun minutes(d: LocalDate) = ((secByDay[d] ?: 0) / 60).toInt()

        // streak: consecutive days with a finished book or >= 1 minute of reading,
        // ending today (or yesterday if nothing yet today)
        fun activeDay(d: LocalDate) = (readsByDay[d] ?: 0) > 0 || (secByDay[d] ?: 0) >= 60
        var streak = 0
        var d = if (activeDay(today)) today else today.minusDays(1)
        while (activeDay(d)) { streak++; d = d.minusDays(1) }

        // heatmap: 18 weeks, Monday-first columns, ending this week
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val first = weekStart.minusWeeks(17)
        val heat = (0 until 18).map { w ->
            (0 until 7).map { i ->
                val dd = first.plusDays((w * 7 + i).toLong())
                DayCell(dd, minutes(dd), readsByDay[dd] ?: 0, dd.isAfter(today))
            }
        }

        val last7 = (6 downTo 0).map { back ->
            val dd = today.minusDays(back.toLong())
            Bar("${dd.monthValue}/${dd.dayOfMonth}", readsByDay[dd] ?: 0, minutes(dd))
        }

        val thisMonth = YearMonth.from(today)
        val monthly = (11 downTo 0).map { back ->
            val ym = thisMonth.minusMonths(back.toLong())
            val r = readDay.count { YearMonth.from(it.second) == ym }
            val m = secByDay.filterKeys { YearMonth.from(it) == ym }.values.sum() / 60
            Bar(if (ym.monthValue == 1 || back == 11) "${ym.year % 100}.${ym.monthValue}" else "${ym.monthValue}월", r, m.toInt())
        }

        val years = (readDay.map { it.second.year } + secByDay.keys.map { it.year } + today.year).toSortedSet()
        val yearly = years.map { y ->
            Bar("$y", readDay.count { it.second.year == y }, (secByDay.filterKeys { it.year == y }.values.sum() / 60).toInt())
        }

        val bookStats = books.map { b ->
            BookStat(b.id, b.title, ((secByBook[b.id] ?: 0) / 60).toInt(), b.progress, b.finishedAt != null, b.id in audioIds)
        }.filter { it.minutes > 0 || it.finished }
            .sortedWith(compareByDescending<BookStat> { it.minutes }.thenBy { it.title })

        val last30 = (0 until 30).sumOf { secByDay[today.minusDays(it.toLong())] ?: 0 }
        val hlDays = highlights.map { day(it.createdAt) }

        return StatsUi(
            loaded = true,
            todayMinutes = minutes(today),
            monthReads = readDay.count { YearMonth.from(it.second) == thisMonth },
            yearReads = readDay.count { it.second.year == today.year },
            totalReads = readDay.size,
            streakDays = streak,
            totalMinutes = (secByDay.values.sum() / 60).toInt(),
            avgMinutes30 = (last30 / 60 / 30).toInt(),
            todayListenMinutes = (listenToday / 60).toInt(),
            totalListenMinutes = (listenTotal / 60).toInt(),
            highlightsTotal = highlights.size,
            highlightsMonth = hlDays.count { YearMonth.from(it) == thisMonth },
            heat = heat,
            last7 = last7,
            monthly = monthly,
            yearly = yearly,
            books = bookStats,
        )
    }
}
