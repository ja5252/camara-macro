package com.pepe.camaramacro

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ImageView con pinch-zoom, doble-tap y arrastre, basado en Matrix.
 * Avisa con [onZoomChanged] cuando hay zoom para desactivar el swipe del ViewPager2.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val m = Matrix()
    private val vals = FloatArray(9)
    private var minScale = 1f
    private val maxScale = 6f
    private var viewW = 0
    private var viewH = 0
    private var lastZoomed = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                var factor = d.scaleFactor
                val target = cur * factor
                if (target < minScale) factor = minScale / cur
                if (target > maxScale) factor = maxScale / cur
                m.postScale(factor, factor, d.focusX, d.focusY)
                fixTranslation()
                imageMatrix = m
                notifyZoom()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale() > minScale * 1.05f) {
                    fitCenter()
                } else {
                    val f = minScale * 2.5f / currentScale()
                    m.postScale(f, f, e.x, e.y)
                    fixTranslation()
                    imageMatrix = m
                }
                notifyZoom()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentScale() > minScale * 1.05f) {
                    m.postTranslate(-dx, -dy)
                    fixTranslation()
                    imageMatrix = m
                    return true
                }
                return false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitCenter() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        viewW = w
        viewH = h
        fitCenter()
    }

    /** Reinicia a "fit center" (toda la imagen visible, sin zoom). */
    fun fitCenter() {
        val d = drawable ?: return
        if (viewW == 0 || viewH == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = min(viewW / dw, viewH / dh)
        minScale = scale
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate((viewW - dw * scale) / 2f, (viewH - dh * scale) / 2f)
        imageMatrix = m
        notifyZoom()
    }

    private fun currentScale(): Float {
        m.getValues(vals)
        return vals[Matrix.MSCALE_X]
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        m.getValues(vals)
        val s = vals[Matrix.MSCALE_X]
        val tx = vals[Matrix.MTRANS_X]
        val ty = vals[Matrix.MTRANS_Y]
        val cw = d.intrinsicWidth * s
        val ch = d.intrinsicHeight * s
        val ntx = if (cw <= viewW) (viewW - cw) / 2f else tx.coerceIn(viewW - cw, 0f)
        val nty = if (ch <= viewH) (viewH - ch) / 2f else ty.coerceIn(viewH - ch, 0f)
        m.postTranslate(ntx - tx, nty - ty)
    }

    private fun notifyZoom() {
        val zoomed = currentScale() > minScale * 1.05f
        if (zoomed != lastZoomed) {
            lastZoomed = zoomed
            onZoomChanged?.invoke(zoomed)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // Mientras hay zoom, evita que el ViewPager2 robe el gesto de arrastre.
        parent?.requestDisallowInterceptTouchEvent(currentScale() > minScale * 1.05f)
        return true
    }
}
