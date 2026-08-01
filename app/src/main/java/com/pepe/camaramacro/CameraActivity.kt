package com.pepe.camaramacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
    private var burstRemaining = 0
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
    private var wbKelvin = 5000

    private var timerSec = 0
    private var gridOn = false
    private var flashMode = 0
    private var facing = "back"
    private var camCycleIndex = 0
    private var aeAfLocked = false
    private var evSteps = 0
    // Captura solicitada por otra app (ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE)
    private var captureIntent = false
    private var captureVideo = false
    private var captureOutput: Uri? = null
    private val ratioLabels = arrayOf("RATIO", "4:3", "16:9", "1:1", "LLENA")
    private var ratioIndex = 0
    private var fullRes = true
    private var disabledLenses = HashSet<String>()
    private var nightOn = false
    private var qrValue: String? = null
    private var qrDismissed: String? = null
    private var countdownRunnable: Runnable? = null
    private var filterIndex = 0
    private val vresList = intArrayOf(1080, 2160, 720)
    private val vresLabels = arrayOf("1080p", "4K", "720p")
    private var vresIndex = 0
    private var vfps = 30
    private var vhevc = false
    private var tlOn = false
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

        // ¿Nos invoca OTRA app para capturar? (banca, archivos, formularios...)
        val act = intent?.action
        captureVideo = act == MediaStore.ACTION_VIDEO_CAPTURE
        captureIntent = captureVideo || act == MediaStore.ACTION_IMAGE_CAPTURE
        @Suppress("DEPRECATION")
        captureOutput = intent?.getParcelableExtra(MediaStore.EXTRA_OUTPUT) as? Uri
        if (captureIntent) setResult(RESULT_CANCELED) // contrato por defecto si el usuario sale

        var savedId = prefs.getString("cameraId", null)
        if (savedId == null || savedId == "0") {
            if (captureIntent) {
                // Con un intent en curso NO podemos irnos al asistente: perderíamos al
                // llamador. Elegimos la primera lente trasera que sirve (nunca la ID0 dañada).
                savedId = CameraInfoUtil.listLenses(this)
                    .firstOrNull { it.facingBack && it.cameraId != "0" }?.cameraId
                if (savedId == null) { finish(); return }
                prefs.edit().putString("cameraId", savedId).apply()
            } else {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
                return
            }
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
            runOnUiThread {
                updateLensChip()
                buildZoomStrip()
                // Da un par de fotogramas a la lente nueva antes de quitar el congelado.
                ui.postDelayed({ releaseLensFade() }, 120)
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
        controller.onQrDetected = { value -> runOnUiThread { showQrResult(value) } }
        controller.onFocusState = { st ->
            runOnUiThread {
                binding.focusRing.setColorFilter(
                    when (st) {
                        FocusState.FOCUSED -> Color.parseColor("#4CD964")   // verde: enfocado
                        FocusState.NOT_FOCUSED -> Color.parseColor("#FF3B30") // rojo: no pudo
                        else -> ContextCompat.getColor(this, R.color.accent)  // ambar: buscando
                    }
                )
            }
        }
        controller.onHdrUnavailable = {
            runOnUiThread {
                Toast.makeText(this, "Ultra HDR no disponible en esta lente", Toast.LENGTH_LONG).show()
                binding.chipHdr.setTextColor(chipColor(false))
            }
        }
        // La miniatura se pinta al instante desde el JPEG en memoria (antes esperaba a
        // que MediaStore indexara el archivo y el retraso se notaba mucho).
        controller.onPhotoThumb = { bmp ->
            binding.thumbnailImage.setImageBitmap(bmp)
            bounceThumbnail()
        }
        if (captureIntent) armIntentCapture()
        controller.onLensSwitching = { freezeForLensSwitch() }

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

                override fun onLongPress(e: MotionEvent) {
                    toggleAeAfLock()
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
        binding.btnShutter.setOnLongClickListener { startBurst(); true }
        binding.btnChangeLens.setOnClickListener { goToSetup() }
        binding.thumbnail.setOnClickListener { openGallery() }
        // El hueco junto al obturador es el sitio canónico del cambio de cámara, no de una
        // marca de terceros (el botón verde de WhatsApp rompía la paleta y confundía).
        binding.btnFlipMain.setOnClickListener { flipCamera() }
        binding.chipWa.setOnClickListener { shootAndShareWhatsApp() }
        binding.tabPhoto.setOnClickListener { setMode("photo") }
        binding.tabVideo.setOnClickListener { setMode("video") }

        binding.proToggle.setOnClickListener { togglePro() }
        binding.chipEv.setOnClickListener { selectParam("ev") }
        binding.chipIso.setOnClickListener { selectParam("iso") }
        binding.chipVel.setOnClickListener { selectParam("vel") }
        binding.chipWb.setOnClickListener { cycleWb() }
        binding.chipK.setOnClickListener { selectKelvin() }
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
        binding.chipNight.setOnClickListener { toggleNight() }
        binding.btnQrOpen.setOnClickListener { openQr() }
        binding.btnQrCopy.setOnClickListener { copyQr() }
        binding.btnQrClose.setOnClickListener {
            qrDismissed = qrValue
            binding.qrCard.visibility = View.GONE
        }
        binding.chipRatio.setOnClickListener { cycleRatio() }
        binding.chipRes.setOnClickListener { toggleRes() }
        binding.chipLenses.setOnClickListener { toggleLensPanel() }
        binding.chipFilter.setOnClickListener { cycleFilter() }
        binding.chipMore.setOnClickListener { toggleMorePanel() }
        binding.chipHdr.setOnClickListener { toggleHdr() }
        binding.evSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val r = controller.evRange
                evSteps = r.first + ((r.second - r.first) * progress / 100.0).toInt()
                controller.setEv(evSteps)
                binding.evLabel.text = evLabel(evSteps)
                ui.removeCallbacks(hideEvQuick)
                ui.postDelayed(hideEvQuick, 4000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { ui.removeCallbacks(hideEvQuick) }
            override fun onStopTrackingTouch(s: SeekBar) { ui.postDelayed(hideEvQuick, 4000) }
        })
        binding.chipVid.setOnClickListener { toggleVideoPanel() }
        binding.chipVres.setOnClickListener { cycleVres() }
        binding.chipVfps.setOnClickListener { toggleVfps() }
        binding.chipVcodec.setOnClickListener { toggleVcodec() }
        binding.chipTl.setOnClickListener { toggleTl() }

        // Si nos invoca otra app, el modo lo fija armIntentCapture: no pisarlo.
        if (!captureIntent) setMode(prefs.getString("mode", "photo") ?: "photo")
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

        // Por defecto 16:9 (índice 2): en una pantalla tan alargada, el 4:3 dejaba una
        // franja negra del 42% de la pantalla. Con 16:9 baja al ~23% y el visor se ve
        // mucho más grande. El 4:3 (máxima resolución) sigue a un toque en el chip RATIO.
        ratioIndex = prefs.getInt("capRatio", 2).coerceIn(0, ratioLabels.size - 1)
        binding.chipRatio.text = ratioLabels[ratioIndex]
        binding.chipRatio.setTextColor(
            if (ratioIndex == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        fullRes = prefs.getBoolean("capFull", true)
        binding.chipRes.text = if (fullRes) "FULL" else "MED"
        binding.chipRes.setTextColor(
            if (fullRes) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )

        disabledLenses = HashSet(prefs.getStringSet("disabledLenses", emptySet()) ?: emptySet())

        if (prefs.getBoolean("hdr", false)) {
            val on = controller.setHdrEnabled(true)
            binding.chipHdr.setTextColor(chipColor(on))
        }
        filterIndex = prefs.getInt("filter", 0).coerceIn(0, Filters.list.size - 1)
        applyFilter()

        vresIndex = prefs.getInt("vres", 0).coerceIn(0, vresList.size - 1)
        vfps = prefs.getInt("vfps", 30)
        vhevc = prefs.getBoolean("vhevc", false)
        tlOn = prefs.getBoolean("tl", false)
        applyVideoSettings()
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        refreshThumbnail()
        ui.removeCallbacks(autoScanTick)
        ui.postDelayed(autoScanTick, 1200)
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
        ui.removeCallbacks(autoScanTick)
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
        camCycleIndex = 0
        facing = "back"
        // Aplicar ajustes guardados ANTES de abrir (sin reconstruir): el primer setUpOutputs ya los usa.
        controller.presetCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
        controller.setDisabledLensIds(disabledLenses)
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
        // El chip de ajustes de video solo aparece en modo video.
        binding.chipVid.visibility = if (photo) View.GONE else View.VISIBLE
        if (photo) {
            binding.videoPanel.visibility = View.GONE
            binding.chipVid.setTextColor(chipColor(false))
        }
        if (!photo &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---- Enfoque ----
    private fun focusAt(x: Float, y: Float) {
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        // El preview NO ocupa toda la pantalla (AutoFitTextureView con wrap_content,
        // alineado arriba). Hay que mapear el toque en coordenadas DEL TEXTURE, no del
        // área de gestos (pantalla completa): si no, el punto de enfoque cae desplazado
        // respecto al dedo (~20% del encuadre en 4:3). En modo LLENA t.left/t.top son
        // negativos y la resta también lo corrige.
        val lx = x - t.left
        val ly = y - t.top
        if (lx < 0f || ly < 0f || lx > t.width || ly > t.height) return // toque fuera del encuadre
        controller.setFocusPoint(lx, ly, t.width, t.height)
        showFocusRing(x, y)   // el anillo se dibuja en coordenadas de pantalla
        showMagnifier(lx, ly) // la lupa recorta sobre el texture
        showEvQuick()         // exposición al alcance, sin entrar a PRO
        binding.gestureArea.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private val hideMagnifier = Runnable { binding.magnifierCard.visibility = View.GONE }

    /** Lupa: muestra una zona ampliada del punto enfocado para confirmar nitidez. */
    private fun showMagnifier(x: Float, y: Float) {
        val tw = binding.texture.width
        val th = binding.texture.height
        if (tw == 0 || th == 0) return
        try {
            val bmp = binding.texture.getBitmap(tw, th) ?: return
            val crop = (tw * 0.12f).toInt().coerceAtLeast(40)
            // El texture está alineado arriba y ocupa el ancho: el mapeo es directo.
            val cx = x.toInt().coerceIn(crop / 2, (tw - crop / 2).coerceAtLeast(crop / 2))
            val cy = y.toInt().coerceIn(crop / 2, (th - crop / 2).coerceAtLeast(crop / 2))
            val left = (cx - crop / 2).coerceIn(0, (tw - crop).coerceAtLeast(0))
            val top = (cy - crop / 2).coerceIn(0, (th - crop).coerceAtLeast(0))
            val w = crop.coerceAtMost(tw - left)
            val h = crop.coerceAtMost(th - top)
            if (w <= 0 || h <= 0) { bmp.recycle(); return }
            val region = android.graphics.Bitmap.createBitmap(bmp, left, top, w, h)
            binding.magnifier.setImageBitmap(region)
            binding.magnifierCard.visibility = View.VISIBLE
            ui.removeCallbacks(hideMagnifier)
            ui.postDelayed(hideMagnifier, 1800)
            bmp.recycle()
        } catch (e: Exception) {
        }
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
    // ---- Exposición rápida y bloqueo AE/AF (sin entrar a PRO) ----

    private val hideEvQuick = Runnable { binding.evQuick.visibility = View.GONE }

    /** Muestra el ajuste de exposición junto al enfoque: el caso real más común
     *  (contraluces, comida oscura) sin obligar a entrar al modo PRO. */
    private fun showEvQuick() {
        val r = controller.evRange
        if (r.second <= r.first) return // la lente no permite compensación
        binding.evSlider.progress = evToProgress(evSteps)
        binding.evLabel.text = evLabel(evSteps)
        binding.evQuick.visibility = View.VISIBLE
        ui.removeCallbacks(hideEvQuick)
        ui.postDelayed(hideEvQuick, 4000)
    }

    private fun evLabel(steps: Int): String {
        val stepEv = controller.evStepValue
        val v = if (stepEv > 0f) steps * stepEv else steps / 2f
        return String.format(Locale.US, "%+.1f EV", v)
    }

    private fun toggleAeAfLock() {
        aeAfLocked = !aeAfLocked
        controller.lockAeAf(aeAfLocked)
        binding.aeLockBadge.visibility = if (aeAfLocked) View.VISIBLE else View.GONE
        binding.gestureArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ---- Captura solicitada por otra app ----

    /**
     * Prepara la app para responder a ACTION_IMAGE_CAPTURE de otra aplicación.
     * Contrato de Android: con EXTRA_OUTPUT se escribe ahí y se devuelve RESULT_OK;
     * sin él, se devuelve una MINIATURA en el extra "data" (el Binder no admite la
     * foto completa). Si esto se hace mal, la app que llama se queda colgada.
     */
    private fun armIntentCapture() {
        // En modo intent no tiene sentido RAW, ni la galería, ni compartir.
        controller.setRawEnabled(false)
        binding.thumbnail.visibility = View.GONE
        binding.modeToggle.visibility = View.GONE
        binding.chipWa.visibility = View.GONE
        if (captureVideo) setMode("video") else setMode("photo")

        controller.jpegSink = sink@{ bytes ->
            val out = captureOutput
            if (out != null) {
                val ok = try {
                    contentResolver.openOutputStream(out)?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    false
                }
                if (ok) runOnUiThread {
                    setResult(
                        RESULT_OK,
                        Intent().setData(out).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    finish()
                }
                return@sink ok
            }
            // Sin EXTRA_OUTPUT: miniatura en "data". Máx ~400 px o revienta el Binder.
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@sink false
            val s = 400f / maxOf(full.width, full.height)
            val thumb = if (s < 1f) Bitmap.createScaledBitmap(
                full, (full.width * s).toInt(), (full.height * s).toInt(), true
            ) else full
            if (thumb !== full) full.recycle()
            runOnUiThread {
                setResult(RESULT_OK, Intent("inline-data").putExtra("data", thumb))
                finish()
            }
            true
        }
    }

    // ---- Fundido al cambiar de lente física ----

    /** Congela el último fotograma para que el cambio de lente no muestre un negro. */
    private fun freezeForLensSwitch() {
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        try {
            val bmp = t.getBitmap(t.width, t.height) ?: return
            binding.lensFade.layoutParams = binding.lensFade.layoutParams.apply {
                width = t.width
                height = t.height
            }
            binding.lensFade.setImageBitmap(bmp)
            binding.lensFade.alpha = 1f
            binding.lensFade.visibility = View.VISIBLE
        } catch (e: Exception) {
        }
    }

    /** Desvanece el fotograma congelado cuando la lente nueva ya está dando imagen. */
    private fun releaseLensFade() {
        val v = binding.lensFade
        if (v.visibility != View.VISIBLE) return
        v.animate().alpha(0f).setDuration(140)
            .withEndAction {
                v.visibility = View.GONE
                v.setImageDrawable(null)
            }.start()
    }

    // ---- Tira de zoom (una píldora por lente física real) ----

    /** Construye la tira una vez que la cámara reportó su cadena de lentes. */
    private fun buildZoomStrip() {
        val stops = controller.zoomStops()
        binding.zoomStrip.removeAllViews()
        if (stops.size < 2) return // con una sola lente no aporta nada
        val pad = dp(11f).toInt()
        stops.forEach { (z, label, optical) ->
            val tv = TextView(this).apply {
                text = label
                textSize = if (optical) 14f else 12.5f
                // Las paradas ópticas (lente física real) van en negrita: son las que
                // dan calidad de verdad, frente al zoom digital.
                setTypeface(null, if (optical) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(pad, dp(9f).toInt(), pad, dp(9f).toInt())
                minHeight = dp(48f).toInt() // objetivo táctil accesible
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.zoom_pill_bg)
                contentDescription = if (optical) "Zoom $label, lente óptica" else "Zoom $label digital"
                setOnClickListener {
                    currentZoom = controller.setZoom(z)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    showZoom()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8f).toInt() }
            binding.zoomStrip.addView(tv, lp)
        }
        highlightZoomStrip()
    }

    /** Marca en ámbar la parada óptica activa. */
    private fun highlightZoomStrip() {
        val stops = controller.zoomStops()
        if (binding.zoomStrip.childCount != stops.size) return
        // Activa = la mayor parada que no supera el zoom actual.
        var active = 0
        stops.forEachIndexed { i, t -> if (currentZoom >= t.first - 0.01f) active = i }
        for (i in 0 until binding.zoomStrip.childCount) {
            (binding.zoomStrip.getChildAt(i) as? TextView)?.setTextColor(
                if (i == active) ContextCompat.getColor(this, R.color.accent)
                else Color.parseColor("#B3FFFFFF")
            )
        }
    }

    /** Muestra SIEMPRE la lente física activa y el zoom: es la ventaja que nos diferencia. */
    private fun updateLensChip() {
        // Zoom en la escala estándar del usuario (1x ≈ 24 mm), no en la interna.
        val disp = currentZoom * controller.zoomDisplayFactor
        val base = String.format(Locale.US, "%s · %.1fx", controller.activeLensLabel, disp)
        // Si hay lentes apagadas, el zoom cae a digital sin poder usar esa óptica.
        // Antes esto era invisible: se perdía el teleobjetivo y nadie sabía por qué.
        val n = controller.disabledLensCount
        if (n > 0) {
            binding.lensChip.text = "$base  ⚠ $n LENTE${if (n > 1) "S" else ""} OFF"
            binding.lensChip.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            binding.lensChip.text = base
            binding.lensChip.setTextColor(ContextCompat.getColor(this, R.color.warm_white))
        }
    }

    private fun showZoom() {
        updateLensChip()
        highlightZoomStrip()
        binding.zoomPill.text =
            String.format(Locale.US, "%.1fx", currentZoom * controller.zoomDisplayFactor)
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
        binding.btnShutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
            .withEndAction {
                binding.btnShutter.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(OvershootInterpolator()).start()
            }.start()
        val cb: (Boolean) -> Unit = { ok ->
            capturing = false
            binding.nightLabel.visibility = View.GONE
            if (ok) {
                refreshThumbnail()
                bounceThumbnail()
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
        if (nightOn) {
            // Apilado multi-frame: sin destello, con indicador de procesado.
            binding.nightLabel.visibility = View.VISIBLE
            controller.takeNightPhoto(cb)
        } else {
            flashScreen()
            controller.takePhoto(cb)
        }
    }

    // ---- Ráfaga (mantener pulsado el obturador) ----
    private fun startBurst() {
        if (mode != "photo" || nightOn || capturing || burstRemaining > 0) return
        burstRemaining = 7
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(this, "Ráfaga", Toast.LENGTH_SHORT).show()
        burstNext()
    }

    private fun burstNext() {
        if (burstRemaining <= 0) return
        if (capturing) { ui.postDelayed({ burstNext() }, 50); return }
        capturing = true
        flashScreen()
        controller.takePhoto { ok ->
            capturing = false
            burstRemaining--
            if (ok) refreshThumbnail()
            if (burstRemaining > 0) ui.postDelayed({ burstNext() }, 60) else bounceThumbnail()
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

    // ---- WhatsApp: toma foto y la comparte ----
    private fun shootAndShareWhatsApp() {
        if (capturing) return
        if (mode == "video") {
            Toast.makeText(this, R.string.mode_photo, Toast.LENGTH_SHORT).show()
            return
        }
        capturing = true
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flashScreen()
        controller.takePhoto { ok ->
            capturing = false
            if (ok) {
                refreshThumbnail()
                bounceThumbnail()
                shareLatestToWhatsApp()
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLatestToWhatsApp() {
        val uri = latestMediaUri() ?: return
        val base = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // WhatsApp normal → WhatsApp Business → selector general.
        for (pkg in arrayOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                startActivity(Intent(base).setPackage(pkg))
                return
            } catch (e: Exception) {
            }
        }
        try {
            startActivity(Intent.createChooser(base, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_whatsapp, Toast.LENGTH_SHORT).show()
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

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && ::controller.isInitialized) {
            // Solo en la primera pulsación (repeatCount 0): un disparo por clic.
            if (event == null || event.repeatCount == 0) {
                if (mode == "video") toggleRecord() else startPhotoOrTimer()
            }
            return true // consume para que NO aparezca el control de volumen
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Consumir también la liberación para suprimir el panel de volumen del sistema.
        if (isVolumeKey(keyCode) && ::controller.isInitialized) return true
        return super.onKeyUp(keyCode, event)
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
        if (on) { // RAW, noche y QR son excluyentes
            nightOn = false
            binding.chipNight.setTextColor(Color.parseColor("#CCFFFFFF"))
            binding.qrCard.visibility = View.GONE
        }
        Toast.makeText(this, if (on) "RAW + JPEG" else "Solo JPEG", Toast.LENGTH_SHORT).show()
    }

    private fun toggleNight() {
        if (controller.isRecording) return
        val on = controller.setNightEnabled(!nightOn)
        nightOn = on
        binding.chipNight.setTextColor(
            if (on) ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")
        )
        if (on) { // noche apaga RAW y QR (excluyentes); reflejarlo en los chips
            binding.chipRaw.setTextColor(Color.parseColor("#CCFFFFFF"))
            binding.qrCard.visibility = View.GONE
        }
        Toast.makeText(this, if (on) "Modo noche ON" else "Modo noche OFF", Toast.LENGTH_SHORT).show()
    }

    /** Ultra HDR: captura en JPEG_R, con mucho más rango dinámico en contraluces. */
    private fun toggleHdr() {
        if (!controller.hasHdr) {
            Toast.makeText(this, "Esta lente no admite Ultra HDR", Toast.LENGTH_SHORT).show()
            return
        }
        val on = controller.setHdrEnabled(!controller.hdrEnabled)
        binding.chipHdr.setTextColor(chipColor(on))
        if (on) binding.chipRaw.setTextColor(chipColor(false)) // excluyente con RAW
        prefs.edit().putBoolean("hdr", on).apply()
        Toast.makeText(this, if (on) "Ultra HDR activado" else "Ultra HDR desactivado", Toast.LENGTH_SHORT).show()
    }

    /** Muestra u oculta el panel con las opciones secundarias. */
    private fun toggleMorePanel() {
        val show = binding.morePanel.visibility != View.VISIBLE
        binding.morePanel.visibility = if (show) View.VISIBLE else View.GONE
        binding.chipMore.setTextColor(chipColor(show))
    }

    // ---- Escaneo SIEMPRE activo de QR y códigos de barras ----
    // Sin chip, sin modo dedicado y sin stream extra: se analiza el propio visor
    // (mismo método que la lupa), así funciona en cualquier modo y no compite con
    // RAW ni con el modo noche por el número de streams de la cámara.

    private val autoScanner by lazy {
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
    }
    private var autoScanBusy = false

    private val autoScanTick = object : Runnable {
        override fun run() {
            scanViewfinderForCodes()
            ui.postDelayed(this, 700)
        }
    }

    private fun scanViewfinderForCodes() {
        if (autoScanBusy || capturing || controller.isRecording) return
        if (binding.qrCard.visibility == View.VISIBLE) return // ya hay uno en pantalla
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        val bmp = try { t.getBitmap(t.width / 2, t.height / 2) } catch (e: Exception) { null } ?: return
        autoScanBusy = true
        try {
            val input = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            autoScanner.process(input)
                .addOnSuccessListener { codes ->
                    codes.firstOrNull()?.rawValue?.let { v ->
                        if (v.isNotEmpty()) showQrResult(v)
                    }
                }
                .addOnCompleteListener { autoScanBusy = false; bmp.recycle() }
        } catch (e: Exception) {
            autoScanBusy = false
            bmp.recycle()
        }
    }

    // ---- QR / código de barras ----
    private fun showQrResult(value: String) {
        if (value == qrDismissed) return // el usuario ya lo cerro
        if (binding.qrCard.visibility == View.VISIBLE && qrValue == value) return // ya mostrado
        qrValue = value
        binding.qrText.text = value
        binding.qrCard.visibility = View.VISIBLE
        binding.btnQrOpen.visibility =
            if (value.startsWith("http://") || value.startsWith("https://")) View.VISIBLE else View.GONE
    }

    private fun openQr() {
        val v = qrValue ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyQr() {
        val v = qrValue ?: return
        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("QR", v))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        binding.qrCard.visibility = View.GONE
    }

    private fun cycleRatio() {
        if (controller.isRecording) return
        ratioIndex = (ratioIndex + 1) % ratioLabels.size
        binding.chipRatio.text = ratioLabels[ratioIndex]
        binding.chipRatio.setTextColor(
            if (ratioIndex == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        prefs.edit().putInt("capRatio", ratioIndex).apply()
        controller.setCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
    }

    private fun cycleFilter() {
        filterIndex = (filterIndex + 1) % Filters.list.size
        applyFilter()
        prefs.edit().putInt("filter", filterIndex).apply()
    }

    private fun applyFilter() {
        val f = Filters.list[filterIndex.coerceIn(0, Filters.list.size - 1)]
        binding.chipFilter.text = "✦ ${f.name}"
        binding.chipFilter.setTextColor(
            if (f.matrix == null) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        controller.setCaptureColorMatrix(f.matrix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.texture.setRenderEffect(
                if (f.matrix == null) null
                else RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(f.matrix))
            )
        }
    }

    private fun toggleRes() {
        if (controller.isRecording) return
        fullRes = !fullRes
        binding.chipRes.text = if (fullRes) "FULL" else "MED"
        binding.chipRes.setTextColor(
            if (fullRes) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
        )
        prefs.edit().putBoolean("capFull", fullRes).apply()
        controller.setCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
    }

    // ---- Lentes (activar/desactivar en el ciclo de zoom) ----
    private fun toggleLensPanel() {
        val show = binding.lensPanel.visibility != View.VISIBLE
        if (show) {
            if (proOn) togglePro() // no solapar paneles
            if (binding.videoPanel.visibility == View.VISIBLE) {
                binding.videoPanel.visibility = View.GONE
                binding.chipVid.setTextColor(chipColor(false))
            }
            buildLensChips()
            binding.lensPanel.visibility = View.VISIBLE
        } else {
            binding.lensPanel.visibility = View.GONE
        }
        binding.chipLenses.setTextColor(
            if (show) ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")
        )
    }

    private fun buildLensChips() {
        binding.lensPanel.removeAllViews()
        val cands = controller.backLensCandidates()
        if (cands.isEmpty()) return
        val minFocal = cands.first().second
        val maxFocal = cands.last().second
        for ((id, focal) in cands) {
            val label = when {
                cands.size >= 2 && focal <= minFocal -> "GA"
                cands.size >= 2 && focal >= maxFocal -> "TELE"
                else -> "ID $id"
            }
            val chip = TextView(this, null, 0, R.style.ProChip)
            chip.text = label
            chip.setTextColor(
                if (!disabledLenses.contains(id)) ContextCompat.getColor(this, R.color.accent)
                else Color.parseColor("#66FFFFFF")
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6f).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener { onLensChipTap(id) }
            binding.lensPanel.addView(chip)
        }
    }

    private fun onLensChipTap(id: String) {
        val enabledCount = controller.backLensCandidates().count { !disabledLenses.contains(it.first) }
        if (!disabledLenses.contains(id)) {
            if (enabledCount <= 1) {
                Toast.makeText(this, R.string.lens_min_one, Toast.LENGTH_SHORT).show()
                return
            }
            disabledLenses.add(id)
        } else {
            disabledLenses.remove(id)
        }
        prefs.edit().putStringSet("disabledLenses", HashSet(disabledLenses)).apply()
        controller.setDisabledLensIds(disabledLenses)
        buildLensChips()
        updateLensChip() // refleja al instante el aviso de lente desactivada
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { autoScanner.close() } catch (e: Exception) {}
        if (::controller.isInitialized) controller.jpegSink = null
        super.onDestroy()
    }

    private fun chipColor(active: Boolean) =
        if (active) ContextCompat.getColor(this, R.color.accent) else Color.parseColor("#CCFFFFFF")

    // ---- Ajustes de video ----
    private fun toggleVideoPanel() {
        val show = binding.videoPanel.visibility != View.VISIBLE
        if (show) {
            if (proOn) togglePro()
            if (binding.lensPanel.visibility == View.VISIBLE) {
                binding.lensPanel.visibility = View.GONE
                binding.chipLenses.setTextColor(chipColor(false))
            }
            binding.videoPanel.visibility = View.VISIBLE
        } else {
            binding.videoPanel.visibility = View.GONE
        }
        binding.chipVid.setTextColor(chipColor(show))
    }

    private fun cycleVres() {
        vresIndex = (vresIndex + 1) % vresList.size
        if (vresList[vresIndex] == 2160 && !controller.supports4kVideo) {
            vresIndex = (vresIndex + 1) % vresList.size
        }
        controller.setVideoTargetHeight(vresList[vresIndex])
        prefs.edit().putInt("vres", vresIndex).apply()
        applyVideoSettings()
    }

    private fun toggleVfps() {
        vfps = if (vfps == 30) 60 else 30
        controller.setVideoFps(vfps)
        prefs.edit().putInt("vfps", vfps).apply()
        applyVideoSettings()
    }

    private fun toggleVcodec() {
        vhevc = !vhevc
        controller.setVideoHevc(vhevc)
        prefs.edit().putBoolean("vhevc", vhevc).apply()
        applyVideoSettings()
    }

    private fun toggleTl() {
        tlOn = !tlOn
        controller.setTimeLapse(tlOn)
        prefs.edit().putBoolean("tl", tlOn).apply()
        applyVideoSettings()
    }

    private fun applyVideoSettings() {
        binding.chipVres.text = vresLabels[vresIndex]
        binding.chipVres.setTextColor(chipColor(vresIndex != 0))
        binding.chipVfps.text = "${vfps}fps"
        binding.chipVfps.setTextColor(chipColor(vfps == 60))
        binding.chipVcodec.text = if (vhevc) "HEVC" else "H264"
        binding.chipVcodec.setTextColor(chipColor(vhevc))
        binding.chipTl.setTextColor(chipColor(tlOn))
        controller.setVideoTargetHeight(vresList[vresIndex])
        controller.setVideoFps(vfps)
        controller.setVideoHevc(vhevc)
        controller.setTimeLapse(tlOn)
    }

    /** Ciclo de lentes al voltear: trasera → cada frontal (incluye la de pantalla interna) → trasera. */
    private fun lensCycle(): List<String> {
        val backId = prefs.getString("cameraId", "3") ?: "3"
        return listOf(backId) + controller.frontLensIds()
    }

    private fun flipCamera() {
        if (controller.isRecording) return
        val cycle = lensCycle()
        if (cycle.size <= 1) {
            Toast.makeText(this, "No hay cámara frontal disponible", Toast.LENGTH_SHORT).show()
            return
        }
        camCycleIndex = (camCycleIndex + 1) % cycle.size
        val target = cycle[camCycleIndex]
        facing = if (camCycleIndex == 0) "back" else "front"
        currentZoom = 1f
        zoomRestored = true
        controller.close()
        controller.open(target)
        val frontCount = cycle.size - 1
        binding.chipFlip.text = when {
            camCycleIndex == 0 -> "⟲ atrás"
            frontCount > 1 -> "⟲ frontal $camCycleIndex"
            else -> "⟲ frontal"
        }
        binding.chipFlip.setTextColor(
            if (camCycleIndex == 0) Color.parseColor("#CCFFFFFF") else ContextCompat.getColor(this, R.color.accent)
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
        countdownRunnable?.let { ui.removeCallbacks(it) }
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
        countdownRunnable = r
        ui.postDelayed(r, 1000)
    }

    // ---- PRO ----
    private fun togglePro() {
        proOn = !proOn
        binding.proPanel.visibility = if (proOn) View.VISIBLE else View.GONE
        binding.proToggle.setTextColor(if (proOn) ContextCompat.getColor(this, R.color.accent) else dimWhite)
        if (proOn) {
            if (binding.lensPanel.visibility == View.VISIBLE) {
                binding.lensPanel.visibility = View.GONE
                binding.chipLenses.setTextColor(Color.parseColor("#CCFFFFFF"))
            }
            if (binding.videoPanel.visibility == View.VISIBLE) {
                binding.videoPanel.visibility = View.GONE
                binding.chipVid.setTextColor(Color.parseColor("#CCFFFFFF"))
            }
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
            "k" -> {
                wbKelvin = progressToKelvin(p)
                controller.setWhiteBalanceKelvin(wbKelvin)
                binding.proValue.text = "${wbKelvin}K"
            }
        }
    }

    private fun cycleWb() {
        proParam = "wb"
        wbIndex = (wbIndex + 1) % wbModes.size
        controller.setWhiteBalance(wbModes[wbIndex])
        binding.proValue.text = wbLabels[wbIndex]
    }

    private fun selectKelvin() {
        if (!controller.hasManualWb) {
            Toast.makeText(this, "WB manual no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        proParam = "k"
        binding.proSlider.progress = kelvinToProgress(wbKelvin)
        controller.setWhiteBalanceKelvin(wbKelvin)
        binding.proValue.text = "${wbKelvin}K"
    }

    private fun kelvinToProgress(k: Int): Int {
        val r = controller.kelvinRange
        return ((k - r.first) * 100 / (r.second - r.first)).coerceIn(0, 100)
    }

    private fun progressToKelvin(p: Int): Int {
        val r = controller.kelvinRange
        return (r.first + (r.second - r.first) * p / 100)
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
