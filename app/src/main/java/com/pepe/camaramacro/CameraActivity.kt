package com.pepe.camaramacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
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
    private var zoomRestored = false

    private var proOn = false
    private var proParam = "ev"
    private var proIso = 0
    private var proExpNs = 0L
    private var wbIndex = 0
    private val wbModes = intArrayOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE
    )
    private val wbLabels = arrayOf("WB AUTO", "WB Incand.", "WB Fluor.", "WB Sol", "WB Nube", "WB Sombra")

    private var timerSec = 0
    private var gridOn = false
    private var flashMode = 0
    private var facing = "back"
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val rotationListener = object : SensorEventListener {
        private val rot = FloatArray(9)
        private val orient = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, orient)
            binding.gridOverlay.setRoll(Math.toDegrees(orient[2].toDouble()).toFloat())
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

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
            if (!zoomRestored) {
                zoomRestored = true
                val z = prefs.getFloat("zoom", 1f)
                if (z > 1.01f) currentZoom = controller.setZoom(z)
            }
        }
        controller.onRecordingChanged = { rec -> onRecordingChanged(rec) }
        controller.onRawSaved = { ok ->
            if (!ok) runOnUiThread {
                Toast.makeText(this, R.string.raw_save_error, Toast.LENGTH_SHORT).show()
            }
        }
        controller.onRawUnavailable = {
            runOnUiThread {
                Toast.makeText(this, R.string.raw_unavailable, Toast.LENGTH_LONG).show()
                binding.chipRaw.setTextColor(Color.parseColor("#CCFFFFFF"))
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

        binding.btnShutter.setOnClickListener {
            if (mode == "video") toggleRecord() else startPhotoOrTimer()
        }
        binding.btnChangeLens.setOnClickListener { goToSetup() }
        binding.thumbnail.setOnClickListener { openGallery() }
        binding.tabPhoto.setOnClickListener { setMode("photo") }
        binding.tabVideo.setOnClickListener { setMode("video") }

        binding.proToggle.setOnClickListener { togglePro() }
        binding.chipEv.setOnClickListener { selectParam("ev") }
        binding.chipIso.setOnClickListener { selectParam("iso") }
        binding.chipVel.setOnClickListener { selectParam("vel") }
        binding.chipWb.setOnClickListener { cycleWb() }
        binding.chipAuto.setOnClickListener { resetAuto() }
        binding.proSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) applyParam(progress)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        binding.chipGrid.setOnClickListener { toggleGrid() }
        binding.chipTimer.setOnClickListener { cycleTimer() }
        binding.chipFlash.setOnClickListener { cycleFlash() }
        binding.chipFlip.setOnClickListener { flipCamera() }
        binding.chipRaw.setOnClickListener { toggleRaw() }

        setMode(prefs.getString("mode", "photo") ?: "photo")
        restoreSettings()
    }

    /** Reabre con los últimos ajustes (flash, temporizador, cuadrícula). */
    private fun restoreSettings() {
        // Flash: el modo linterna (3) no se restaura para no encender la luz al abrir.
        flashMode = prefs.getInt("flash", 0).let { if (it == 3) 0 else it }
        controller.setFlashMode(flashMode)
        binding.chipFlash.text = arrayOf("⚡ off", "⚡ auto", "⚡ on", "🔦")[flashMode]
        binding.chipFlash.setTextColor(
            if (flashMode == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )

        timerSec = prefs.getInt("timer", 0)
        binding.chipTimer.text = if (timerSec == 0) "⏱ off" else "⏱ ${timerSec}s"
        binding.chipTimer.setTextColor(
            if (timerSec == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )

        gridOn = prefs.getBoolean("grid", false)
        binding.gridOverlay.showGrid = gridOn
        binding.gridOverlay.showLevel = gridOn
        binding.chipGrid.setTextColor(
            if (gridOn) ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")
        )
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        refreshThumbnail()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(rotationListener)
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
        zoomRestored = false
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

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (::controller.isInitialized) {
                if (mode == "video") toggleRecord() else startPhotoOrTimer()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        binding.gridOverlay.showGrid = gridOn
        binding.gridOverlay.showLevel = gridOn
        binding.chipGrid.setTextColor(
            if (gridOn) ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")
        )
        prefs.edit().putBoolean("grid", gridOn).apply()
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        binding.chipTimer.text = if (timerSec == 0) "⏱ off" else "⏱ ${timerSec}s"
        binding.chipTimer.setTextColor(
            if (timerSec == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        prefs.edit().putInt("timer", timerSec).apply()
    }

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 4
        controller.setFlashMode(flashMode)
        binding.chipFlash.text = arrayOf("⚡ off", "⚡ auto", "⚡ on", "🔦")[flashMode]
        binding.chipFlash.setTextColor(
            if (flashMode == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        prefs.edit().putInt("flash", flashMode).apply()
    }

    private fun toggleRaw() {
        if (!controller.hasRaw) {
            Toast.makeText(this, "Esta lente no soporta RAW", Toast.LENGTH_SHORT).show()
            return
        }
        val on = controller.setRawEnabled(!controller.rawEnabled)
        binding.chipRaw.setTextColor(
            if (on) ContextCompat.getColor(this, R.color.accent)
            else Color.parseColor("#CCFFFFFF")
        )
        Toast.makeText(this, if (on) "RAW + JPEG" else "Solo JPEG", Toast.LENGTH_SHORT).show()
    }

    private fun flipCamera() {
        if (controller.isRecording) return
        val target = if (facing == "back") controller.frontLensId() else prefs.getString("cameraId", "3")
        if (target == null) {
            Toast.makeText(this, "No hay cámara frontal disponible", Toast.LENGTH_SHORT).show()
            return
        }
        facing = if (facing == "back") "front" else "back"
        currentZoom = 1f
        zoomRestored = true
        controller.close()
        controller.open(target)
        binding.chipFlip.text = if (facing == "front") "⟲ frontal" else "⟲ atrás"
        binding.chipFlip.setTextColor(
            if (facing == "front") ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")
        )
    }

    private fun startPhotoOrTimer() {
        if (capturing) return
        if (timerSec <= 0) {
            takePhoto()
            return
        }
        var remaining = timerSec
        binding.countdown.visibility = View.VISIBLE
        binding.countdown.text = remaining.toString()
        val r = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    binding.countdown.visibility = View.GONE
                    takePhoto()
                } else {
                    binding.countdown.text = remaining.toString()
                    ui.postDelayed(this, 1000)
                }
            }
        }
        ui.postDelayed(r, 1000)
    }

    // ---- PRO ----
    private fun togglePro() {
        proOn = !proOn
        binding.proPanel.visibility = if (proOn) View.VISIBLE else View.GONE
        binding.proToggle.setTextColor(if (proOn) ContextCompat.getColor(this, R.color.accent) else dimWhite)
        if (proOn) {
            if (proIso == 0) proIso = (controller.isoRange.first + controller.isoRange.second) / 2
            if (proExpNs == 0L) proExpNs = 16_000_000L
            selectParam("ev")
        }
    }

    private fun selectParam(p: String) {
        proParam = p
        when (p) {
            "ev" -> controller.setAutoExposure()
            "iso", "vel" -> controller.setManualExposure(proIso, proExpNs)
        }
        binding.proSlider.progress = when (p) {
            "iso" -> isoToProgress(proIso)
            "vel" -> velToProgress(proExpNs)
            else -> evToProgress(0)
        }
        updateProLabel()
    }

    private fun applyParam(p: Int) {
        when (proParam) {
            "ev" -> {
                val r = controller.evRange
                val steps = r.first + ((r.second - r.first) * p / 100.0).toInt()
                controller.setEv(steps)
                binding.proValue.text = "EV $steps"
            }
            "iso" -> {
                val r = controller.isoRange
                proIso = r.first + ((r.second - r.first) * p / 100.0).toInt()
                controller.setManualExposure(proIso, proExpNs)
                binding.proValue.text = "ISO $proIso"
            }
            "vel" -> {
                proExpNs = progressToExp(p)
                controller.setManualExposure(proIso, proExpNs)
                binding.proValue.text = shutterLabel(proExpNs)
            }
        }
    }

    private fun cycleWb() {
        proParam = "wb"
        wbIndex = (wbIndex + 1) % wbModes.size
        controller.setWhiteBalance(wbModes[wbIndex])
        binding.proValue.text = wbLabels[wbIndex]
    }

    private fun resetAuto() {
        controller.setAutoExposure()
        controller.setEv(0)
        controller.setAutoFocus()
        wbIndex = 0
        controller.setWhiteBalance(wbModes[0])
        proParam = "ev"
        binding.proSlider.progress = evToProgress(0)
        binding.proValue.text = "AUTO"
    }

    private fun updateProLabel() {
        binding.proValue.text = when (proParam) {
            "iso" -> "ISO $proIso"
            "vel" -> shutterLabel(proExpNs)
            else -> "EV 0"
        }
    }

    private fun isoToProgress(iso: Int): Int {
        val r = controller.isoRange
        if (r.second <= r.first) return 0
        return ((iso - r.first) * 100 / (r.second - r.first)).coerceIn(0, 100)
    }

    private fun evToProgress(steps: Int): Int {
        val r = controller.evRange
        if (r.second <= r.first) return 50
        return ((steps - r.first) * 100 / (r.second - r.first)).coerceIn(0, 100)
    }

    private fun velToProgress(ns: Long): Int {
        val r = controller.shutterRangeNs
        val lo = (if (r.first > 0) r.first else 1L).toDouble()
        val hi = (if (r.second > lo) r.second else 100_000_000L).toDouble()
        val v = ns.toDouble().coerceIn(lo, hi)
        return (((Math.log(v) - Math.log(lo)) / (Math.log(hi) - Math.log(lo))) * 100).toInt().coerceIn(0, 100)
    }

    private fun progressToExp(p: Int): Long {
        val r = controller.shutterRangeNs
        val lo = (if (r.first > 0) r.first else 1L).toDouble()
        val hi = (if (r.second > lo) r.second else 100_000_000L).toDouble()
        val v = Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * p / 100.0)
        return v.toLong().coerceIn(lo.toLong(), hi.toLong())
    }

    private fun shutterLabel(ns: Long): String {
        val sec = ns / 1_000_000_000.0
        return if (sec < 1.0) "1/${Math.round(1.0 / sec)}s" else String.format(Locale.US, "%.1fs", sec)
    }
}
