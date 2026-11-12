package com.oasisreader.app.stats

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.oasisreader.app.MainActivity
import com.oasisreader.app.R
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.ui.MiniChip
import com.oasisreader.app.ui.MiniChipGap
import com.oasisreader.app.ui.Notice
import com.oasisreader.app.ui.ShelfActions
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** The next [hour]:[minute] on one of [days] (1 = 월 … 7 = 일) after [now]; null when no day is chosen. */
fun nextHabitTime(now: LocalDateTime, hour: Int, minute: Int, days: Set<Int>): LocalDateTime? {
    for (i in 0..7) {
        val d = now.toLocalDate().plusDays(i.toLong())
        val t = d.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        if (d.dayOfWeek.value in days && t.isAfter(now)) return t
    }
    return null
}

/** Days in a row with a minute or more of reading, up to [today] (a day not read yet today does not break it). */
fun readingStreak(secondsByDay: Map<String, Long>, today: LocalDate): Int {
    fun read(d: LocalDate) = (secondsByDay[d.toString()] ?: 0L) >= 60
    var d = if (read(today)) today else today.minusDays(1)
    var n = 0
    while (read(d)) { n++; d = d.minusDays(1) }
    return n
}

/**
 * 독서 습관 알림 (TODO 50): at the chosen time on the chosen days a notification asks to read;
 * a tap opens the book read last. WorkManager carries it (it survives a restart of the phone),
 * so the time can be some minutes late.
 */
object ReadingHabit {
    private const val PREFS = "habit"
    private const val WORK = "reading-habit"
    private const val CHANNEL = "habit"
    private const val NOTE_ID = 7301
    const val EXTRA_BOOK = "habit_book"

    data class Config(val on: Boolean, val hour: Int, val minute: Int, val days: Set<Int>, val skipIfRead: Boolean) {
        val time: String get() = "%d:%02d".format(hour, minute)
    }

    /** the book a tapped notification asked for ("" = just the app); MainActivity opens it */
    var openBook by mutableStateOf<String?>(null)

    fun offer(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_BOOK) ?: return
        intent.removeExtra(EXTRA_BOOK)
        openBook = id
    }

    fun load(c: Context): Config {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            on = p.getBoolean("on", false), hour = p.getInt("hour", 22), minute = p.getInt("minute", 0),
            days = (p.getString("days", "1,2,3,4,5,6,7") ?: "").split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
            skipIfRead = p.getBoolean("skip_if_read", true),
        )
    }

    fun save(c: Context, cfg: Config) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("on", cfg.on).putInt("hour", cfg.hour).putInt("minute", cfg.minute)
            .putString("days", cfg.days.sorted().joinToString(",")).putBoolean("skip_if_read", cfg.skipIfRead).apply()
        schedule(c, ExistingWorkPolicy.REPLACE)
    }

    /** At app start: make sure the next one is waiting (one that already waits is kept). */
    fun ensure(c: Context) = schedule(c, ExistingWorkPolicy.KEEP)

    private fun schedule(c: Context, policy: ExistingWorkPolicy) {
        val app = c.applicationContext
        runCatching {
            val wm = WorkManager.getInstance(app)
            val cfg = load(app)
            val now = LocalDateTime.now()
            val next = if (cfg.on) nextHabitTime(now, cfg.hour, cfg.minute, cfg.days) else null
            if (next == null) { wm.cancelUniqueWork(WORK); return }
            val wait = Duration.between(now, next).toMillis().coerceAtLeast(1_000)
            wm.enqueueUniqueWork(WORK, policy, OneTimeWorkRequestBuilder<Worker>().setInitialDelay(wait, TimeUnit.MILLISECONDS).build())
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            runCatching { remind(applicationContext, force = false) }
            // the next one: after this run ends (replacing here would cancel this very run)
            schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
    }

    /** Shows the notification now ([force]: also when today was read already — 시험해 보기). */
    suspend fun remind(c: Context, force: Boolean) {
        val app = c.applicationContext
        val cfg = load(app)
        val db = AppDatabase.get(app)
        val today = LocalDate.now()
        val byDay = db.readingTime().all().groupBy { it.day }.mapValues { e -> e.value.sumOf { it.seconds } }
        val todaySec = byDay[today.toString()] ?: 0L
        if (!force && cfg.skipIfRead && todaySec >= 60) return
        // the book read last that is not finished (books come newest-read first)
        val books = db.books().all()
        val book = books.filter { it.finishedAt == null }.maxByOrNull { it.lastReadAt ?: 0L }?.takeIf { it.lastReadAt != null }
        val streak = readingStreak(byDay, today)
        val text = listOfNotNull(
            book?.let { "「${it.title}」 이어 읽기" } ?: "서재에서 읽을 책을 골라 보세요",
            if (todaySec >= 60) "오늘 ${todaySec / 60}분 읽음" else null,
            if (streak >= 2) "연속 ${streak}일" else null,
        ).joinToString(" · ")
        val nm = NotificationManagerCompat.from(app)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("독서 습관 알림").build(),
        )
        val open = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BOOK, book?.id.orEmpty())
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle("📖 책 읽을 시간이에요")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(app, NOTE_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTE_ID, n) }
    }

    fun summary(c: Context): String {
        val cfg = load(c)
        if (!cfg.on) return "꺼짐"
        val names = listOf("월", "화", "수", "목", "금", "토", "일")
        val days = if (cfg.days.size == 7) "매일" else cfg.days.sorted().joinToString("·") { names[it - 1] }
        return if (cfg.days.isEmpty()) "요일을 골라 주세요" else "$days ${cfg.time}"
    }
}

/** 설정 › 독서 습관 알림: on/off, the time, the days. */
@Composable
fun ReadingHabitDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    var cfg by remember { mutableStateOf(ReadingHabit.load(app)) }
    fun set(next: ReadingHabit.Config) { cfg = next; ReadingHabit.save(app, next) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Notice.show(context, "알림이 꺼져 있어 독서 알림이 보이지 않습니다. 폰 설정 › 앱 › OG Reader › 알림에서 켜 주세요.")
    }
    fun allowed() = android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val names = listOf("월", "화", "수", "목", "금", "토", "일")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏰ 독서 습관 알림", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("정한 시각에 알림 받기", modifier = Modifier.weight(1f))
                    Switch(checked = cfg.on, onCheckedChange = { v ->
                        set(cfg.copy(on = v))
                        if (v && !allowed()) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("시각", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        android.app.TimePickerDialog(context, { _, h, m -> set(cfg.copy(hour = h, minute = m)) }, cfg.hour, cfg.minute, true).show()
                    }) { Text(cfg.time, style = MaterialTheme.typography.titleMedium) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
                    names.forEachIndexed { i, n ->
                        val d = i + 1
                        MiniChip(n, selected = d in cfg.days, onClick = { set(cfg.copy(days = if (d in cfg.days) cfg.days - d else cfg.days + d)) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘 이미 읽었으면 알리지 않기", modifier = Modifier.weight(1f))
                    Switch(checked = cfg.skipIfRead, onCheckedChange = { v -> set(cfg.copy(skipIfRead = v)) })
                }
                Text(
                    "알림을 누르면 마지막에 읽던 책(오디오북 포함)이 바로 열립니다. 폰이 절전 중이면 몇 분 늦게 올 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            TextButton(onClick = {
                if (!allowed()) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else ShelfActions.scope.launch { ReadingHabit.remind(app, force = true) }
            }) { Text("🔔 지금 시험해 보기") }
        },
    )
}
