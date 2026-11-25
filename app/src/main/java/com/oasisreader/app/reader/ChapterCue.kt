package com.oasisreader.app.reader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 장 넘김 소리 (83, 2.9): one slow, soft page swish made here — noise through a two-pole lowpass
 * that slides from bright to dull under a smooth rise and long fall (no crackle, no sound file:
 * nothing to license, nothing added to the APK). 진석님 picked this one ("A") and asked for it
 * about twice as slow. Played on the media stream, so it comes out wherever the voice does.
 */
object ChapterCue {
    private const val RATE = TtsTuning.CUE_SAMPLE_RATE
    private const val LEN_S = TtsTuning.CUE_LEN_S
    /** How long the sound lasts; reading goes on after it. */
    const val MS = TtsTuning.CUE_MS

    private val pcm: ShortArray by lazy { make() }

    private fun make(): ShortArray {
        val n = (RATE * LEN_S).toInt()
        val v = DoubleArray(n)
        val rnd = Random(7)
        var y1 = 0.0; var y2 = 0.0; var hp = 0.0; var prev = 0.0
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val rise = sin(PI * minOf(1.0, x / 0.35) / 2)
            val env = rise * rise * (1 - x).pow(1.6)          // rise ~0.22 s, long fall
            val fc = 4200 * (1 - x) + 700 * x                 // 4.2 kHz → 0.7 kHz
            val a = 1 - exp(-2 * PI * fc / RATE)
            val w = rnd.nextDouble() * 2 - 1
            y1 += a * (w - y1); y2 += a * (y1 - y2)           // two poles: smooth, not crackly
            hp = 0.985 * (hp + y2 - prev); prev = y2          // drop the rumble
            v[i] = hp * env
        }
        // about −24 dBFS rms (beside a voice), never above 0.9 peak
        val rms = sqrt(v.sumOf { it * it } / n).coerceAtLeast(1e-9)
        var g = 10.0.pow(-24.0 / 20) / rms
        val peak = v.maxOf { kotlin.math.abs(it) } * g
        if (peak > 0.9) g *= 0.9 / peak
        return ShortArray(n) { ((v[it] * g).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Play it once (returns at once; it lasts [MS]). Quiet failures: a missing cue never stops reading. */
    fun play() {
        runCatching {
            val data = pcm
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, MS + 300)
        }
    }
}
