package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Image view with pinch-to-zoom, panning, double-tap zoom and configurable fit modes.
 * Uses [ScaleType.MATRIX] and manipulates the image matrix directly.
 *
 * [onTap] receives the tap position as a fraction of the view width so callers can
 * implement left/centre/right tap zones. [onVerticalDrag] receives the vertical
 * delta of a drag while the image is not zoomed (used for the brightness gesture).
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    enum class FitMode { SCREEN, WIDTH, HEIGHT, STRETCH }

    private val matrix = Matrix()
    private val baseMatrix = Matrix()
    private var fitMode = FitMode.SCREEN
    private var minScale = 1f
    private var currentScale = 1f
    private var maxScale = 8f
    private var lastX = 0f
    private var lastY = 0f

    var onTap: ((Float) -> Unit)? = null
    var onVerticalDrag: ((Float) -> Unit)? = null
    var doubleTapEnabled = true

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (drawable == null) return false
                val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / currentScale
                currentScale = newScale
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                val fraction = if (width > 0) e.x / width.toFloat() else 0.5f
                onTap?.invoke(fraction)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!doubleTapEnabled) return false
                if (currentScale > minScale * 1.1f) {
                    fit(fitMode)
                } else {
                    zoomTo(minScale * 3f, e.x, e.y)
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fit(fitMode) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit(fitMode)
    }

    fun setFitMode(mode: FitMode) {
        fitMode = mode
        fit(mode)
    }

    fun currentFitMode(): FitMode = fitMode

    fun fit(mode: FitMode = fitMode) {
        val drawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val drawableW = drawable.intrinsicWidth.toFloat()
        val drawableH = drawable.intrinsicHeight.toFloat()
        if (drawableW <= 0f || drawableH <= 0f) return

        if (mode == FitMode.STRETCH) {
            baseMatrix.reset()
            baseMatrix.postScale(viewW / drawableW, viewH / drawableH)
            matrix.set(baseMatrix)
            currentScale = 1f
            minScale = 1f
            maxScale = 1f
            imageMatrix = matrix
            return
        }

        val scale = when (mode) {
            FitMode.WIDTH -> viewW / drawableW
            FitMode.HEIGHT -> viewH / drawableH
            else -> minOf(viewW / drawableW, viewH / drawableH)
        }
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(
            (viewW - drawableW * scale) / 2f,
            (viewH - drawableH * scale) / 2f
        )
        matrix.set(baseMatrix)
        currentScale = scale
        minScale = scale
        maxScale = scale * 8f
        imageMatrix = matrix
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val clamped = target.coerceIn(minScale, maxScale)
        val factor = clamped / currentScale
        currentScale = clamped
        matrix.postScale(factor, factor, focusX, focusY)
        clamp()
        imageMatrix = matrix
    }

    private fun clamp() {
        val values = FloatArray(9)
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val drawable = drawable ?: return
        val drawableW = drawable.intrinsicWidth * scaleX
        val drawableH = drawable.intrinsicHeight * scaleY
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var dx = 0f
        if (drawableW <= viewW) {
            dx = (viewW - drawableW) / 2f - transX
        } else {
            val minX = viewW - drawableW
            if (transX > 0f) dx = -transX else if (transX < minX) dx = minX - transX
        }

        var dy = 0f
        if (drawableH <= viewH) {
            dy = (viewH - drawableH) / 2f - transY
        } else {
            val minY = viewH - drawableH
            if (transY > 0f) dy = -transY else if (transY < minY) dy = minY - transY
        }
        matrix.postTranslate(dx, dy)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val zoomed = currentScale > minScale * 1.01f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(zoomed)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    if (zoomed) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        matrix.postTranslate(dx, dy)
                        clamp()
                        imageMatrix = matrix
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        onVerticalDrag?.invoke(event.y - lastY)
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
