package com.pepe.camaramacro

import android.graphics.ColorMatrix

/** Un look de color: nombre + matriz (null = sin filtro). */
data class CamFilter(val name: String, val matrix: ColorMatrix?)

/** Catálogo de filtros tipo "looks" (se aplican a la vista previa y a la foto). */
object Filters {

    val list: List<CamFilter> = build()

    private fun build(): List<CamFilter> {
        val vivid = ColorMatrix().apply { setSaturation(1.55f) }
        val bw = ColorMatrix().apply { setSaturation(0f) }
        val sepia = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 38f,
                        0f, 1f, 0f, 0f, 18f,
                        0f, 0f, 1f, 0f, -22f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val warm = ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 12f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 0.88f, 0f, -12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val cool = ColorMatrix(
            floatArrayOf(
                0.9f, 0f, 0f, 0f, -8f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.15f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val drama = ColorMatrix(
            floatArrayOf(
                1.35f, 0f, 0f, 0f, -32f,
                0f, 1.35f, 0f, 0f, -32f,
                0f, 0f, 1.35f, 0f, -32f,
                0f, 0f, 0f, 1f, 0f
            )
        ).apply { postConcat(ColorMatrix().apply { setSaturation(1.2f) }) }

        return listOf(
            CamFilter("Normal", null),
            CamFilter("Vívido", vivid),
            CamFilter("B&N", bw),
            CamFilter("Sepia", sepia),
            CamFilter("Cálido", warm),
            CamFilter("Frío", cool),
            CamFilter("Drama", drama)
        )
    }
}
