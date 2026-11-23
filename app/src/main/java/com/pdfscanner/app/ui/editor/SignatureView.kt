package com.pdfscanner.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Finger signature canvas for signing scanned documents cleanly.
 */
class SignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePath = Path()
    private val strokes = mutableListOf<Pair<Path, Int>>()
    private var currentColor = 0xFF111827.toInt()
    private var hasInk = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setInkColor(color: Int) {
        currentColor = color
    }

    fun clear() {
        strokes.clear()
        strokePath.reset()
        hasInk = false
        invalidate()
    }

    fun isEmpty(): Boolean = !hasInk

    fun exportBitmap(): Bitmap? {
        if (!hasInk || width <= 0 || height <= 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        for ((p, col) in strokes) {
            paint.color = col
            c.drawPath(p, paint)
        }
        paint.color = currentColor
        c.drawPath(strokePath, paint)
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((p, col) in strokes) {
            paint.color = col
            canvas.drawPath(p, paint)
        }
        paint.color = currentColor
        canvas.drawPath(strokePath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokePath.moveTo(event.x, event.y)
                hasInk = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                strokePath.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                strokes.add(Path(strokePath) to currentColor)
                strokePath.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
