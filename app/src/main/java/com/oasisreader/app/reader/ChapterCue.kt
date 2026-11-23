package com.oasisreader.app.reader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 장 넘김 소리 (83, 2.9): a short page-turn sound made here — two soft paper swishes from shaped
 * noise, no sound file (nothing to license, nothing added to the APK). Played on the media
 * stream, so it comes out wherever the voice does (speaker, earphones, car).
 */
object ChapterCue {
    private const val RATE = 22050
    /** How long the sound lasts; reading goes on after it. */
    const val MS = 420L

    private val pcm: ShortArray by lazy { make() }

    private fun make(): ShortArray {
        val n = (RATE * 0.36).toInt()
        val out = ShortArray(n)
        val rnd = Random(83)
        var lp = 0.0
        var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            // two swishes: a light lift (0–0.12 s) and the page falling over (0.13–0.36 s)
            val a = swell(t, 0.0, 0.12, 0.35) + swell(t, 0.13, 0.23, 0.6)
            val white = rnd.nextDouble() * 2 - 1
            lp += 0.35 * (white - lp)            // soften the hiss
            val hp = lp - prev; prev = lp        // keep the papery top
            val v = (hp * 2.2 + lp * 0.5) * a
            out[i] = ((v * 1.0).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** A bump from [start] lasting [len] seconds, peak [peak]: quick rise, gentle fall. */
    private fun swell(t: Double, start: Double, len: Double, peak: Double): Double {
        val x = (t - start) / len
        if (x <= 0 || x >= 1) return 0.0
        return peak * sin(PI * x) * exp(-1.5 * x)
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
