package com.pepe.camaramacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import com.pepe.camaramacro.databinding.ActivityCameraBinding
import java.util.Locale

/**
 * Pantalla principal de uso diario: abre directo la lente guardada con un viewfinder
 * premium. Tap = enfoque, pellizco = zoom, obturador con feedback inmediato, y
 * miniatura de la última foto.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var controller: Camera2Controller
    private var capturing = false
    private var currentZoom = 1f

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val hideZoom = Runnable {
        binding.zoomPill.animate().alpha(0f).setDuration(200).start()
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedId = prefs.getString("cameraId", null)
        if (savedId == null || savedId == "0") {
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
                binding.btnChangeLens.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            }
        }

        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentZoom = controller.setZoom(currentZoom * detector.scaleFactor)
                    showZoom()
                    return true
                }
            }
        )

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentZoom = if (currentZoom > 1.05f) controller.setZoom(1f)
                    else controller.setZoom(minOf(2f, controller.maxZoomRatio))
                    showZoom()
                    return true
                }
            }
        )

        binding.gestureArea.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnChangeLens.setOnClickListener { goToSetup() }
        binding.thumbnail.setOnClickListener { openLastPhoto() }
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        refreshThumbnail()
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
        currentZoom = 1f
        controller.open(id)
    }

    private fun goToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    // ---- Enfoque ----
    private fun focusAt(x: Float, y: Float) {
        controller.setFocusPoint(x, y, binding.gestureArea.width, binding.gestureArea.height)
        showFocusRing(x, y)
        binding.gestureArea.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val r = binding.focusRing
        val offset = if (r.width > 0) r.width / 2f else dp(36f)
        r.translationX = x - offset
        r.translationY = y - offset
        r.animate().cancel()
        r.visibility = View.VISIBLE
        r.alpha = 1f
        r.scaleX = 1.3f
        r.scaleY = 1.3f
        r.animate().scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                r.animate().alpha(0f).setStartDelay(900).setDuration(240).start()
            }.start()
    }

    // ---- Zoom ----
    private fun showZoom() {
        binding.zoomPill.text = String.format(Locale.US, "%.1fx", currentZoom)
        binding.zoomPill.animate().cancel()
        binding.zoomPill.alpha = 1f
        ui.removeCallbacks(hideZoom)
        ui.postDelayed(hideZoom, 1200)
    }

    // ---- Captura ----
    private fun takePhoto() {
        if (capturing) return
        capturing = true
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flashScreen()
        binding.btnShutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
            .withEndAction {
                binding.btnShutter.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(OvershootInterpolator()).start()
            }.start()
        controller.takePhoto { ok ->
            capturing = false
            if (ok) {
                refreshThumbnail()
                bounceThumbnail()
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun flashScreen() {
        val f = binding.screenFlash
        f.animate().cancel()
        f.alpha = 0f
        f.animate().alpha(0.7f).setDuration(50).withEndAction {
            f.animate().alpha(0f).setDuration(140).start()
        }.start()
    }

    private fun bounceThumbnail() {
        val t = binding.thumbnail
        t.scaleX = 0.85f
        t.scaleY = 0.85f
        t.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator()).start()
    }

    // ---- Miniatura / galería ----
    private fun refreshThumbnail() {
        val uri = latestPhotoUri()
        if (uri != null) binding.thumbnailImage.load(uri)
    }

    private fun openLastPhoto() {
        val uri = latestPhotoUri() ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
        }
    }

    private fun latestPhotoUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Pictures/CamaraMacro%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
