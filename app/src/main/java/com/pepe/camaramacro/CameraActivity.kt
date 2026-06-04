package com.pepe.camaramacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
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
 * Pantalla principal: viewfinder premium con la lente que funciona.
 * Tap = enfoque, pellizco = zoom, FOTO/VIDEO, y recuerda el último modo y zoom.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var controller: Camera2Controller

    private var mode = "photo"
    private var capturing = false
    private var currentZoom = 1f

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val dimWhite = Color.parseColor("#99FFFFFF")

    private var recStart = 0L
    private val tick = object : Runnable {
        override fun run() {
            val s = ((SystemClock.elapsedRealtime() - recStart) / 1000).toInt()
            binding.recIndicator.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val hideZoom = Runnable {
        binding.zoomPill.animate().alpha(0f).setDuration(200).start()
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
        }

    private val requestAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permiso pre-concedido al entrar a modo video; grabar es una acción aparte.
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
        controller.onReady = {
            val z = prefs.getFloat("zoom", 1f)
            if (z > 1.01f) currentZoom = controller.setZoom(z)
        }
        controller.onRecordingChanged = { rec -> onRecordingChanged(rec) }

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

        binding.btnShutter.setOnClickListener {
            if (mode == "video") toggleRecord() else takePhoto()
        }
        binding.btnChangeLens.setOnClickListener { goToSetup() }
        binding.thumbnail.setOnClickListener { openLastPhoto() }
        binding.tabPhoto.setOnClickListener { setMode("photo") }
        binding.tabVideo.setOnClickListener { setMode("video") }

        setMode(prefs.getString("mode", "photo") ?: "photo")
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
        if (::controller.isInitialized) {
            if (controller.isRecording) controller.stopVideo()
            prefs.edit().putFloat("zoom", currentZoom).putString("mode", mode).apply()
            controller.close()
        }
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

    // ---- Modo ----
    private fun setMode(m: String) {
        if (controller.isRecording) return
        mode = m
        prefs.edit().putString("mode", m).apply()
        val photo = m == "photo"
        val accent = ContextCompat.getColor(this, R.color.accent)
        binding.tabPhoto.setTextColor(if (photo) accent else dimWhite)
        binding.tabVideo.setTextColor(if (photo) dimWhite else accent)
        binding.shutterIcon.visibility = if (photo) View.GONE else View.VISIBLE
        binding.shutterIcon.setBackgroundResource(R.drawable.rec_dot)
        if (!photo &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
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

    // ---- Foto ----
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

    // ---- Video ----
    private fun toggleRecord() {
        if (controller.isRecording) {
            controller.stopVideo()
            return
        }
        val withAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        startRec(withAudio)
    }

    private fun startRec(withAudio: Boolean) {
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (!controller.startVideo(withAudio)) {
            Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRecordingChanged(rec: Boolean) {
        runOnUiThread {
            if (rec) {
                binding.shutterIcon.setBackgroundResource(R.drawable.rec_stop)
                binding.recIndicator.text = "0:00"
                binding.recIndicator.visibility = View.VISIBLE
                recStart = SystemClock.elapsedRealtime()
                ui.post(tick)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.modeToggle.alpha = 0.4f
                binding.tabPhoto.isEnabled = false
                binding.tabVideo.isEnabled = false
            } else {
                binding.shutterIcon.setBackgroundResource(R.drawable.rec_dot)
                binding.recIndicator.visibility = View.GONE
                ui.removeCallbacks(tick)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.modeToggle.alpha = 1f
                binding.tabPhoto.isEnabled = true
                binding.tabVideo.isEnabled = true
                refreshThumbnail()
            }
        }
    }

    // ---- Miniatura / galería ----
    private fun refreshThumbnail() {
        val uri = latestMediaUri()
        if (uri != null) binding.thumbnailImage.load(uri)
    }

    private fun openLastPhoto() {
        val uri = latestMediaUri() ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
        }
    }

    private fun latestMediaUri(): Uri? {
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
