package com.pdfscanner.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Pinch-to-zoom (1x..5x), bounded pan, and double-tap zoom ImageView for PDF pages.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val imgMatrix = Matrix()
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val nextScale = (currentScale * factor).coerceIn(minScale, maxScale)
                val applied = nextScale / currentScale
                if (applied != 1f) {
                    currentScale = nextScale
                    imgMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                    constrainBounds()
                    imageMatrix = imgMatrix
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale > minScale * 1.4f) minScale else minScale * 2.5f
                val applied = target / currentScale
                currentScale = target
                imgMatrix.postScale(applied, applied, e.x, e.y)
                constrainBounds()
                imageMatrix = imgMatrix
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitToView() }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val scale = minOf(vw / dw, vh / dh)
        minScale = scale
        maxScale = scale * 5f
        currentScale = scale
        imgMatrix.reset()
        imgMatrix.postScale(scale, scale)
        imgMatrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
        imageMatrix = imgMatrix
    }

    private fun constrainBounds() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imgMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        }
        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        }
        imgMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && currentScale > minScale * 1.02f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    imgMatrix.postTranslate(dx, dy)
                    constrainBounds()
                    imageMatrix = imgMatrix
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }
}
