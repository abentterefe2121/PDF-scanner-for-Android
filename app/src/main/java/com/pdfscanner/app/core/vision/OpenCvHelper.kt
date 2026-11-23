package com.pdfscanner.app.core.vision

import android.graphics.Bitmap
import android.util.Log
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenCV helper — lazy-loaded ONLY when Scanner opens (never at app start).
 *
 * Pipeline (Phase 1-2):
 *  1. Downscale to DeviceTier.maxProcessingEdge
 *  2. Grayscale -> Gaussian blur -> Canny edge detection
 *  3. findContours -> largest 4-point polygon (document quad)
 *  4. Perspective warp (flatten page)
 *  5. Enhance: Magic (CLAHE + sharpen + shadow removal), B&W (adaptive threshold), Lighten
 *
 * Ethiopic tuning: larger adaptive-threshold block size so dense fidel strokes
 * are not destroyed. See docs/STRATEGY.md §3.
 *
 * All functions are stubs in Phase 0 that return the input (downscaled).
 * Real OpenCV calls land in Phase 1 once the scanner UI is wired.
 */
@Singleton
class OpenCvHelper @Inject constructor(
    private val deviceTier: DeviceTier
) {
    companion object {
        private const val TAG = "OpenCvHelper"
        @Volatile private var loaded = false

        fun ensureLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("opencv_java4")
                loaded = true
                Log.i(TAG, "OpenCV loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "OpenCV native lib not available: ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "OpenCV load failed: ${e.message}")
                false
            }
        }
    }

    enum class Filter { ORIGINAL, MAGIC, LIGHTEN, BLACK_WHITE }

    /** Live edge detection on 640x480 frame. Returns quad points or null. */
    fun detectDocumentQuad(frame: Bitmap): Array<android.graphics.PointF>? {
        // TODO(Phase 1): OpenCV Canny + contours + approxPolyDP
        // Return null in Phase 0 — overlay shows "Align document" state.
        return null
    }

    /** Perspective-correct + enhance a captured page. */
    fun processPage(source: Bitmap, filter: Filter): Bitmap {
        val scaled = BitmapUtils.scaleToMaxEdge(source, deviceTier.maxProcessingEdge)
        // TODO(Phase 2): warpPerspective + CLAHE / adaptiveThreshold / shadow removal
        return scaled
    }

    /** Sharpness check (Laplacian variance) for auto-capture. */
    fun sharpnessScore(frame: Bitmap): Double {
        // TODO(Phase 1): Laplacian variance — higher = sharper
        return 0.0
    }
}
