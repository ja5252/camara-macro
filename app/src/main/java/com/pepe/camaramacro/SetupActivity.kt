package com.pepe.camaramacro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pepe.camaramacro.databinding.ActivitySetupBinding

/**
 * Pantalla de configuración: deja recorrer todas las lentes con vista previa en
 * vivo para que el usuario elija la que funciona (su "modo macro").
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var controller: Camera2Controller
    private var lenses: List<LensInfo> = emptyList()
    private var index = 0

    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureInit()
            else Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = Camera2Controller(this, binding.texture)
        controller.onError = { msg ->
            runOnUiThread { binding.lblLens.text = "⚠ $msg" }
        }

        binding.btnPrev.setOnClickListener { switch(-1) }
        binding.btnNext.setOnClickListener { switch(1) }
        binding.btnUse.setOnClickListener { useCurrent() }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ensureInit()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        controller.close()
        super.onPause()
    }

    private fun ensureInit() {
        if (lenses.isEmpty()) init() else openCurrent()
    }

    private fun init() {
        val all = CameraInfoUtil.listLenses(this)
        // Excluimos la lente principal (ID 0): es la dañada y abrirla cuelga la cámara.
        lenses = all.filter { it.cameraId != "0" }.ifEmpty { all }
        if (lenses.isEmpty()) {
            binding.lblLens.text = getString(R.string.no_cameras)
            return
        }
        // Empezamos en la primera lente trasera (suele ser gran angular / macro).
        index = lenses.indexOfFirst { it.facingBack }.let { if (it >= 0) it else 0 }
        openCurrent()
    }

    private fun openCurrent() {
        controller.close()
        val lens = lenses[index]
        binding.lblLens.text = "Lente ${index + 1} de ${lenses.size}\n${lens.label}"
        controller.open(lens.cameraId)
    }

    private fun switch(dir: Int) {
        if (lenses.isEmpty()) return
        index = (index + dir + lenses.size) % lenses.size
        openCurrent()
    }

    private fun useCurrent() {
        if (lenses.isEmpty()) return
        prefs.edit().putString("cameraId", lenses[index].cameraId).apply()
        Toast.makeText(this, R.string.lens_saved, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, CameraActivity::class.java))
        finish()
    }
}
