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

/**
 * OpenCV helper — lazy-loaded ONLY when Scanner opens.
 * Real pipeline: downscale -> gray -> blur -> Canny -> contours -> quad -> warp -> enhance.
 * Ethiopic tuning: larger adaptive block so dense fidel strokes survive.
 * All Mats released immediately (low-end native memory).
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

    /** Live edge detection on analysis frame. Returns quad in frame coords or null. */
    fun detectDocumentQuad(frame: Bitmap): Array<PointF>? {
        if (!ensureLoaded()) return null
        var mat: Mat? = null
        var gray: Mat? = null
        var blurred: Mat? = null
        var edges: Mat? = null
        var best2f: MatOfPoint2f? = null
        var approx: MatOfPoint2f? = null
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
            Imgproc.Canny(blurred, edges, 75.0, 200.0)

            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            var best: MatOfPoint? = null
            var bestArea = 0.0
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area > bestArea) {
                    bestArea = area
                    best = c
                }
            }
            val minArea = frame.width * frame.height * 0.08
            if (best == null || bestArea < minArea) return null

            best2f = MatOfPoint2f(*best.toArray())
            approx = MatOfPoint2f()
            val peri = Imgproc.arcLength(best2f, true)
            Imgproc.approxPolyDP(best2f, approx, 0.02 * peri, true)
            if (approx.rows() != 4) return null

            val pts = approx.toArray()
            val sorted = pts.sortedWith(compareBy<Point>({ it.y }, { it.x }))
            val top = sorted.take(2).sortedBy { it.x }
            val bottom = sorted.takeLast(2).sortedBy { it.x }
            val ordered = arrayOf(top[0], top[1], bottom[1], bottom[0])
            ordered.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            null
        } finally {
            try { mat?.release() } catch (_: Exception) {}
            try { gray?.release() } catch (_: Exception) {}
            try { blurred?.release() } catch (_: Exception) {}
            try { edges?.release() } catch (_: Exception) {}
            try { best2f?.release() } catch (_: Exception) {}
            try { approx?.release() } catch (_: Exception) {}
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
                val srcPts = MatOfPoint2f(
                    Point((quad[0].x * scaleX).toDouble(), (quad[0].y * scaleY).toDouble()),
                    Point((quad[1].x * scaleX).toDouble(), (quad[1].y * scaleY).toDouble()),
                    Point((quad[2].x * scaleX).toDouble(), (quad[2].y * scaleY).toDouble()),
                    Point((quad[3].x * scaleX).toDouble(), (quad[3].y * scaleY).toDouble())
                )
                val w = maxOf(src.width, 1)
                val h = maxOf(src.height, 1)
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

            gray = Mat()
            Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)

            out = when (filter) {
                Filter.ORIGINAL -> warped.clone()
                Filter.BLACK_WHITE -> {
                    val bw = Mat()
                    // Larger block (51) so dense Ethiopic fidel strokes survive cleanly.
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
                    // Shadow removal: divide by blurred background scaled back to 0..255.
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
                Filter.MAGIC -> {
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    val enhanced = Mat()
                    clahe.apply(gray, enhanced)
                    val kernel = Mat(3, 3, CvType.CV_32F)
                    kernel.put(0, 0, floatArrayOf(0f, -1f, 0f, -1f, 5f, -1f, 0f, -1f, 0f))
                    val sharpened = Mat()
                    Imgproc.filter2D(enhanced, sharpened, -1, kernel)
                    kernel.release()
                    val rgba = Mat()
                    Imgproc.cvtColor(sharpened, rgba, Imgproc.COLOR_GRAY2RGBA)
                    enhanced.release()
                    sharpened.release()
                    rgba
                }
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
