package com.pepe.camaramacro

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * TextureView que conserva la proporción (aspect ratio) de la cámara para que
 * la vista previa no se vea estirada.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    /** true = llena la pantalla recortando (modo Full); false = muestra todo (FIT). */
    var coverMode = false
        set(v) { field = v; requestLayout() }

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else if (coverMode) {
            // COVER: llena la pantalla recortando el sobrante (modo Full).
            if (width > height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        } else {
            // FIT (contiene la proporción completa = lo que ves es lo que sale; sin recortar
            // ni deformar). Deja franjas arriba/abajo donde van los controles.
            if (width > height * ratioWidth / ratioHeight) {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            } else {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            }
        }
    }
}
