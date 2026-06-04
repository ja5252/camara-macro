package com.pepe.camaramacro

import android.media.Image
import kotlin.math.abs

/**
 * Fusión multi-frame para modo noche (Kotlin puro, sin RenderScript ni NDK).
 *
 * Acumula N frames YUV_420_888 capturados con exposición fija, los alinea por
 * desplazamiento global entero (corrige micro-temblor de mano) y los promedia
 * con rechazo de outliers por píxel (sigma-clip ligero contra fantasmas/movimiento).
 * Devuelve NV21 listo para YuvImage.compressToJpeg.
 *
 * Todo el cómputo se hace en el hilo de cámara (background), nunca en UI.
 */
class NightStacker(private val width: Int, private val height: Int) {

    private val accY = IntArray(width * height)
    private val cntY = IntArray(width * height)
    private val cw = width / 2
    private val ch = height / 2
    private val accU = IntArray(cw * ch)
    private val accV = IntArray(cw * ch)
    private val cntC = IntArray(cw * ch)

    private var refY: ByteArray? = null
    private var frames = 0

    /** Procesa un frame. Debe llamarse en el hilo de cámara. La Image la cierra el llamador. */
    fun addFrame(image: Image) {
        if (image.width != width || image.height != height) return
        val y = planeToDense(image, 0, width, height)
        val u = planeToDense(image, 1, cw, ch)
        val v = planeToDense(image, 2, cw, ch)

        val ref = refY
        var dx = 0
        var dy = 0
        if (ref == null) {
            refY = y
        } else {
            val off = estimateShift(ref, y)
            dx = off.first
            dy = off.second
        }

        // Luma con alineación + rechazo de outliers.
        for (j in 0 until height) {
            val sj = (j + dy).coerceIn(0, height - 1)
            val rowOut = j * width
            val rowSrc = sj * width
            for (i in 0 until width) {
                val si = (i + dx).coerceIn(0, width - 1)
                val v8 = y[rowSrc + si].toInt() and 0xFF
                if (ref != null) {
                    val rv = ref[rowOut + i].toInt() and 0xFF
                    if (abs(v8 - rv) > GHOST_THRESH) continue // descarta movimiento/fantasma
                }
                accY[rowOut + i] += v8
                cntY[rowOut + i]++
            }
        }

        // Croma (mitad de resolución; mismo desplazamiento /2).
        val cdx = dx / 2
        val cdy = dy / 2
        for (j in 0 until ch) {
            val sj = (j + cdy).coerceIn(0, ch - 1)
            val rowOut = j * cw
            val rowSrc = sj * cw
            for (i in 0 until cw) {
                val si = (i + cdx).coerceIn(0, cw - 1)
                accU[rowOut + i] += u[rowSrc + si].toInt() and 0xFF
                accV[rowOut + i] += v[rowSrc + si].toInt() and 0xFF
                cntC[rowOut + i]++
            }
        }
        frames++
    }

    /** Devuelve la imagen fusionada en NV21 (VU intercalado), o null si no hubo frames. */
    fun result(): ByteArray? {
        if (frames == 0) return null
        val ref = refY ?: return null
        val out = ByteArray(width * height + 2 * cw * ch)
        // Luma
        for (i in accY.indices) {
            out[i] = (if (cntY[i] > 0) accY[i] / cntY[i] else ref[i].toInt() and 0xFF).toByte()
        }
        // Croma NV21: V luego U, intercalados, a resolución cw x ch
        var o = width * height
        for (j in 0 until ch) {
            for (i in 0 until cw) {
                val idx = j * cw + i
                val c = if (cntC[idx] > 0) cntC[idx] else 1
                out[o++] = (accV[idx] / c).toByte()
                out[o++] = (accU[idx] / c).toByte()
            }
        }
        return out
    }

    fun release() {
        refY = null
    }

    /** Copia un plano YUV a un arreglo denso respetando rowStride/pixelStride reales. */
    private fun planeToDense(image: Image, planeIdx: Int, w: Int, h: Int): ByteArray {
        val plane = image.planes[planeIdx]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val dense = ByteArray(w * h)
        val row = ByteArray(rowStride)
        var pos = 0
        for (j in 0 until h) {
            val remaining = buf.remaining()
            val toRead = if (rowStride <= remaining) rowStride else remaining
            if (toRead <= 0) break
            buf.get(row, 0, toRead)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, dense, pos, minOf(w, toRead))
            } else {
                var k = 0
                var i = 0
                while (i < w && k < toRead) {
                    dense[pos + i] = row[k]
                    k += pixelStride
                    i++
                }
            }
            pos += w
        }
        return dense
    }

    /**
     * Estima el desplazamiento global (dx,dy) que mejor alinea `cur` con `ref`,
     * minimizando SAD de luma sobre una rejilla submuestreada. Rango ±SEARCH px.
     */
    private fun estimateShift(ref: ByteArray, cur: ByteArray): Pair<Int, Int> {
        val step = 8
        val margin = SEARCH + 2
        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE
        var ddy = -SEARCH
        while (ddy <= SEARCH) {
            var ddx = -SEARCH
            while (ddx <= SEARCH) {
                var sad = 0L
                var count = 0
                var j = margin
                while (j < height - margin) {
                    val rowRef = j * width
                    val rowCur = (j + ddy) * width
                    var i = margin
                    while (i < width - margin) {
                        val a = ref[rowRef + i].toInt() and 0xFF
                        val b = cur[rowCur + i + ddx].toInt() and 0xFF
                        sad += abs(a - b).toLong()
                        count++
                        i += step
                    }
                    j += step
                }
                if (count > 0 && sad < bestSad) {
                    bestSad = sad
                    bestDx = ddx
                    bestDy = ddy
                }
                ddx++
            }
            ddy++
        }
        // Devolvemos el desplazamiento a aplicar a `cur` para alinearlo con `ref`.
        return Pair(-bestDx, -bestDy)
    }

    companion object {
        private const val GHOST_THRESH = 30
        private const val SEARCH = 6 // ±6 px de búsqueda de alineación
    }
}
