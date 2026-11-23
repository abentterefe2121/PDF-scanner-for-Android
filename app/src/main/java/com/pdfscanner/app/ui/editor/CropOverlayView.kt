package com.pdfscanner.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.pdfscanner.app.R
import kotlin.math.hypot

/**
 * Interactive 4-corner document cropper overlay with a circular 2x Magnifier Loupe
 * above the dragged corner so fingers never hide exact paper edges.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val corners = arrayOf(
        PointF(0.08f, 0.08f),
        PointF(0.92f, 0.08f),
        PointF(0.92f, 0.92f),
        PointF(0.08f, 0.92f)
    )

    private var imageRect = RectF(0f, 0f, 1f, 1f)
    private var sourceBitmap: Bitmap? = null
    private var activeIndex = -1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.edge_ready)
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.edge_ready)
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }

    private val scrimPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x77000000
    }

    private val loupeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }

    private val loupeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.edge_ready)
    }

    private val loupeImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setSourceBitmap(bmp: Bitmap?) {
        sourceBitmap = bmp
        if (bmp != null) setImageBounds(bmp.width, bmp.height)
    }

    fun setImageBounds(bitmapW: Int, bitmapH: Int) {
        if (width == 0 || height == 0 || bitmapW <= 0 || bitmapH <= 0) return
        val scale = minOf(width.toFloat() / bitmapW, height.toFloat() / bitmapH)
        val drawW = bitmapW * scale
        val drawH = bitmapH * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        imageRect.set(left, top, left + drawW, top + drawH)
        invalidate()
    }

    fun setNormalizedCorners(pts: Array<PointF>?) {
        if (pts != null && pts.size == 4) {
            for (i in 0 until 4) {
                corners[i].set(pts[i].x.coerceIn(0f, 1f), pts[i].y.coerceIn(0f, 1f))
            }
        } else {
            resetCorners()
        }
        invalidate()
    }

    fun resetCorners() {
        corners[0].set(0.05f, 0.05f)
        corners[1].set(0.95f, 0.05f)
        corners[2].set(0.95f, 0.95f)
        corners[3].set(0.05f, 0.95f)
        invalidate()
    }

    fun getNormalizedCorners(): Array<PointF> =
        corners.map { PointF(it.x, it.y) }.toTypedArray()

    private fun toView(norm: PointF): PointF = PointF(
        imageRect.left + norm.x * imageRect.width(),
        imageRect.top + norm.y * imageRect.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = corners.map { toView(it) }

        val outerPath = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(outerPath, scrimPaint)

        val quadPath = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        canvas.drawPath(quadPath, linePaint)

        for ((i, p) in pts.withIndex()) {
            val r = if (i == activeIndex) 28f else 22f
            canvas.drawCircle(p.x, p.y, r, handleFillPaint)
            canvas.drawCircle(p.x, p.y, r, handleStrokePaint)
        }

        // Draw 2x Magnifier Loupe when dragging a corner
        val bmp = sourceBitmap
        if (activeIndex in 0..3 && bmp != null && !bmp.isRecycled) {
            drawLoupe(canvas, bmp, corners[activeIndex], pts[activeIndex])
        }
    }

    private fun drawLoupe(canvas: Canvas, bmp: Bitmap, norm: PointF, viewPt: PointF) {
        val radius = 110f
        val cx = viewPt.x.coerceIn(radius + 16f, width - radius - 16f)
        val cy = if (viewPt.y > radius * 2 + 60f) {
            viewPt.y - radius - 75f
        } else {
            viewPt.y + radius + 75f
        }

        val srcX = (norm.x * bmp.width).toInt()
        val srcY = (norm.y * bmp.height).toInt()
        val halfSample = (minOf(bmp.width, bmp.height) * 0.08f).toInt().coerceAtLeast(32)
        val srcRect = Rect(
            (srcX - halfSample).coerceAtLeast(0),
            (srcY - halfSample).coerceAtLeast(0),
            (srcX + halfSample).coerceAtMost(bmp.width),
            (srcY + halfSample).coerceAtMost(bmp.height)
        )
        val dstRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, srcRect, dstRect, loupeImagePaint)
        canvas.drawLine(cx - 24f, cy, cx + 24f, cy, loupeCrossPaint)
        canvas.drawLine(cx, cy - 24f, cx, cy + 24f, loupeCrossPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, loupeBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.width() <= 1f || imageRect.height() <= 1f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var bestIdx = -1
                var bestDist = 115f
                for (i in 0 until 4) {
                    val p = toView(corners[i])
                    val d = hypot(event.x - p.x, event.y - p.y)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                activeIndex = bestIdx
                invalidate()
                return activeIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeIndex in 0..3) {
                    val nx = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0.01f, 0.99f)
                    val ny = ((event.y - imageRect.top) / imageRect.height()).coerceIn(0.01f, 0.99f)
                    corners[activeIndex].set(nx, ny)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
