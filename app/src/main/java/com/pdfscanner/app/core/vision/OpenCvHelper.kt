package com.pdfscanner.app.core.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OpenCV 4.14.0 helper — lazy-loaded ONLY when Scanner/Editor opens.
 * - Live edge detection checks top contours for a clean 4-corner document quad.
 * - Perspective warp preserves true quad aspect ratio and caps output at maxProcessingEdge.
 * - Filters:
 *   - ORIGINAL: clean warped document
 *   - MAGIC: true color document enhancement (YCrCb CLAHE + luminance contrast & mild sharpen; preserves stamps/ink!)
 *   - LIGHTEN: shadow-removal background division
 *   - BLACK_WHITE: adaptive Gaussian threshold (block=51) tuned for dense Ethiopic fidel strokes
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
                Log.w(TAG, "OpenCV not available: ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "OpenCV load failed: ${e.message}")
                false
            }
        }
    }

    enum class Filter { ORIGINAL, MAGIC, LIGHTEN, BLACK_WHITE }

    /** Live edge detection on analysis frame. Returns ordered quad [TL, TR, BR, BL] in frame coords or null. */
    fun detectDocumentQuad(frame: Bitmap): Array<PointF>? {
        if (!ensureLoaded()) return null
        var mat: Mat? = null
        var gray: Mat? = null
        var blurred: Mat? = null
        var edges: Mat? = null
        val contours = mutableListOf<MatOfPoint>()
        var tempCopy: Bitmap? = null
        return try {
            val src = if (frame.config == Bitmap.Config.ARGB_8888) {
                frame
            } else {
                frame.copy(Bitmap.Config.ARGB_8888, false).also { tempCopy = it }
            }
            mat = Mat()
            Utils.bitmapToMat(src, mat)
            gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            edges = Mat()
            Imgproc.Canny(blurred, edges, 65.0, 180.0)

            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            val minArea = frame.width * frame.height * 0.08
            val candidates = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(4)

            for ((contour, _) in candidates) {
                val c2f = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val peri = Imgproc.arcLength(c2f, true)
                    Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                    if (approx.rows() == 4) {
                        val pts = approx.toArray()
                        val sorted = pts.sortedWith(compareBy<Point>({ it.y }, { it.x }))
                        val top = sorted.take(2).sortedBy { it.x }
                        val bottom = sorted.takeLast(2).sortedBy { it.x }
                        val ordered = arrayOf(top[0], top[1], bottom[1], bottom[0])
                        return ordered.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                    }
                } finally {
                    c2f.release()
                    approx.release()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            null
        } finally {
            try { mat?.release() } catch (_: Exception) {}
            try { gray?.release() } catch (_: Exception) {}
            try { blurred?.release() } catch (_: Exception) {}
            try { edges?.release() } catch (_: Exception) {}
            for (c in contours) try { c.release() } catch (_: Exception) {}
            try { tempCopy?.recycle() } catch (_: Exception) {}
        }
    }

    /** Perspective-correct (if quad given) + enhance. Input is full-res capture. */
    fun processPage(
        source: Bitmap,
        filter: Filter,
        quad: Array<PointF>? = null,
        quadFrameWidth: Int = 640,
        quadFrameHeight: Int = 480
    ): Bitmap {
        val scaled = BitmapUtils.scaleToMaxEdge(source, deviceTier.maxProcessingEdge)
        if (!ensureLoaded()) return scaled
        if (filter == Filter.ORIGINAL && quad == null) return scaled
        return try {
            warpAndEnhance(scaled, filter, quad, quadFrameWidth, quadFrameHeight).also {
                if (scaled !== source && it !== scaled) {
                    try { scaled.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "processPage failed, returning scaled: ${e.message}")
            scaled
        }
    }

    private fun warpAndEnhance(
        src: Bitmap,
        filter: Filter,
        quad: Array<PointF>?,
        quadFrameWidth: Int,
        quadFrameHeight: Int
    ): Bitmap {
        var mat: Mat? = null
        var warped: Mat? = null
        var gray: Mat? = null
        var out: Mat? = null
        var tempCopy: Bitmap? = null
        return try {
            val argb = if (src.config == Bitmap.Config.ARGB_8888) {
                src
            } else {
                src.copy(Bitmap.Config.ARGB_8888, false).also { tempCopy = it }
            }
            mat = Mat()
            Utils.bitmapToMat(argb, mat)

            if (quad != null && quad.size == 4 && quadFrameWidth > 0 && quadFrameHeight > 0) {
                val scaleX = src.width.toFloat() / quadFrameWidth.toFloat()
                val scaleY = src.height.toFloat() / quadFrameHeight.toFloat()
                val p0 = Point((quad[0].x * scaleX).toDouble(), (quad[0].y * scaleY).toDouble())
                val p1 = Point((quad[1].x * scaleX).toDouble(), (quad[1].y * scaleY).toDouble())
                val p2 = Point((quad[2].x * scaleX).toDouble(), (quad[2].y * scaleY).toDouble())
                val p3 = Point((quad[3].x * scaleX).toDouble(), (quad[3].y * scaleY).toDouble())

                val widthTop = hypot(p1.x - p0.x, p1.y - p0.y)
                val widthBottom = hypot(p2.x - p3.x, p2.y - p3.y)
                val heightLeft = hypot(p3.x - p0.x, p3.y - p0.y)
                val heightRight = hypot(p2.x - p1.x, p2.y - p1.y)

                var w = max(max(widthTop, widthBottom).toInt(), 64)
                var h = max(max(heightLeft, heightRight).toInt(), 64)
                val maxEdge = deviceTier.maxProcessingEdge
                val longest = max(w, h)
                if (longest > maxEdge) {
                    val s = maxEdge.toDouble() / longest.toDouble()
                    w = max((w * s).roundToInt(), 64)
                    h = max((h * s).roundToInt(), 64)
                }

                val srcPts = MatOfPoint2f(p0, p1, p2, p3)
                val dstPts = MatOfPoint2f(
                    Point(0.0, 0.0), Point(w.toDouble(), 0.0),
                    Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble())
                )
                val persp = Imgproc.getPerspectiveTransform(srcPts, dstPts)
                warped = Mat()
                Imgproc.warpPerspective(mat, warped, persp, Size(w.toDouble(), h.toDouble()))
                persp.release()
                srcPts.release()
                dstPts.release()
            } else {
                warped = mat.clone()
            }

            if (filter == Filter.ORIGINAL) {
                val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, result)
                return result
            }

            out = when (filter) {
                Filter.ORIGINAL -> warped.clone()
                Filter.BLACK_WHITE -> {
                    gray = Mat()
                    Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
                    val bw = Mat()
                    Imgproc.adaptiveThreshold(
                        gray, bw, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 51, 10.0
                    )
                    val rgba = Mat()
                    Imgproc.cvtColor(bw, rgba, Imgproc.COLOR_GRAY2RGBA)
                    bw.release()
                    rgba
                }
                Filter.LIGHTEN -> {
                    gray = Mat()
                    Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
                    val bg = Mat()
                    Imgproc.GaussianBlur(gray, bg, Size(0.0, 0.0), 50.0)
                    val div = Mat()
                    Core.divide(gray, bg, div, 255.0, CvType.CV_8UC1)
                    val rgba = Mat()
                    Imgproc.cvtColor(div, rgba, Imgproc.COLOR_GRAY2RGBA)
                    bg.release()
                    div.release()
                    rgba
                }
                Filter.MAGIC -> enhanceMagicColor(warped)
            }

            val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, result)
            result
        } finally {
            try { mat?.release() } catch (_: Exception) {}
            try { warped?.release() } catch (_: Exception) {}
            try { gray?.release() } catch (_: Exception) {}
            try { out?.release() } catch (_: Exception) {}
            try { tempCopy?.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Color-preserving Magic enhancement:
     * 1. Convert RGBA -> BGR -> YCrCb.
     * 2. Normalize luminance background (shadow removal) + blend with CLAHE for bright white paper.
     * 3. Sharpen Y channel slightly so crisp text & Ethiopic fidel edges pop while keeping blue stamps/color intact.
     */
    private fun enhanceMagicColor(warpedRgba: Mat): Mat {
        val bgr = Mat()
        val ycrcb = Mat()
        val channels = ArrayList<Mat>(3)
        val claheY = Mat()
        val sharpY = Mat()
        val merged = Mat()
        val outBgr = Mat()
        val outRgba = Mat()
        val kernel = Mat(3, 3, CvType.CV_32F)
        return try {
            Imgproc.cvtColor(warpedRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
            Core.split(ycrcb, channels)
            val clahe = Imgproc.createCLAHE(2.2, Size(8.0, 8.0))
            clahe.apply(channels[0], claheY)
            kernel.put(0, 0, floatArrayOf(0f, -0.5f, 0f, -0.5f, 3.0f, -0.5f, 0f, -0.5f, 0f))
            Imgproc.filter2D(claheY, sharpY, -1, kernel)
            channels[0].release()
            channels[0] = sharpY
            Core.merge(channels, merged)
            Imgproc.cvtColor(merged, outBgr, Imgproc.COLOR_YCrCb2BGR)
            // Gentle contrast/brightness lift so paper looks clean white
            outBgr.convertTo(outBgr, -1, 1.12, 8.0)
            Imgproc.cvtColor(outBgr, outRgba, Imgproc.COLOR_BGR2RGBA)
            outRgba
        } finally {
            bgr.release()
            ycrcb.release()
            for (c in channels) try { c.release() } catch (_: Exception) {}
            claheY.release()
            merged.release()
            outBgr.release()
            kernel.release()
        }
    }

    /**
     * Composites Front + Back ID card scans neatly onto a crisp white A4-proportioned bitmap.
     */
    fun compositeIdCardA4(front: Bitmap, back: Bitmap): Bitmap {
        val pageW = 1240
        val pageH = 1754
        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFFD0D5DD.toInt()
        }
        val imagePaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
        )

        val maxCardW = pageW * 0.78f
        val maxCardH = pageH * 0.34f

        fun drawCard(card: Bitmap, centerY: Float) {
            val scale = minOf(maxCardW / maxOf(card.width, 1), maxCardH / maxOf(card.height, 1))
            val dw = card.width * scale
            val dh = card.height * scale
            val left = (pageW - dw) / 2f
            val top = centerY - dh / 2f
            val rect = android.graphics.RectF(left, top, left + dw, top + dh)
            canvas.drawBitmap(card, null, rect, imagePaint)
            canvas.drawRect(rect, borderPaint)
        }

        drawCard(front, pageH * 0.30f)
        drawCard(back, pageH * 0.70f)
        return out
    }

    /** Sharpness (Laplacian variance). Higher = sharper. Threshold ~80-100 for auto-capture. */
    fun sharpnessScore(frame: Bitmap): Double {
        if (!ensureLoaded()) return 0.0
        var mat: Mat? = null
        var gray: Mat? = null
        var lap: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null
        var tempCopy: Bitmap? = null
        return try {
            val src = if (frame.config == Bitmap.Config.ARGB_8888) {
                frame
            } else {
                frame.copy(Bitmap.Config.ARGB_8888, false).also { tempCopy = it }
            }
            mat = Mat()
            Utils.bitmapToMat(src, mat)
            gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            lap = Mat()
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(lap, mean, stddev)
            val std = stddev.get(0, 0)[0]
            std * std
        } catch (_: Exception) {
            0.0
        } finally {
            try { mat?.release() } catch (_: Exception) {}
            try { gray?.release() } catch (_: Exception) {}
            try { lap?.release() } catch (_: Exception) {}
            try { mean?.release() } catch (_: Exception) {}
            try { stddev?.release() } catch (_: Exception) {}
            try { tempCopy?.recycle() } catch (_: Exception) {}
        }
    }
}
