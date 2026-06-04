package com.pepe.camaramacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** Overlay no interactivo: cuadrícula de tercios + nivel de horizonte. */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val levelPaint = Paint().apply {
        strokeWidth = 5f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val okColor = Color.parseColor("#FFFF9E00")

    var showGrid = false
        set(v) { field = v; invalidate() }
    var showLevel = false
        set(v) { field = v; invalidate() }

    private var roll = 0f

    fun setRoll(r: Float) {
        roll = r
        if (showLevel) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (showGrid) {
            c.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
            c.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
            c.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
            c.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)
        }
        if (showLevel) {
            val cx = w / 2f
            val cy = h / 2f
            val len = w * 0.16f
            val level = abs(roll) < 1.5f
            levelPaint.color = if (level) okColor else Color.argb(200, 255, 255, 255)
            c.save()
            c.rotate(roll, cx, cy)
            c.drawLine(cx - len, cy, cx + len, cy, levelPaint)
            c.restore()
            levelPaint.color = Color.argb(110, 255, 255, 255)
            c.drawLine(cx - len - 28f, cy, cx - len - 6f, cy, levelPaint)
            c.drawLine(cx + len + 6f, cy, cx + len + 28f, cy, levelPaint)
        }
    }
}
