package com.oasisreader.app.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale

/** How a page turn looks. Stored as its [id]. */
enum class TurnStyle(val id: String, val label: String, val hint: String) {
    SLIDE("slide", "밀기", "옆으로 스르륵 밀려 넘어갑니다"),
    CURL("curl", "종이 말림", "종이책처럼 쪽이 말리며 넘어갑니다"),
    COVER("cover", "덮기", "지금 쪽이 옆으로 빠지고 다음 쪽이 드러납니다"),
    NONE("none", "없음", "효과 없이 바로 바뀝니다");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SLIDE
        /** The curl needs Android 13's runtime shaders; older phones get the cover effect. */
        val curlSupported get() = Build.VERSION.SDK_INT >= 33
    }
}

/** Turn speed for the curl and cover effects. */
enum class TurnSpeed(val id: String, val label: String, val curlMs: Int, val coverMs: Int) {
    SLOW("slow", "느리게", 1800, 700),
    NORMAL("normal", "보통", 1300, 520),
    FAST("fast", "빠르게", 850, 380);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NORMAL }
}

/** Pick a page-turn style (one plain line each) and its speed. */
@Composable
fun TurnStyleDialog(
    current: TurnStyle, speed: TurnSpeed,
    onPick: (TurnStyle) -> Unit, onSpeed: (TurnSpeed) -> Unit, onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("넘김 효과") },
        text = {
            androidx.compose.foundation.layout.Column {
                TurnStyle.entries.forEach { s ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = s == current, onClick = { onPick(s) })
                        androidx.compose.foundation.layout.Column(Modifier.padding(start = 8.dp)) {
                            androidx.compose.material3.Text(s.label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                            androidx.compose.material3.Text(
                                s.hint,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (current == TurnStyle.CURL || current == TurnStyle.COVER) {
                    androidx.compose.material3.Text(
                        "속도", style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    androidx.compose.foundation.layout.Row {
                        TurnSpeed.entries.forEach { sp ->
                            androidx.compose.material3.FilterChip(
                                selected = sp == speed, onClick = { onSpeed(sp) },
                                label = { androidx.compose.material3.Text(sp.label) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("닫기") } },
    )
}

/**
 * A picture of the page that was on screen, animated away over the new page.
 * [tapY]: where (0 top .. 1 bottom) the turn was started: near the top the top corner lifts,
 * near the bottom the bottom corner, in between the page edge at that height.
 * [interactive]: the curl follows [finger] until [release] says whether the turn completes.
 */
class TurnShot(
    val image: ImageBitmap, val forward: Boolean, val style: TurnStyle,
    val tapY: Float = 0.5f, val speed: TurnSpeed = TurnSpeed.NORMAL,
    val interactive: Boolean = false, val id: Long = System.nanoTime(),
) {
    /** finger position in overlay pixels while dragging */
    var finger by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    /** null while the finger is down; true = finish the turn, false = put the page back */
    var release by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
}

/**
 * Overlay for "종이 말림" / "덮기": the old page (a snapshot) curls or slides away and uncovers
 * the new page that is already in place underneath. [onDone] gets false when a dragged curl was
 * let go early and the page went back (the caller then returns to the old page underneath).
 */
@Composable
fun TurnOverlay(
    shot: TurnShot?, paper: Color, onDone: (TurnShot, Boolean) -> Unit,
    modifier: Modifier = Modifier, started: Boolean = true,
) {
    if (shot == null) return
    val curl = shot.style == TurnStyle.CURL && TurnStyle.curlSupported
    if (curl) { CurlOverlay(shot, paper, onDone, modifier, started); return }
    val p = remember(shot.id) { Animatable(0f) }
    LaunchedEffect(shot.id, started) {
        if (!started) return@LaunchedEffect   // e.g. the next chapter is still loading underneath
        p.animateTo(1f, tween(shot.speed.coverMs, easing = FastOutSlowInEasing))
        onDone(shot, true)
    }
    Box(modifier) {
        Image(
            bitmap = shot.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val k = p.value
                translationX = (if (shot.forward) -1f else 1f) * size.width * k
                shadowElevation = 24f * (1f - k)
            },
        )
    }
}

/**
 * The curl is driven by a "finger" point F: the page corner C is lifted and carried to F,
 * like holding the corner of a paper page. Auto turns move F across the page; drags follow
 * the real finger, and on release F goes on across (turn) or back past C (page laid back).
 */
@Composable
private fun CurlOverlay(shot: TurnShot, paper: Color, onDone: (TurnShot, Boolean) -> Unit, modifier: Modifier, started: Boolean) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val r = w * 0.12f
        val corner = androidx.compose.ui.geometry.Offset(
            if (shot.forward) w else 0f,
            when {
                shot.tapY < 0.34f -> 0f
                shot.tapY > 0.66f -> h
                else -> shot.tapY * h
            },
        )
        val gone = androidx.compose.ui.geometry.Offset(if (shot.forward) -w - 3.5f * r else 2 * w + 3.5f * r, corner.y)
        val f = remember(shot.id) { Animatable(corner, androidx.compose.ui.geometry.Offset.VectorConverter) }
        if (shot.interactive) {
            LaunchedEffect(shot.id) {
                androidx.compose.runtime.snapshotFlow { shot.finger }.collect { p ->
                    if (p != null && shot.release == null) f.snapTo(p)
                }
            }
            val rel = shot.release
            LaunchedEffect(shot.id, rel) {
                if (rel == null) return@LaunchedEffect
                val target = if (rel) gone else {
                    // back past the corner, so nothing of the page stays lifted
                    val d = corner - f.value
                    val len = d.getDistance()
                    val dir = if (len < 1f) androidx.compose.ui.geometry.Offset(if (shot.forward) 1f else -1f, 0f) else d / len
                    corner + dir * (Math.PI.toFloat() * r)
                }
                f.animateTo(target, tween(if (rel) shot.speed.curlMs / 2 else 320, easing = FastOutSlowInEasing))
                onDone(shot, rel)
            }
        } else {
            LaunchedEffect(shot.id, started) {
                if (!started) return@LaunchedEffect
                f.animateTo(gone, tween(shot.speed.curlMs, easing = FastOutSlowInEasing))
                onDone(shot, true)
            }
        }
        if (TurnStyle.curlSupported) CurlImage(shot.image, paper, corner, r) { f.value }
    }
}

@RequiresApi(33)
@Composable
private fun CurlImage(image: ImageBitmap, paper: Color, corner: androidx.compose.ui.geometry.Offset, r: Float, finger: () -> androidx.compose.ui.geometry.Offset) {
    val shader = remember { RuntimeShader(CURL_SHADER) }
    // last fold direction: when a page laid back passes its corner, the finger-to-corner
    // direction flips; keep folding the same way (that flip made the next page flash)
    val keep = remember(image) { FloatArray(2) }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val fp = finger()
            // fold direction: from the finger towards the corner; the axis sits where the
            // rolled-over corner lands on the finger
            var nx = corner.x - fp.x
            var ny = corner.y - fp.y
            val len = kotlin.math.sqrt(nx * nx + ny * ny)
            val kept = keep[0] != 0f || keep[1] != 0f
            if (len < 1f) {
                if (kept) { nx = keep[0]; ny = keep[1] } else { nx = if (corner.x > size.width / 2) 1f else -1f; ny = 0f }
            } else {
                nx /= len; ny /= len
                if (kept && nx * keep[0] + ny * keep[1] < 0f) { nx = keep[0]; ny = keep[1] }
                else { keep[0] = nx; keep[1] = ny }
            }
            val uc = corner.x * nx + corner.y * ny
            val uf = fp.x * nx + fp.y * ny
            val cx = (uf + uc - Math.PI.toFloat() * r) / 2f
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("n", nx, ny)
            shader.setFloatUniform("cx", cx)
            shader.setFloatUniform("R", r)
            shader.setColorUniform("paper", paper.toArgb())
            renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "page").asComposeRenderEffect()
        },
    )
}

/**
 * Page curl around a cylinder of radius R. Positions are measured along the unit vector n
 * (u = pos·n); the cylinder axis is the line u = cx. Behind the axis the sheet lies flat, or —
 * once rolled over — its back lies on top; on the cylinder the back half covers the front half;
 * beyond it the next page shows, with a soft shadow next to the curl.
 */
private const val CURL_SHADER = """
uniform shader page;
uniform float2 size;
uniform float2 n;
uniform float cx;
uniform float R;
layout(color) uniform half4 paper;

bool inside(float2 q) { return q.x >= 0.0 && q.y >= 0.0 && q.x <= size.x && q.y <= size.y; }

half4 main(float2 pos) {
    float u = dot(pos, n);
    float d = u - cx;
    float PI = 3.14159265;
    if (d < 0.0) {
        float2 q = pos + (cx + PI * R + (cx - u) - u) * n;
        if (inside(q)) {
            half4 c = page.eval(q);
            return half4(mix(c.rgb, paper.rgb, 0.82), 1.0);
        }
        return page.eval(pos);
    }
    if (d < R) {
        float a = asin(d / R);
        float2 qb = pos + (cx + R * (PI - a) - u) * n;
        if (inside(qb)) {
            half4 c = page.eval(qb);
            half shade = half(0.86 + 0.14 * cos(a));
            return half4(mix(c.rgb, paper.rgb, 0.82) * shade, 1.0);
        }
        float2 qf = pos + (cx + R * a - u) * n;
        if (inside(qf)) {
            half4 c = page.eval(qf);
            half shade = half(0.55 + 0.45 * cos(a));
            return half4(c.rgb * shade, 1.0);
        }
        return half4(0.0);
    }
    float s = (d - R) / (R * 1.5);
    if (s < 1.0) {
        half k = half(0.28 * (1.0 - s) * (1.0 - s));
        return half4(0.0, 0.0, 0.0, k);
    }
    return half4(0.0);
}
"""
