package com.pepe.camaramacro

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Contenedor del visor: coloca a TODOS sus hijos (salvo el propio TextureView) sobre el
 * RECTÁNGULO VISIBLE DE LA IMAGEN, no sobre la pantalla.
 *
 * Por qué existe: el HUD estaba anclado a la pantalla. En la pantalla de cubierta la
 * imagen ocupaba 351x624dp dentro de una caja de 351x758dp, así que la cuadrícula de
 * tercios se pintaba en y=253 y y=505 cuando los tercios reales de la foto estaban en
 * y=208 y y=416: hasta 289 px de error, y el nivel de horizonte 67dp por debajo del
 * centro real. Con relación 1:1 la segunda línea caía directamente fuera de la foto, y
 * la cuenta atrás, la tarjeta QR y el aviso de apilado aparecían ENTEROS sobre la franja
 * negra. El destello de captura hacía lo mismo: parpadeaba también el negro y delataba
 * dónde acaba el encuadre.
 *
 * Cómo lo resuelve: el hijo con id `texture` se mide solo (conserva la proporción de la
 * cámara y puede quedar más pequeño que el contenedor en modo AJUSTAR o más grande en
 * modo LLENAR). El resto de hijos se mide y se coloca sobre la INTERSECCIÓN de ese
 * rectángulo con el del contenedor, que es exactamente lo que el usuario está viendo:
 *
 *   · AJUSTAR (FIT): la imagen cabe entera → la intersección es la imagen.
 *   · LLENAR (COVER): la imagen desborda y se recorta → la intersección es la parte
 *     visible, que es justo lo que hay que encuadrar.
 *
 * Es la alternativa de layout al setPreviewRect() que se propuso para GridOverlayView, y
 * tiene la ventaja de arreglar de una vez la cuadrícula, el anillo, la lupa, el chip de
 * lente, la ranura central y el destello, sin que ninguno tenga que enterarse.
 */
class PreviewFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var preview: android.view.View? = null

    /** Rectángulo visible de la imagen, en coordenadas de este contenedor. */
    val previewRect = Rect()

    override fun onFinishInflate() {
        super.onFinishInflate()
        preview = findViewById(R.id.texture)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val p = preview ?: return
        // Ojo con el orden: hay que dejar que super mida primero, porque el TextureView
        // es quien decide su propio tamaño a partir de la proporción de la cámara.
        val vw = minOf(p.measuredWidth, measuredWidth)
        val vh = minOf(p.measuredHeight, measuredHeight)
        if (vw <= 0 || vh <= 0) return
        val ws = MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === p || c.visibility == GONE) continue
            c.measure(ws, hs)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val p = preview ?: return
        val l = maxOf(0, p.left)
        val t = maxOf(0, p.top)
        val r = minOf(width, p.right)
        val b = minOf(height, p.bottom)
        if (r <= l || b <= t) return
        previewRect.set(l, t, r, b)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === p || c.visibility == GONE) continue
            c.layout(l, t, r, b)
        }
    }
}
