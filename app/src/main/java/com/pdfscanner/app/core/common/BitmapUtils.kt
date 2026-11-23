package com.pdfscanner.app.core.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap helpers optimized for low RAM.
 * - Downsamples at decode time (inSampleSize) so 48MP photos never OOM on 2GB devices.
 * - Downscales to exact maxEdge before OpenCV/OCR.
 */
object BitmapUtils {

    fun decodeSampledFile(path: String, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        val rotated = applyExifRotation(path, decoded)
        val scaled = scaleToMaxEdge(rotated, maxEdge)
        if (scaled !== rotated) safeRecycle(rotated)
        return scaled
    }

    fun decodeSampledUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = try {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotated = rotateByExif(decoded, orientation)
        val scaled = scaleToMaxEdge(rotated, maxEdge)
        if (scaled !== rotated) safeRecycle(rotated)
        return scaled
    }

    fun applyExifRotation(path: String, bmp: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            return bmp
        }
        return rotateByExif(bmp, orientation)
    }

    private fun rotateByExif(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) safeRecycle(bmp)
        return rotated
    }

    private fun calculateInSampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var inSampleSize = 1
        var longEdge = max(w, h)
        while (longEdge / 2 >= maxEdge) {
            inSampleSize *= 2
            longEdge /= 2
        }
        return inSampleSize
    }

    fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val longEdge = max(w, h)
        if (longEdge <= maxEdge) return source
        val scale = maxEdge.toFloat() / longEdge
        val newW = (w * scale).roundToInt().coerceAtLeast(1)
        val newH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    /** Recycle safely (no-op if already recycled). */
    fun safeRecycle(bmp: Bitmap?) {
        try {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        } catch (_: Exception) {
        }
    }
}
