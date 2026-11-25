package com.oasisreader.app.reader

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

/**
 * Active reading time: counted only while the reader is in front and the reader has
 * turned/scrolled/tapped within the last 3 minutes.
 */
@Composable
internal fun ReadingTimeTracker(
    bookId: String, db: AppDatabase, lifecycleOwner: LifecycleOwner,
    lastActive: () -> Long, ready: () -> Boolean,
) {
    LaunchedEffect(bookId) {
        var pending = 0L
        suspend fun flush() {
            if (pending <= 0) return
            val add = pending; pending = 0
            val day = LocalDate.now().toString()
            val dao = db.readingTime()
            val cur = dao.get(day, bookId)?.seconds ?: 0L
            dao.upsert(ReadingTimeEntity(day, bookId, cur + add))
        }
        try {
            while (true) {
                delay(5_000)
                val front = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                val active = System.currentTimeMillis() - lastActive() < 180_000
                if (front && active && ready()) pending += 5
                if (pending >= 30) flush()
            }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }
}

/**
 * Window flags for this screen: keep it on while reading (released after 10 minutes without
 * reading activity) and its own brightness (back to the system level when leaving the reader).
 */
@Composable
internal fun ReaderWindowEffects(window: android.view.Window?, keepOn: Boolean, brightness: Float, lastActive: () -> Long) {
    // keep the screen on while reading; released after 10 minutes without any reading activity
    LaunchedEffect(keepOn, window) {
        val w = window ?: return@LaunchedEffect
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!keepOn) { w.clearFlags(flag); return@LaunchedEffect }
        try {
            while (true) {
                if (System.currentTimeMillis() - lastActive() < 600_000) w.addFlags(flag) else w.clearFlags(flag)
                delay(15_000)
            }
        } finally {
            w.clearFlags(flag)
        }
    }
    LaunchedEffect(brightness) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
}
