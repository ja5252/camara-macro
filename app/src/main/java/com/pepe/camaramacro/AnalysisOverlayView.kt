package com.pepe.camaramacro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Herramientas de exposición y enfoque pintadas SOBRE el visor: histograma de luma,
 * cebras de recorte (altas luces quemadas y negros pegados) y realce de los bordes
 * enfocados.
 *
 * Por qué existe: una app que pelea por calidad de imagen no ofrecía NINGUNA herramienta
 * de exposición. Ni histograma, ni cebras, ni aviso de recorte: el usuario no podía saber
 * si había quemado el cielo hasta que descargaba la foto en el ordenador. Y en macro, sin
 * realce de enfoque, la nitidez se juzga a ojo sobre un visor pequeño, que es la causa
 * directa de las fotos "blandas" que se descubren en casa.
 *
 * Esta vista NO toca la cámara ni reserva memoria dentro de onDraw: CameraActivity le pasa
 * ya calculados el histograma y una máscara diminuta, y aquí solo se dibuja. La máscara se
 * amplía sobre el rectángulo de la vista, que es exactamente el rectángulo VISIBLE de la
 * imagen porque el overlay cuelga del HUD que coloca PreviewFrameLayout: si se midiera
 * contra la pantalla, las cebras se pintarían sobre la franja negra y no sobre los píxeles
 * que de verdad se están quemando.
 *
 * Es utilizable desde XML (constructor con AttributeSet), igual que PreviewFrameLayout.
 */
class AnalysisOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val dst = RectF()
    private val maskSrc = Rect()

    // Sin FILTER_BITMAP a propósito: al ampliar la máscara unas siete veces, interpolar
    // convertiría las rayas de las cebras en una mancha gris.
    private val maskPaint = Paint()
    private val boxPaint = Paint().apply { color = Color.argb(140, 0, 0, 0); isAntiAlias = true }
    private val barPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
    private val clipPaint = Paint().apply { color = Color.parseColor("#FFFF3B30") }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val hist = IntArray(64)
    private var histMax = 1
    private var readout = ""
    private var mask: Bitmap? = null

    var showHistogram = false
        set(v) { field = v; invalidate() }

    /** Máscara de cebras/realce a resolución diminuta (null = ninguna). */
    fun setMask(bmp: Bitmap?) {
        mask = bmp
        if (bmp != null) maskSrc.set(0, 0, bmp.width, bmp.height)
        invalidate()
    }

    fun setHistogram(bins: IntArray, max: Int, linea: String) {
        System.arraycopy(bins, 0, hist, 0, minOf(bins.size, hist.size))
        histMax = if (max > 0) max else 1
        readout = linea
        if (showHistogram) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        val m = mask
        if (m != null && !m.isRecycled) {
            dst.set(0f, 0f, w, h)
            c.drawBitmap(m, maskSrc, dst, maskPaint)
        }
        if (!showHistogram) return
        val d = resources.displayMetrics.density
        val bw = 116f * d
        val bh = 62f * d
        val x = 16f * d
        // 140dp desde arriba: justo por debajo del chip de lente y de la barra de opciones,
        // que es la única franja alta del visor que no tiene nada encima.
        val y = 140f * d
        if (x + bw > w || y + bh + 16f * d > h) return // visor demasiado pequeño: no estorbar
        c.drawRoundRect(x, y, x + bw, y + bh + 16f * d, 8f * d, 8f * d, boxPaint)
        val cw = bw / hist.size
        for (i in hist.indices) {
            val alto = (hist[i].toFloat() / histMax) * bh
            // Primera y última barra en rojo: son los píxeles ya perdidos (negro pegado y
            // blanco quemado), que es lo único irreparable en una foto.
            val p = if (i == 0 || i == hist.size - 1) clipPaint else barPaint
            c.drawRect(x + i * cw, y + bh - alto, x + (i + 1) * cw - 0.5f, y + bh, p)
        }
        if (readout.isNotEmpty()) c.drawText(readout, x + 4f * d, y + bh + 12f * d, textPaint)
    }
}
