package com.pepe.camaramacro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pepe.camaramacro.databinding.ActivityCameraBinding

/**
 * Pantalla principal de uso diario: abre directo la lente guardada y muestra un
 * botón grande para disparar. Si no hay lente guardada, manda a la pantalla de
 * configuración.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var controller: Camera2Controller
    private var capturing = false

    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (prefs.getString("cameraId", null) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = Camera2Controller(this, binding.texture)
        controller.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                binding.btnChangeLens.text = getString(R.string.pick_another_lens)
            }
        }

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnChangeLens.setOnClickListener { goToSetup() }
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        if (::controller.isInitialized) controller.close()
        super.onPause()
    }

    private fun startCamera() {
        val id = prefs.getString("cameraId", null) ?: return goToSetup()
        controller.open(id)
    }

    private fun goToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun takePhoto() {
        if (capturing) return
        capturing = true
        binding.btnShutter.isEnabled = false
        controller.takePhoto { ok ->
            capturing = false
            binding.btnShutter.isEnabled = true
            Toast.makeText(
                this,
                if (ok) R.string.photo_saved else R.string.photo_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
