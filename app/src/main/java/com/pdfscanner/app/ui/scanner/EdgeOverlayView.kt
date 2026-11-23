package com.pdfscanner.app.ui.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.pdfscanner.app.R

/**
 * Custom View that draws:
 * - Live document edge quad (green when steady/sharp)
 * - Optional rule-of-thirds alignment grid
 * - Tap-to-focus ring feedback
 */
class EdgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var quad: Array<PointF>? = null
        set(value) {
            field = value
            invalidate()
        }

    var isStable: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var focusPoint: PointF? = null
    private val clearFocusRunnable = Runnable {
        focusPoint = null
        invalidate()
    }

    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.edge_detected)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ContextCompat.getColor(context, R.color.edge_guide)
        alpha = 160
    }

    private val paintFill = Paint().apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.edge_fill)
        alpha = 40
    }

    private val paintGrid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x55FFFFFF
    }

    private val paintFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    fun showFocusRing(x: Float, y: Float) {
        removeCallbacks(clearFocusRunnable)
        focusPoint = PointF(x, y)
        invalidate()
        postDelayed(clearFocusRunnable, 900)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showGrid && width > 0 && height > 0) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(w / 3f, 0f, w / 3f, h, paintGrid)
            canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, paintGrid)
            canvas.drawLine(0f, h / 3f, w, h / 3f, paintGrid)
            canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, paintGrid)
        }

        val q = quad
        if (q != null && q.size == 4) {
            val path = Path().apply {
                moveTo(q[0].x, q[0].y)
                lineTo(q[1].x, q[1].y)
                lineTo(q[2].x, q[2].y)
                lineTo(q[3].x, q[3].y)
                close()
            }
            canvas.drawPath(path, paintFill)
            paintStroke.color = if (isStable) {
                ContextCompat.getColor(context, R.color.edge_ready)
            } else {
                ContextCompat.getColor(context, R.color.edge_detected)
            }
            canvas.drawPath(path, paintStroke)
        } else {
            val marginX = width * 0.1f
            val marginY = height * 0.2f
            canvas.drawRoundRect(
                marginX, marginY,
                width - marginX, height - marginY,
                24f, 24f, paintGuide
            )
        }

        focusPoint?.let { p ->
            canvas.drawCircle(p.x, p.y, 44f, paintFocus)
        }
    }
}
