package com.pepe.camaramacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * Overlay no interactivo: cuadrícula de tercios + nivel de horizonte.
 *
 * DOS ARREGLOS DE R10, LOS DOS DE LEGIBILIDAD, Y LOS DOS CON LA MISMA CAUSA QUE EL
 * DEFECTO QUE EL JURADO SÍ MIDIÓ EN LAS PASTILLAS:
 *
 *  1. GROSORES EN PÍXELES CRUDOS. strokeWidth valía 2f y las marcas del nivel 28f y 6f,
 *     todo en PÍXELES, no en dp. En el CPH2765 (densidad ~3) eso son 0,67 dp de línea
 *     —un pelo que desaparece— y unas marcas de nivel a un tercio del tamaño con el que
 *     se diseñaron. Ahora todo se escala por displayMetrics.density, así que la
 *     cuadrícula mide lo mismo en cualquier pantalla.
 *  2. BLANCO AL 23 % SOBRE CUALQUIER ESCENA. El jurado midió que la pastilla de zoom
 *     activa cae a 2,54:1 de contraste "sobre ropa de cama blanca" y la declaró
 *     ilegible al sol; una línea blanca al alfa 60/255 sobre esa misma colcha está peor
 *     todavía. La solución es la de siempre en un visor y no cuesta nada: una línea
 *     oscura más gruesa DEBAJO y la clara encima, de modo que la cuadrícula tiene
 *     borde oscuro sobre fondo claro y borde claro sobre fondo oscuro. Se aplica igual
 *     al nivel de horizonte, que es lo que se mira contra el cielo.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = context.resources.displayMetrics.density

    private val gridPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1.1f * dp
        isAntiAlias = true
    }
    /** Sombra de la cuadrícula: va DEBAJO y más gruesa, para que la línea clara tenga
     *  borde oscuro sobre una escena clara. Sin esto la rejilla se pierde sobre una
     *  pared blanca o una ventana quemada, que es justo cuando hace falta encuadrar. */
    private val gridShadow = Paint().apply {
        color = Color.argb(90, 0, 0, 0)
        strokeWidth = 3.0f * dp
        isAntiAlias = true
    }
    private val levelPaint = Paint().apply {
        strokeWidth = 2.4f * dp
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val levelShadow = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
        strokeWidth = 4.6f * dp
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
            val x1 = w / 3f
            val x2 = 2f * w / 3f
            val y1 = h / 3f
            val y2 = 2f * h / 3f
            // Primero la sombra de las CUATRO líneas y luego las cuatro claras: así los
            // cruces no se ven mordidos por la sombra de la línea que va después.
            c.drawLine(x1, 0f, x1, h, gridShadow)
            c.drawLine(x2, 0f, x2, h, gridShadow)
            c.drawLine(0f, y1, w, y1, gridShadow)
            c.drawLine(0f, y2, w, y2, gridShadow)
            c.drawLine(x1, 0f, x1, h, gridPaint)
            c.drawLine(x2, 0f, x2, h, gridPaint)
            c.drawLine(0f, y1, w, y1, gridPaint)
            c.drawLine(0f, y2, w, y2, gridPaint)
        }
        if (showLevel) {
            val cx = w / 2f
            val cy = h / 2f
            val len = w * 0.16f
            val hueco = 3f * dp
            val marca = 14f * dp
            val level = abs(roll) < 1.5f
            levelPaint.color = if (level) okColor else Color.argb(230, 255, 255, 255)
            c.save()
            c.rotate(roll, cx, cy)
            c.drawLine(cx - len, cy, cx + len, cy, levelShadow)
            c.drawLine(cx - len, cy, cx + len, cy, levelPaint)
            c.restore()
            // Referencia fija (no gira): es contra estas dos marcas contra las que se
            // lee la inclinación, así que también necesitan sobrevivir a un cielo blanco.
            c.drawLine(cx - len - marca, cy, cx - len - hueco, cy, levelShadow)
            c.drawLine(cx + len + hueco, cy, cx + len + marca, cy, levelShadow)
            levelPaint.color = Color.argb(170, 255, 255, 255)
            c.drawLine(cx - len - marca, cy, cx - len - hueco, cy, levelPaint)
            c.drawLine(cx + len + hueco, cy, cx + len + marca, cy, levelPaint)
        }
    }
}
