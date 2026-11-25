package com.oasisreader.app.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.ui.Text

/**
 * AI 음성: natural on-device voices come from a separate TTS engine app (BookFusion Voice, free on
 * Google Play, Supertonic/Piper voices). The app ships no model or runtime — it only helps install
 * the engine, open it to download voices, and pick it inside this app (the phone's default engine
 * is left alone). Samsung TTS is not offered to other apps since One UI 7, so this is the way to a
 * better voice than Google's.
 */
object AiVoice {
    const val PKG = "com.bookfusion.voice"
    const val NAME = "BookFusion Voice"
    private const val AWAY = TtsPrefs.AI_AWAY

    private fun prefs(ctx: Context) = TtsPrefs.of(ctx)

    /** Left for the store or the engine app: on coming back the voice list is read again. */
    private fun markAway(ctx: Context) = prefs(ctx).edit().putBoolean(AWAY, true).apply()

    /** True once after coming back from [openStore] / [openApp]. */
    fun takeReturn(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(AWAY, false)) return false
        p.edit().remove(AWAY).apply()
        return true
    }

    fun openStore(ctx: Context) {
        markAway(ctx)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PKG"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** The engine app itself, where voices are downloaded (it has no standard "install voice data" screen). */
    fun openApp(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(PKG)
        if (launch == null) { openStore(ctx); return }
        markAway(ctx)
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The card at the top of the voice dialog: install → use → download voices → in use (no button). */
@Composable
internal fun AiVoiceCard(ra: ReadAloud, engines: List<Pair<String, String>>, engine: String?, voiceCount: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val installed = engines.any { it.first == AiVoice.PKG }
    val using = installed && engine == AiVoice.PKG
    val note: String
    var label: String? = null
    var act: () -> Unit = {}
    when {
        !installed -> {
            note = "기기 안에서 만드는 자연스러운 AI 목소리입니다. 무료 앱 ${AiVoice.NAME}(다른 회사 앱)를 설치하면 여기서 바로 고를 수 있습니다."
            label = "설치하기"; act = { AiVoice.openStore(ctx) }
        }
        !using -> {
            note = "${AiVoice.NAME}가 설치되어 있습니다. 휴대폰 기본 엔진은 그대로 두고 이 앱에서만 씁니다."
            label = "AI 음성 쓰기"; act = { ra.setEngine(AiVoice.PKG); onChanged() }
        }
        ra.available == null -> note = "AI 음성 엔진을 여는 중…"
        ra.available == false -> {
            note = "${AiVoice.NAME}를 열지 못했습니다. 앱을 한 번 실행한 뒤 돌아와 주세요."
            label = "앱 열기"; act = { AiVoice.openApp(ctx) }
        }
        voiceCount == 0 -> {
            note = "${AiVoice.NAME} 앱에서 이 책 언어의 목소리를 받은 뒤 돌아오세요. 받은 목소리가 아래에 저절로 나타납니다."
            label = "목소리 받기"; act = { AiVoice.openApp(ctx) }
        }
        else -> {
            // BookFusion downloads its voices all at once: nothing more to get here
            note = "AI가 만든 목소리로 읽습니다. 아래 목소리를 누르면 바로 들어 볼 수 있습니다."
        }
    }
    val button = label
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (using) "AI 음성 · 사용 중" else "AI 음성", style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (button != null) TextButton(onClick = act) { Text(button) }
            }
        }
    }
}
