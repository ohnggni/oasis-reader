package com.oasisreader.app.comic

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.ReadingTimeEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.lifecycle.LifecycleOwner

/** Active reading time (same rule as the book reader). */
@Composable
internal fun ComicReadingTime(bookId: String, db: AppDatabase, lifecycleOwner: LifecycleOwner, lastActive: () -> Long) {
    LaunchedEffect(bookId) {
        var pending = 0L
        suspend fun flush() {
            if (pending <= 0) return
            val add = pending; pending = 0
            val day = LocalDate.now().toString()
            val cur = db.readingTime().get(day, bookId)?.seconds ?: 0L
            db.readingTime().upsert(ReadingTimeEntity(day, bookId, cur + add))
        }
        try {
            while (true) {
                delay(5_000)
                val front = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (front && System.currentTimeMillis() - lastActive() < 180_000) pending += 5
                if (pending >= 30) flush()
            }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }
}

/** Keep the screen on while reading (released after 10 minutes idle) and this screen's own brightness. */
@Composable
internal fun ComicWindowEffects(window: android.view.Window?, keepOn: Boolean, brightness: Float, lastActive: () -> Long) {
    LaunchedEffect(keepOn, window) {
        val w = window ?: return@LaunchedEffect
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!keepOn) { w.clearFlags(flag); return@LaunchedEffect }
        try {
            while (true) {
                if (System.currentTimeMillis() - lastActive() < 600_000) w.addFlags(flag) else w.clearFlags(flag)
                delay(15_000)
            }
        } finally { w.clearFlags(flag) }
    }
    LaunchedEffect(brightness, window) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
}
