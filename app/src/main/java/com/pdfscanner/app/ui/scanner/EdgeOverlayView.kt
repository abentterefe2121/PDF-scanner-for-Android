package com.pdfscanner.app.ui.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.pdfscanner.app.R

/**
 * Custom View that draws the live document edge quad over the camera preview.
 * Native View (not Compose) — zero bridge overhead, 60fps on low-end.
 *
 * States:
 * - No quad: show dashed guide rectangle + "Align document" hint
 * - Quad detected: solid corners, green when stable/sharp (auto-capture ready)
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

    private val paintStroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.edge_detected)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintGuide = Paint().apply {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
            // Guide rectangle (centered, 80% width)
            val marginX = width * 0.1f
            val marginY = height * 0.2f
            canvas.drawRoundRect(
                marginX, marginY,
                width - marginX, height - marginY,
                24f, 24f, paintGuide
            )
        }
    }
}
