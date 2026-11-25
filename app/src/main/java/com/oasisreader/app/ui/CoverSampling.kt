package com.oasisreader.app.ui

/** How a stored cover is read for the size it is shown at. */
internal data class CoverRead(
    val sample: Int,        // BitmapFactory inSampleSize
    val scale: Float,       // a further shrink of the decoded picture (1 = none)
    val full: Boolean,      // nothing was given up for the shown size: as large as a cover is ever read
)

/**
 * [srcW]x[srcH] shown (cropped) in [wantW]x[wantH] pixels: both sides stay at least as large as
 * shown, and never larger than before (height read down to about [maxH]). A want of 0 means the
 * shown size is not known: read as before.
 */
internal fun coverRead(srcW: Int, srcH: Int, wantW: Int, wantH: Int, maxH: Int = 600): CoverRead {
    var s = 1
    while (srcH / (s * 2) >= maxH) s *= 2
    if (wantW <= 0 || wantH <= 0) return CoverRead(s, 1f, true)
    val usual = s
    while (srcW / (s * 2) >= wantW && srcH / (s * 2) >= wantH) s *= 2
    val w = (srcW / s).coerceAtLeast(1)
    val h = (srcH / s).coerceAtLeast(1)
    val fit = maxOf(wantW.toFloat() / w, wantH.toFloat() / h)
    // a second pass only when it saves a good part of the memory (under 0.8 each side: 36 % or more)
    val scale = if (fit < 0.8f) fit else 1f
    return CoverRead(s, scale, s == usual && scale == 1f)
}
