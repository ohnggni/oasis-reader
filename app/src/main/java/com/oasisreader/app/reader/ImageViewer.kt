package com.oasisreader.app.reader

import android.graphics.Bitmap
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SCALE = 6f
private const val MAX_DIM = 3000   // keeps very large charts within bitmap limits

/** Decode a book image file (scaled down if huge). SVG and other formats BitmapFactory can't read give null. */
private suspend fun loadImage(file: File): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** Full-screen image with pinch zoom, pan, and double-tap zoom. */
@Composable
fun ImageViewer(file: File, onClose: () -> Unit) {
    val src = file.path
    BackHandler(onBack = onClose)
    val bmp by produceState<Bitmap?>(null, src) { value = loadImage(file) }
    var loaded by remember(src) { mutableStateOf(false) }
    if (bmp != null) loaded = true

    var scale by remember(src) { mutableFloatStateOf(1f) }
    var offset by remember(src) { mutableStateOf(Offset.Zero) }
    // vertical drag while not zoomed: follow the finger, close past a threshold
    var dismissY by remember(src) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = 1f - (kotlin.math.abs(dismissY) / (constraints.maxHeight * 0.6f)).coerceIn(0f, 0.75f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)))
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val center = Offset(w / 2, h / 2)

        fun clamp(o: Offset, s: Float): Offset {
            val mx = w * (s - 1) / 2
            val my = h * (s - 1) / 2
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        val b = bmp
        if (b == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(src) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                if (scale > 1.05f) {
                                    scale = 1f; offset = Offset.Zero
                                } else {
                                    val ns = 2.5f
                                    offset = clamp((center - p) * (ns - 1f), ns)
                                    scale = ns
                                }
                            },
                        )
                    }
                    .pointerInput(src) {
                        // gesture end: dismiss or spring back
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                            } while (e.changes.any { it.pressed })
                            if (kotlin.math.abs(dismissY) > h * 0.16f) {
                                onClose()
                            } else if (dismissY != 0f) {
                                val from = dismissY
                                scope.launch { animate(from, 0f) { v, _ -> dismissY = v } }
                            }
                        }
                    }
                    .pointerInput(src) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (scale <= 1.01f && zoom == 1f && (dismissY != 0f || kotlin.math.abs(pan.y) > kotlin.math.abs(pan.x))) {
                                dismissY += pan.y
                                return@detectTransformGestures
                            }
                            val ns = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val c = centroid - center
                            // keep the point under the fingers fixed while zooming
                            offset = clamp((offset - c) * (ns / scale) + c + pan, ns)
                            scale = ns
                        }
                    },
            ) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y + dismissY
                        },
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Color.White)
        }

        if (b != null && scale <= 1.01f && dismissY == 0f) {
            Text(
                "두 손가락으로 확대 · 두 번 탭 · 아래로 쓸어 닫기",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            )
        }
    }
}
