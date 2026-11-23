package com.pdfscanner.app.core.common

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap helpers optimized for low RAM.
 * - Downscale early (before OpenCV/OCR) to avoid OOM on 2GB devices.
 * - Never keep two full-size copies.
 */
object BitmapUtils {

    fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val longEdge = max(w, h)
        if (longEdge <= maxEdge) return source
        val scale = maxEdge.toFloat() / longEdge
        val newW = (w * scale).roundToInt().coerceAtLeast(1)
        val newH = (h * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, newW, newH, true)
        if (scaled !== source && !source.isRecycled) {
            // Caller owns source; we do NOT recycle here to avoid surprises.
            // Processing code should recycle source after use if it created it.
        }
        return scaled
    }

    /** Recycle safely (no-op if already recycled). */
    fun safeRecycle(bmp: Bitmap?) {
        try {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        } catch (_: Exception) {
            // ignore
        }
    }
}
