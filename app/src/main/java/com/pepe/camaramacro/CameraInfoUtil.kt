package com.pepe.camaramacro

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Información resumida de una lente para mostrarla al usuario. */
data class LensInfo(
    val cameraId: String,
    val facingBack: Boolean,
    val focalLengthMm: Float,
    val hasFlash: Boolean,
    val label: String
)

object CameraInfoUtil {

    /** Devuelve todas las lentes que el sistema expone a las apps. */
    fun listLenses(context: Context): List<LensInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            emptyArray()
        }

        data class Raw(val id: String, val back: Boolean, val focal: Float, val flash: Boolean)

        val raws = mutableListOf<Raw>()
        for (id in ids) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                raws.add(Raw(id, back, focal, flash))
            } catch (e: Exception) {
                // Si una lente no se puede consultar, la saltamos.
            }
        }

        val backFocals = raws.filter { it.back && it.focal > 0f }.map { it.focal }
        val minFocal = backFocals.minOrNull()
        val maxFocal = backFocals.maxOrNull()

        return raws.map { r ->
            val type = when {
                !r.back -> "Frontal (selfie)"
                r.id == "0" -> "Principal (la que suele fallarte)"
                r.focal > 0f && minFocal != null && r.focal == minFocal && minFocal != maxFocal ->
                    "Gran angular / macro (probable)"
                r.focal > 0f && maxFocal != null && r.focal == maxFocal && minFocal != maxFocal ->
                    "Teleobjetivo / zoom (probable)"
                else -> "Trasera (normal)"
            }
            val focalText = if (r.focal > 0f) "  ·  ${"%.1f".format(r.focal)} mm" else ""
            LensInfo(r.id, r.back, r.focal, r.flash, "$type$focalText  ·  ID ${r.id}")
        }
    }
}
