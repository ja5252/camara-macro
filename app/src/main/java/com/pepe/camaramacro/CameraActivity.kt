package com.pepe.camaramacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
    /** El dedo está sobre el obturador: se precalienta el AF y se calla el escáner. */
    private var shutterHeld = false
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
    private var pickContent = false
    private val ratioLabels = arrayOf("RATIO", "4:3", "16:9", "1:1", "LLENA")
    private var ratioIndex = 0
    private var fullRes = true
    private var disabledLenses = HashSet<String>()
    private var nightOn = false
    private var qrValue: String? = null
    // Lista, no un solo valor: con una sola variable el aviso volvía a saltar en cuanto
    // entraba en cuadro un código distinto del que se acababa de descartar.
    private val qrDismissedList = HashSet<String>()
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

    /**
     * Espejo del visor en la pantalla externa del plegable. Puede no llegar a existir:
     * solo se enciende si el sistema publica de verdad la pantalla de cubierta.
     */
    private var mirror: CoverMirror? = null

    // ---- Colores del HUD ----
    // Estaban escritos a mano como Color.parseColor("#CCFFFFFF") / "#8CFFFFFF" /
    // "#4CD964" / "#FF3B30" repartidos por catorce sitios de este fichero: subir el
    // contraste obligaba a cambiarlos uno a uno y siempre quedaba alguno sin tocar,
    // así que la barra superior nunca era homogénea. Ahora salen de colors.xml.
    private val cAccent by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val cDim by lazy { ContextCompat.getColor(this, R.color.text_dim) }
    private val cOff by lazy { ContextCompat.getColor(this, R.color.text_off) }
    private val cWhite by lazy { ContextCompat.getColor(this, R.color.text_primary) }
    private val cWarm by lazy { ContextCompat.getColor(this, R.color.warm_white) }
    private val cFocusOk by lazy { ContextCompat.getColor(this, R.color.focus_ok) }
    private val cFocusFail by lazy { ContextCompat.getColor(this, R.color.focus_fail) }

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
        // GET_CONTENT/PICK: otra app pide una imagen (adjuntar documento, formularios,
        // subidas web...). Respondemos capturandola con la lente que SI funciona.
        pickContent = act == Intent.ACTION_GET_CONTENT || act == Intent.ACTION_PICK
        captureIntent = captureVideo || act == MediaStore.ACTION_IMAGE_CAPTURE || pickContent
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
                syncPreviewGravity()
                syncRatioChip()
                updateLensChip()
                buildZoomStrip()
                // Da un par de fotogramas a la lente nueva antes de quitar el congelado.
                ui.postDelayed({ releaseLensFade() }, 120)
            }
        }
        // El motor puede apagar RAW/HDR/noche/QR por su cuenta (son excluyentes entre
        // sí y dependen de la lente). Sin este aviso los chips se quedaban encendidos
        // mintiendo sobre un modo que ya no estaba activo.
        controller.onCaptureModesChanged = { runOnUiThread { syncCaptureModeChips() } }
        controller.onFirstFrame = { runOnUiThread { syncPreviewGravity() } }
        controller.onRecordingChanged = { rec -> onRecordingChanged(rec) }
        controller.onRawSaved = { ok ->
            if (!ok) runOnUiThread {
                Toast.makeText(this, R.string.raw_save_error, Toast.LENGTH_SHORT).show()
            }
        }
        controller.onRawUnavailable = {
            runOnUiThread {
                hint(getString(R.string.raw_unavailable))
                setChipState(binding.chipRaw, false, R.string.cd_raw)
            }
        }
        controller.onQrDetected = { value -> runOnUiThread { showQrResult(value) } }
        controller.onFocusState = { st ->
            runOnUiThread {
                val c = when (st) {
                    FocusState.FOCUSED -> cFocusOk       // verde: enfocado
                    FocusState.NOT_FOCUSED -> cFocusFail // rojo: no pudo
                    else -> cAccent                      // ámbar: buscando
                }
                binding.focusRing.backgroundTintList = ColorStateList.valueOf(c)
            }
        }
        controller.onHdrUnavailable = {
            runOnUiThread {
                hint(getString(R.string.hint_no_hdr))
                setChipState(binding.chipHdr, false, R.string.cd_hdr)
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
        // Devuelve SIEMPRE false para no robarle el clic ni la pulsación larga al botón:
        // esto solo escucha. En ACTION_DOWN se precalienta el autofoco, que es el único
        // adelanto real de latencia posible sin reprocesado, y se corta el escaneo de
        // códigos mientras el dedo está puesto.
        binding.btnShutter.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shutterHeld = true
                    if (mode == "photo" && !capturing) controller.prewarmAf()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> shutterHeld = false
            }
            false
        }
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
        binding.qrHint.setOnClickListener { openQrCard() }
        binding.btnQrOpen.setOnClickListener { openQr() }
        binding.btnQrCopy.setOnClickListener { copyQr() }
        binding.btnQrClose.setOnClickListener { dismissQr() }
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

        setUpCoverMirror()
        setUpAccessibility()

        // Si nos invoca otra app, el modo lo fija armIntentCapture: no pisarlo.
        if (!captureIntent) setMode(prefs.getString("mode", "photo") ?: "photo")
        restoreSettings()
    }

    // ================= PLEGABLE =================

    /**
     * Al plegar y desplegar el teléfono la Activity YA NO se recrea (el manifiesto
     * declara screenLayout y smallestScreenSize). Aquí solo hay que reajustar el visor:
     * la cámara sigue abierta, así que desaparecen los 539-572 ms de pantalla en negro
     * y no se pierden ni el zoom ni el modo ni los ajustes en curso.
     *
     * Lo único que hay que reponer a mano es coverMode: el motor lo decide una sola vez
     * al configurar las salidas leyendo el bool preview_fills_screen, y ese bool cambia
     * de valor al pasar de la pantalla de cubierta a la interior. Si no se repone, el
     * visor se queda con el criterio de la pantalla anterior. Al cambiarlo se dispara
     * un requestLayout, el TextureView cambia de tamaño y el propio motor recalcula la
     * matriz de transformación en onSurfaceTextureSizeChanged: no hace falta (ni se
     * debe) cerrar el CameraDevice.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controller.isInitialized) return
        syncPreviewGravity()
        // El HUD se recoloca solo: cuelga del rectángulo del visor, no de la pantalla.
        binding.previewFrame.requestLayout()
        // Las paradas de zoom se reconstruyen con el nuevo ancho de píldora de dimens.
        buildZoomStrip()
    }

    /**
     * Alinea el visor con el modo de encuadre y replica la gravedad en el fotograma
     * congelado del cambio de lente.
     *
     * El TextureView estaba clavado a top|center_horizontal SIEMPRE. En la pantalla
     * interior, con el visor en modo LLENAR y 16:9, se medía a 692x1230dp dentro de un
     * padre de 716dp: los 514dp que sobraban (el 41,8% del encuadre) se perdían TODOS
     * por abajo y 0dp por arriba, así que lo que el usuario centraba en pantalla acababa
     * al 29% de altura del fotograma real. Centrado, el recorte se reparte arriba y
     * abajo, que es lo que cualquiera espera al encuadrar.
     */
    private fun syncPreviewGravity() {
        // Mismo criterio que usa el motor al configurar las salidas, con su API pública:
        // el recorte se aplica si el usuario pidió LLENA o si la pantalla lo pide.
        val cover = controller.currentAspect == AspectRatio.FULL ||
            resources.getBoolean(R.bool.preview_fills_screen)
        val g = if (cover) android.view.Gravity.CENTER
        else android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        (binding.texture.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.gravity != g) {
                it.gravity = g
                binding.texture.layoutParams = it
            }
        }
        if (binding.texture.coverMode != cover) binding.texture.coverMode = cover
    }

    /** El chip de proporción nacía con "16:9" escrito en el XML mientras las fotos
     *  salían en 4:3: en una app cuyo valor es decir la verdad sobre la óptica, el HUD
     *  no puede equivocarse sobre el encuadre. Ahora se lee del motor. */
    private fun syncRatioChip() {
        val real = controller.currentAspect.ordinal.coerceIn(0, ratioLabels.size - 1)
        if (real != ratioIndex) ratioIndex = real
        binding.chipRatio.text = ratioLabels[ratioIndex]
        setChipState(binding.chipRatio, ratioIndex != 0, R.string.cd_ratio, ratioLabels[ratioIndex])
    }

    /** Prepara el espejo de la pantalla externa. El chip solo aparece si esa pantalla
     *  existe de verdad: prometer un botón que no hace nada es peor que no tenerlo. */
    private fun setUpCoverMirror() {
        val m = CoverMirror(this, binding.texture)
        m.onShutter = {
            // Disparo desde la pantalla externa: es para lo que sirve el espejo.
            if (mode == "video") toggleRecord() else startPhotoOrTimer()
        }
        m.onAvailabilityChanged = { available ->
            runOnUiThread {
                binding.chipMirror.visibility = if (available) View.VISIBLE else View.GONE
                if (!available) setChipState(binding.chipMirror, false, R.string.cd_mirror)
            }
        }
        binding.chipMirror.setOnClickListener { toggleMirror() }
        mirror = m
    }

    private fun toggleMirror() {
        val m = mirror ?: return
        if (m.isShowing) {
            m.hide()
            setChipState(binding.chipMirror, false, R.string.cd_mirror)
            hint(getString(R.string.mirror_off))
            return
        }
        // Distinguimos "apagado" de "la pantalla externa no lo aceptó": si se falla en
        // silencio, el usuario pulsa el chip tres veces creyendo que no responde.
        val ok = m.show()
        setChipState(binding.chipMirror, ok, R.string.cd_mirror)
        hint(getString(if (ok) R.string.mirror_on else R.string.mirror_failed))
    }

    /** Reabre con los últimos ajustes (flash, temporizador, cuadrícula). */
    private fun restoreSettings() {
        // Flash: el modo linterna (3) no se restaura para no encender la luz al abrir.
        flashMode = prefs.getInt("flash", 0).let { if (it == 3) 0 else it }
        controller.setFlashMode(flashMode)
        applyFlashChip()

        timerSec = prefs.getInt("timer", 0)
        applyTimerChip()

        gridOn = prefs.getBoolean("grid", false)
        binding.gridOverlay.showGrid = gridOn
        binding.gridOverlay.showLevel = gridOn
        setChipState(binding.chipGrid, gridOn, R.string.cd_grid)

        // Por defecto 16:9 (índice 2): en una pantalla tan alargada, la proporción nativa
        // 4:3 dejaba una franja negra enorme. Con 16:9 el visor ocupa un 80% de la pantalla.
        // El 4:3 (máxima resolución) sigue a un toque en el chip RATIO.
        // Migración: las instalaciones anteriores tienen guardado el valor viejo (0 =
        // nativo) y no verían el cambio nunca; se les pasa a 16:9 UNA sola vez, respetando
        // después lo que el usuario elija.
        if (!prefs.getBoolean("migr169", false)) {
            if (prefs.getInt("capRatio", 0) == 0) prefs.edit().putInt("capRatio", 2).apply()
            prefs.edit().putBoolean("migr169", true).apply()
        }
        ratioIndex = prefs.getInt("capRatio", 2).coerceIn(0, ratioLabels.size - 1)
        binding.chipRatio.text = ratioLabels[ratioIndex]
        setChipState(binding.chipRatio, ratioIndex != 0, R.string.cd_ratio, ratioLabels[ratioIndex])

        fullRes = prefs.getBoolean("capFull", true)
        applyResChip()

        disabledLenses = HashSet(prefs.getStringSet("disabledLenses", emptySet()) ?: emptySet())

        // presetHdr deja anotada la intención SIN mentir: setHdrEnabled devolvía false
        // antes de que la lente estuviera abierta y el chip se quedaba apagado aunque
        // el ajuste sí estuviera guardado. El motor avisa por onCaptureModesChanged
        // cuando resuelve si esta lente lo admite y ahí se repinta.
        controller.presetHdr(prefs.getBoolean("hdr", false))
        syncCaptureModeChips()

        filterIndex = prefs.getInt("filter", 0).coerceIn(0, Filters.list.size - 1)
        applyFilter()

        vresIndex = prefs.getInt("vres", 0).coerceIn(0, vresList.size - 1)
        vfps = prefs.getInt("vfps", 30)
        vhevc = prefs.getBoolean("vhevc", false)
        tlOn = prefs.getBoolean("tl", false)
        applyVideoSettings()

        // Deja la ranura de paneles cerrada Y de paso pone el color y la descripción
        // accesible de los cuatro chips que abren panel, que si no arrancaban sin nada.
        showPanel(null)
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        refreshThumbnail()
        ui.removeCallbacks(autoScanTick)
        ui.postDelayed(autoScanTick, 1200)
        // Vigila si aparece o desaparece la pantalla externa del plegable: el chip
        // "Espejo" solo debe existir cuando de verdad hay dónde pintarlo.
        mirror?.start()
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
        // El espejo copia fotogramas del visor: sin cámara no hay nada que copiar y
        // dejarlo vivo sería una lectura de GPU cada 66 ms con la app en segundo plano.
        mirror?.stop()
        // Ojo: onCreate puede terminar en finish() ANTES de inflar la vista (sin lente
        // guardada, o invocados por otra app sin cámara trasera válida), y onPause se
        // llama igual. Tocar binding sin esta guarda revienta al salir en ese caso.
        if (::binding.isInitialized) {
            setChipState(binding.chipMirror, false, R.string.cd_mirror)
            // Un temporizador en marcha al salir seguía contando y disparaba con la
            // cámara ya cerrada.
            cancelCountdown()
        }
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
        // El estado del HUD tiene que volver atrás con la cámara. Tras bloquear la
        // pantalla estando en la frontal, se reabría la trasera pero el chip seguía
        // diciendo "frontal", y la insignia AE/AF BLOQUEADO se quedaba encendida con
        // el bloqueo ya deshecho por la reapertura.
        setChipState(binding.chipFlip, false, R.string.cd_flip, getString(R.string.lens_back))
        aeAfLocked = false
        binding.aeLockBadge.visibility = View.GONE
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
        // Cambiar de modo con una cuenta atrás en marcha disparaba una foto en pleno
        // modo video segundos después.
        cancelCountdown()
        binding.tabPhoto.setTextColor(if (photo) cAccent else cOff)
        binding.tabVideo.setTextColor(if (photo) cOff else cAccent)
        binding.tabPhoto.isSelected = photo
        binding.tabVideo.isSelected = !photo
        binding.shutterIcon.visibility = if (photo) View.GONE else View.VISIBLE
        binding.shutterIcon.setBackgroundResource(R.drawable.rec_dot)
        // El chip de ajustes de video solo aparece en modo video.
        binding.chipVid.visibility = if (photo) View.GONE else View.VISIBLE
        if (photo && binding.videoPanel.visibility == View.VISIBLE) showPanel(null)
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
        // x,y llegan en coordenadas de gesture_area, que ES el rectángulo visible de la
        // imagen: PreviewFrameLayout lo coloca justo encima del fotograma. Para pasar a
        // coordenadas del TextureView basta con el desplazamiento entre ambos, que en
        // modo LLENAR NO es cero porque ahí el texture es más grande que lo visible y
        // sobresale por los cuatro lados.
        // El código anterior restaba t.left/t.top a un toque medido en la PANTALLA
        // entera, con un comentario que afirmaba que "en modo LLENA t.left/t.top son
        // negativos": con gravedad top|center_horizontal t.top nunca fue negativo, así
        // que el punto de enfoque caía desplazado hasta un 20% del encuadre.
        val hud = binding.previewHud
        val lx = x + (hud.left - t.left)
        val ly = y + (hud.top - t.top)
        if (lx < 0f || ly < 0f || lx > t.width || ly > t.height) return // fuera del encuadre
        controller.setFocusPoint(lx, ly, t.width, t.height)
        showFocusRing(x, y)              // el anillo vive dentro del visor
        showMagnifier(x, y, lx, ly)      // la lupa recorta sobre el texture
        showEvQuick()                    // exposición al alcance, sin entrar a PRO
        binding.gestureArea.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Enfoque al centro sin vista: el visor concentra cuatro gestos y no tenía ni una
     *  sola acción accesible, así que sin tacto fino la app era inservible. */
    private fun focusCenter() {
        val hud = binding.previewHud
        if (hud.width == 0 || hud.height == 0) return
        focusAt(hud.width / 2f, hud.height / 2f)
    }

    private val hideMagnifier = Runnable { binding.magnifierCard.visibility = View.GONE }

    /** Lupa: muestra una zona ampliada del punto enfocado para confirmar nitidez. */
    private fun showMagnifier(hudX: Float, hudY: Float, texX: Float, texY: Float) {
        val tw = binding.texture.width
        val th = binding.texture.height
        if (tw == 0 || th == 0) return
        try {
            val bmp = binding.texture.getBitmap(tw, th) ?: return
            val crop = (tw * 0.12f).toInt().coerceAtLeast(40)
            val cx = texX.toInt().coerceIn(crop / 2, (tw - crop / 2).coerceAtLeast(crop / 2))
            val cy = texY.toInt().coerceIn(crop / 2, (th - crop / 2).coerceAtLeast(crop / 2))
            val left = (cx - crop / 2).coerceIn(0, (tw - crop).coerceAtLeast(0))
            val top = (cy - crop / 2).coerceIn(0, (th - crop).coerceAtLeast(0))
            val w = crop.coerceAtMost(tw - left)
            val h = crop.coerceAtMost(th - top)
            if (w <= 0 || h <= 0) { bmp.recycle(); return }
            val region = android.graphics.Bitmap.createBitmap(bmp, left, top, w, h)
            // La lupa va al cuadrante OPUESTO al dedo. Clavada arriba a la derecha
            // tapaba justo lo que el usuario acababa de tocar en esa esquina, y encima
            // el panel "Más" se dibujaba encima de ella: parecía que no funcionaba.
            val hudW = binding.previewHud.width
            val hudH = binding.previewHud.height
            if (hudW > 0 && hudH > 0) {
                val g = (if (hudY < hudH / 2f) android.view.Gravity.BOTTOM else android.view.Gravity.TOP) or
                    (if (hudX < hudW / 2f) android.view.Gravity.END else android.view.Gravity.START)
                (binding.magnifierCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    if (lp.gravity != g) {
                        lp.gravity = g
                        binding.magnifierCard.layoutParams = lp
                    }
                }
            }
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

    private val hideEvQuick = Runnable {
        if (binding.evQuick.visibility == View.VISIBLE) showPanel(null)
    }

    /** Muestra el ajuste de exposición junto al enfoque: el caso real más común
     *  (contraluces, comida oscura) sin obligar a entrar al modo PRO. */
    private fun showEvQuick() {
        val r = controller.evRange
        if (r.second <= r.first) return // la lente no permite compensación
        // Si ya hay otro panel abierto no se le echa encima. Antes ev_quick aparecía
        // en CADA toque de enfoque, a la misma cota que el panel PRO y por encima:
        // dos sliders superpuestos, y el de arriba movía la exposición.
        if (binding.evQuick.visibility != View.VISIBLE && anyPanelVisible()) return
        binding.evSlider.progress = evToProgress(evSteps)
        binding.evLabel.text = evLabel(evSteps)
        showPanel(binding.evQuick)
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
        binding.gestureArea.announceForAccessibility(
            getString(if (aeAfLocked) R.string.ae_af_locked else R.string.ae_af_unlocked)
        )
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
            // GET_CONTENT/PICK esperan un content:// legible, no una miniatura.
            if (pickContent) {
                val uri = writeSharedJpeg(bytes)
                if (uri != null) {
                    runOnUiThread {
                        setResult(
                            RESULT_OK,
                            Intent().setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                        finish()
                    }
                    return@sink true
                }
                return@sink false
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
            // Ya no hay que copiar el tamaño del texture a mano: lens_fade cuelga del
            // PreviewFrameLayout, que lo coloca exactamente sobre el rectángulo visible
            // de la imagen, y con centerCrop encaja también en modo LLENAR.
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

    /** Escribe la foto en la caché privada y la expone por content:// para otra app. */
    private fun writeSharedJpeg(bytes: ByteArray): Uri? = try {
        val dir = java.io.File(cacheDir, "compartir").apply { if (!exists()) mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile) it.delete() } // no acumular basura
        val f = java.io.File(dir, "captura_${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(f).use { it.write(bytes) }
        androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", f
        )
    } catch (e: Exception) {
        null
    }

    // ---- Tira de zoom (una píldora por lente física real) ----

    /** Construye la tira una vez que la cámara reportó su cadena de lentes. */
    private fun buildZoomStrip() {
        val stops = controller.zoomStops()
        binding.zoomStrip.removeAllViews()
        if (stops.size < 2) return // con una sola lente no aporta nada
        // TODAS las píldoras EXACTAMENTE iguales, con ancho y alto fijos de dimens.
        // La queja literal del usuario fue "botones de zoom disparejos": eran
        // wrap_content con minWidth, así que "0.6x" y "2.9x" salían más anchas que
        // "1x", "2x" y "5x". Lo óptico se distingue por COLOR, nunca por tamaño.
        val w = resources.getDimensionPixelSize(R.dimen.zoom_stop_width)
        val h = resources.getDimensionPixelSize(R.dimen.zoom_stop_height)
        stops.forEach { (z, label, optical) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setBackgroundResource(R.drawable.zoom_stop_bg)
                contentDescription = getString(
                    if (optical) R.string.cd_zoom_optical else R.string.cd_zoom_digital, label
                )
                setOnClickListener {
                    currentZoom = controller.setZoom(z)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    showZoom()
                }
            }
            markAsButton(tv)
            val lp = LinearLayout.LayoutParams(w, h).apply { marginEnd = dp(8f).toInt() }
            binding.zoomStrip.addView(tv, lp)
        }
        highlightZoomStrip()
    }

    /** Marca la parada activa. */
    private fun highlightZoomStrip() {
        val stops = controller.zoomStops()
        if (binding.zoomStrip.childCount != stops.size) return
        // La activa es la parada MÁS CERCANA al zoom real, no la última que no lo supera:
        // con el zoom global en 6,6x quedaba resaltada la de 5x, que es mentira.
        var active = 0
        var best = Float.MAX_VALUE
        stops.forEachIndexed { i, t ->
            val d = kotlin.math.abs(currentZoom - t.first)
            if (d < best) { best = d; active = i }
        }
        for (i in 0 until binding.zoomStrip.childCount) {
            val esOptica = stops.getOrNull(i)?.third == true
            val v = binding.zoomStrip.getChildAt(i) as? TextView ?: continue
            // El estado seleccionado se lee de reojo: fondo ámbar al 18% con filo ámbar
            // (zoom_stop_bg), no solo el color de la cifra.
            v.isSelected = i == active
            v.setTextColor(
                when {
                    i == active -> cAccent
                    esOptica -> cWhite      // lente física real: blanco pleno
                    else -> cOff            // zoom digital: atenuado
                }
            )
        }
    }

    /**
     * Chip de lente: UNA sola fuente de verdad para la óptica.
     *
     * Antes el rótulo decía "ID6 · 70 MM · 6.6X" mientras la píldora decía "5x", y en
     * otra captura "ID6 · 70 MM · 10.5X" con la píldora en "4.6x": los milímetros
     * quedaban congelados en la focal FÍSICA mientras el recorte digital crecía hasta
     * 3,6x, así que la pantalla se contradecía a sí misma en una app cuyo mayor valor
     * es decir la verdad sobre la óptica.
     *
     * Ahora todo sale del mismo currentZoom: la píldora muestra el zoom total y el chip
     * la focal EFECTIVA (focal equivalente de la lente × recorte de esa lente). Y ya no
     * hay jerga: "GRAN ANGULAR · 15 MM", no "ID3". El identificador se queda en la
     * pantalla de elección de lente, que es donde hace falta.
     */
    private fun updateLensChip() {
        val nombre = lensHumanName()
        val mm = effectiveFocalMm()
        val recorte = lensCropFactor()
        val base = when {
            mm > 0 && recorte > 1.05f ->
                String.format(Locale.US, "%s · %d mm · %s", nombre, mm, getString(R.string.lens_digital))
            mm > 0 -> String.format(Locale.US, "%s · %d mm", nombre, mm)
            else -> nombre
        }
        // Si hay lentes apagadas, el zoom cae a digital sin poder usar esa óptica.
        // Antes esto era invisible: se perdía el teleobjetivo y nadie sabía por qué.
        val n = controller.disabledLensCount
        if (n > 0) {
            val off = if (n == 1) getString(R.string.lens_off_one)
            else getString(R.string.lens_off_many, n)
            binding.lensChip.text = "$base · $off"
            binding.lensChip.setTextColor(cAccent)
        } else {
            binding.lensChip.text = base
            // Ámbar cuando el número ya es recorte digital: el usuario tiene que saber
            // que a partir de ahí no está ganando óptica, está ampliando píxeles.
            binding.lensChip.setTextColor(if (recorte > 1.05f) cAccent else cWarm)
        }
        binding.lensChip.contentDescription = binding.lensChip.text
    }

    /** Nombre humano de la lente activa, deducido de su sitio en la cadena de zoom. */
    private fun lensHumanName(): String {
        if (facing != "back") return getString(R.string.lens_front)
        val opticas = controller.zoomStops().filter { it.third }
        if (opticas.size < 2) return getString(R.string.lens_main)
        val i = activeOpticalIndex(opticas)
        return when (i) {
            0 -> getString(R.string.lens_wide)
            opticas.size - 1 -> getString(R.string.lens_tele)
            else -> getString(R.string.lens_main)
        }
    }

    /** Índice de la parada óptica que está realmente en uso (la mayor que no supera
     *  el zoom actual: por encima de ella todo es recorte digital de esa misma lente). */
    private fun activeOpticalIndex(opticas: List<Triple<Float, String, Boolean>>): Int {
        var idx = 0
        opticas.forEachIndexed { i, t -> if (currentZoom >= t.first - 0.01f) idx = i }
        return idx
    }

    /** Cuánto se está recortando digitalmente SOBRE la lente física activa. */
    private fun lensCropFactor(): Float {
        val opticas = controller.zoomStops().filter { it.third }
        if (opticas.isEmpty()) return 1f
        val base = opticas[activeOpticalIndex(opticas)].first
        return if (base > 0f) (currentZoom / base).coerceAtLeast(1f) else 1f
    }

    /**
     * Focal equivalente EFECTIVA en mm.
     * El milimetraje físico solo lo conoce el motor y lo publica dentro de
     * activeLensLabel ("ID3 · 15 mm"), así que se extrae de ahí y se multiplica por el
     * recorte. Si el formato de esa etiqueta cambiase, esto devuelve 0 y el chip se
     * queda solo con el nombre de la lente: nunca enseña un número inventado.
     */
    private fun effectiveFocalMm(): Int {
        val m = Regex("(\\d+)\\s*mm").find(controller.activeLensLabel) ?: return 0
        val fisica = m.groupValues[1].toIntOrNull() ?: return 0
        return Math.round(fisica * lensCropFactor())
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
            if (binding.nightLabel.visibility == View.VISIBLE) showCenterSlot(null)
            if (ok) {
                refreshThumbnail()
                bounceThumbnail()
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
        if (nightOn) {
            // Apilado multi-frame: sin destello, con indicador de procesado.
            showCenterSlot(binding.nightLabel)
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
        hint(getString(R.string.hint_burst))
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
        val uri = latestOwnUri()
        if (uri != null) binding.thumbnailImage.load(uri)
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // ---- WhatsApp: toma foto y la comparte ----
    private fun shootAndShareWhatsApp() {
        if (capturing) return
        if (mode == "video") {
            hint(getString(R.string.hint_only_photo))
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
        val uri = latestOwnUri() ?: return
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

    /** Lo ultimo guardado por NOSOTROS. Preferimos la URI directa del motor: buscar en
     *  MediaStore fallaba desde que guardamos en DCIM/Camera en vez de Pictures. */
    private fun latestOwnUri(): Uri? =
        (if (::controller.isInitialized) controller.ultimoGuardado else null) ?: latestMediaUri()

    private fun latestMediaUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%DCIM/Camera%")
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
        setChipState(binding.chipGrid, gridOn, R.string.cd_grid)
        announceChip(binding.chipGrid)
        prefs.edit().putBoolean("grid", gridOn).apply()
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        applyTimerChip()
        announceChip(binding.chipTimer)
        prefs.edit().putInt("timer", timerSec).apply()
    }

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 4
        controller.setFlashMode(flashMode)
        applyFlashChip()
        announceChip(binding.chipFlash)
        prefs.edit().putInt("flash", flashMode).apply()
    }

    private fun toggleRaw() {
        if (!controller.hasRaw) {
            hint(getString(R.string.hint_no_raw))
            return
        }
        val on = controller.setRawEnabled(!controller.rawEnabled)
        // RAW, noche y QR son excluyentes: la exclusión la resuelve el motor y aquí
        // solo se repintan los cuatro chips a la vez, para que ninguno se quede
        // encendido anunciando un modo que ya está apagado.
        syncCaptureModeChips()
        if (on && binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
        hint(getString(if (on) R.string.hint_raw_on else R.string.hint_raw_off))
    }

    private fun toggleNight() {
        if (controller.isRecording) return
        val on = controller.setNightEnabled(!nightOn)
        nightOn = on
        syncCaptureModeChips()
        if (on && binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
        hint(getString(if (on) R.string.hint_night_on else R.string.hint_night_off))
    }

    /** Ultra HDR: captura en JPEG_R, con mucho más rango dinámico en contraluces. */
    private fun toggleHdr() {
        if (!controller.hasHdr) {
            hint(getString(R.string.hint_no_hdr))
            return
        }
        val on = controller.setHdrEnabled(!controller.hdrEnabled)
        syncCaptureModeChips()
        prefs.edit().putBoolean("hdr", on).apply()
        hint(getString(if (on) R.string.hint_hdr_on else R.string.hint_hdr_off))
    }

    /** Muestra u oculta el panel con las opciones secundarias. */
    private fun toggleMorePanel() {
        showPanel(if (binding.morePanel.visibility == View.VISIBLE) null else binding.morePanel)
    }

    // ---- Escaneo SIEMPRE activo de QR y códigos de barras ----
    // Sin chip, sin modo dedicado y sin stream extra: se analiza el propio visor
    // (mismo método que la lupa), así funciona en cualquier modo y no compite con
    // RAW ni con el modo noche por el número de streams de la cámara.

    private val autoScanner by lazy {
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
    }
    private var autoScanBusy = false
    private var scanBitmap: Bitmap? = null

    private val autoScanTick = object : Runnable {
        override fun run() {
            scanViewfinderForCodes()
            ui.postDelayed(this, 1100)
        }
    }

    private fun scanViewfinderForCodes() {
        if (autoScanBusy || capturing || controller.isRecording) return
        if (binding.qrCard.visibility == View.VISIBLE) return // ya hay uno en pantalla
        if (binding.qrHint.visibility == View.VISIBLE) return // ya hay un aviso pendiente
        // Ni con el dedo puesto en el obturador ni durante la cuenta atrás: en esos dos
        // momentos el usuario está haciendo una foto, no leyendo un código, y la lectura
        // de GPU compite justo con el disparo.
        if (shutterHeld || binding.countdown.visibility == View.VISIBLE) return
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        // Bitmap PEQUEÑO y REUTILIZADO: pedir uno nuevo a media resolución cada vez hacía
        // una lectura de GPU enorme y una asignación por ciclo, y eso volvía la app lenta.
        // A ~360 px de ancho un QR se sigue leyendo de sobra.
        val target = 360
        val h = (target.toFloat() * t.height / t.width).toInt().coerceAtLeast(1)
        var bmp = scanBitmap
        if (bmp == null || bmp.width != target || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(target, h, Bitmap.Config.ARGB_8888)
            scanBitmap = bmp
        }
        val frame = try { t.getBitmap(bmp) } catch (e: Exception) { null } ?: return
        autoScanBusy = true
        try {
            val input = com.google.mlkit.vision.common.InputImage.fromBitmap(frame, 0)
            autoScanner.process(input)
                .addOnSuccessListener { codes ->
                    codes.firstOrNull()?.rawValue?.let { v ->
                        if (v.isNotEmpty()) showQrResult(v)
                    }
                }
                .addOnCompleteListener { autoScanBusy = false } // el bitmap se reutiliza
        } catch (e: Exception) {
            autoScanBusy = false
        }
    }

    // ---- QR / código de barras ----
    /**
     * Aviso discreto, no secuestro del visor.
     * La tarjeta saltaba al centro en cuanto entraba CUALQUIER código en cuadro,
     * mientras el usuario componía, y qrDismissed recordaba UN solo valor: al siguiente
     * código volvía a saltar. Ahora aparece una pastilla abajo, la tarjeta solo se
     * despliega si se toca, y los descartados se recuerdan todos.
     */
    private fun showQrResult(value: String) {
        if (value.isEmpty()) return
        if (qrDismissedList.contains(value)) return // el usuario ya lo cerró
        if (binding.qrCard.visibility == View.VISIBLE && qrValue == value) return // ya mostrado
        qrValue = value
        binding.qrHint.visibility = View.VISIBLE
    }

    /** Despliega la tarjeta con el contenido del código. */
    private fun openQrCard() {
        val v = qrValue ?: return
        binding.qrText.text = v
        binding.btnQrOpen.visibility =
            if (v.startsWith("http://") || v.startsWith("https://")) View.VISIBLE else View.GONE
        binding.qrHint.visibility = View.GONE
        showCenterSlot(binding.qrCard)
    }

    private fun dismissQr() {
        qrValue?.let { qrDismissedList.add(it) }
        binding.qrHint.visibility = View.GONE
        if (binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
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
        hint(getString(R.string.hint_copied))
        dismissQr()
    }

    private fun cycleRatio() {
        if (controller.isRecording) return
        ratioIndex = (ratioIndex + 1) % ratioLabels.size
        binding.chipRatio.text = ratioLabels[ratioIndex]
        setChipState(binding.chipRatio, ratioIndex != 0, R.string.cd_ratio, ratioLabels[ratioIndex])
        announceChip(binding.chipRatio)
        prefs.edit().putInt("capRatio", ratioIndex).apply()
        controller.setCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
        // Al pasar a LLENA (o al salir de ella) cambia el recorte del visor: hay que
        // repartirlo otra vez arriba y abajo en vez de dejarlo caer todo hacia abajo.
        syncPreviewGravity()
    }

    private fun cycleFilter() {
        filterIndex = (filterIndex + 1) % Filters.list.size
        applyFilter()
        prefs.edit().putInt("filter", filterIndex).apply()
    }

    private fun applyFilter() {
        val f = Filters.list[filterIndex.coerceIn(0, Filters.list.size - 1)]
        // El nombre solo cuando hay filtro puesto: el icono ya dice lo que es y "Normal"
        // ocupaba sitio para no decir nada.
        binding.chipFilter.text = if (f.matrix == null) "" else f.name
        setChipState(binding.chipFilter, f.matrix != null, R.string.cd_filter, f.name)
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
        applyResChip()
        announceChip(binding.chipRes)
        prefs.edit().putBoolean("capFull", fullRes).apply()
        controller.setCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
    }

    // ---- Lentes (activar/desactivar en el ciclo de zoom) ----
    private fun toggleLensPanel() {
        // La exclusión entre paneles ya no se hace con ifs a mano: showPanel deja uno
        // visible y esconde el resto, y el chip que corresponda se enciende solo.
        if (binding.lensPanel.visibility == View.VISIBLE) {
            showPanel(null)
        } else {
            buildLensChips()
            showPanel(binding.lensPanel)
        }
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
            val activa = !disabledLenses.contains(id)
            chip.text = label
            chip.isSelected = activa
            chip.setTextColor(if (activa) cAccent else cOff)
            chip.contentDescription = getString(
                R.string.cd_lens_chip, label,
                getString(if (activa) R.string.state_on else R.string.state_off)
            )
            markAsButton(chip)
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
                hint(getString(R.string.lens_min_one))
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
        mirror?.release()
        mirror = null
        try { autoScanner.close() } catch (e: Exception) {}
        scanBitmap?.recycle(); scanBitmap = null
        if (::controller.isInitialized) controller.jpegSink = null
        super.onDestroy()
    }

    // ==========================================================================
    //  HELPERS DE HUD: estado de chip, paneles, ranura central y avisos
    // ==========================================================================

    /**
     * Estado visual Y accesible de un chip, en un solo sitio.
     *
     * Antes cada chip repetía a mano tres líneas (setText, setTextColor con un
     * Color.parseColor literal y nada más), así que el color se escapaba en la mitad de
     * los sitios y NINGÚN chip decía qué era ni cómo estaba: un lector de pantalla leía
     * "alto voltaje off" donde tenía que decir "flash apagado", porque el texto
     * accesible del chip era literalmente el emoji.
     *
     * @param stateText estado en lenguaje humano para el lector de pantalla.
     * @param iconRes   icono monocromo; se tiñe con el mismo color que la letra, cosa
     *                  que con los emoji del sistema era sencillamente imposible.
     */
    private fun setChipState(
        chip: TextView,
        active: Boolean,
        cdRes: Int,
        stateText: String? = null,
        iconRes: Int = 0
    ) {
        val c = if (active) cAccent else cDim
        chip.setTextColor(c)
        chip.compoundDrawableTintList = ColorStateList.valueOf(c)
        if (iconRes != 0) chip.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        chip.isSelected = active
        // Sin letra no hay separación que reservar: TextView cuenta el drawablePadding
        // aunque el texto esté vacío y el icono quedaba descentrado a la izquierda en
        // los cinco chips de la barra superior, que son justo los que solo llevan icono.
        chip.compoundDrawablePadding = if (chip.text.isNullOrEmpty()) 0 else dp(5f).toInt()
        val estado = stateText
            ?: getString(if (active) R.string.state_on else R.string.state_off)
        chip.contentDescription = getString(cdRes, estado)
    }

    /** Un cambio de estado que no se anuncia no existe para quien usa TalkBack. */
    private fun announceChip(chip: TextView) {
        chip.contentDescription?.let { chip.announceForAccessibility(it) }
    }

    /** TalkBack leía los chips como texto suelto: ni que son botones ni si están
     *  puestos. Con esto se anuncian como botón conmutable y con su estado. */
    private fun markAsButton(v: View) {
        ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        })
    }

    private fun anyPanelVisible(): Boolean {
        val slot = binding.panelSlot
        for (i in 0 until slot.childCount) {
            if (slot.getChildAt(i).visibility == View.VISIBLE) return true
        }
        return false
    }

    /**
     * Exclusión mutua ESTRUCTURAL de paneles: solo uno visible, siempre.
     * Antes los cuatro paneles vivían a la misma cota (marginBottom=244dp) y la
     * exclusión se intentaba con ifs sueltos que ev_quick se saltaba en cada toque.
     */
    private fun showPanel(panel: View?) {
        val slot = binding.panelSlot
        for (i in 0 until slot.childCount) {
            val c = slot.getChildAt(i)
            c.visibility = if (c === panel && panel != null) View.VISIBLE else View.GONE
        }
        proOn = panel === binding.proPanel
        setChipState(binding.proToggle, proOn, R.string.cd_pro)
        setChipState(binding.chipLenses, panel === binding.lensPanel, R.string.cd_lenses)
        setChipState(binding.chipVid, panel === binding.videoPanel, R.string.cd_vid)
        setChipState(binding.chipMore, panel === binding.morePanel, R.string.cd_more)
    }

    /**
     * Ranura central única dentro del VISOR. Cuenta atrás, tarjeta QR y aviso de
     * apilado compartían el centro de la PANTALLA: con relación 1:1 salían enteros
     * sobre la franja negra, y un código detectado durante la cuenta atrás tapaba el
     * número. Ahora solo puede haber uno y siempre está sobre la imagen.
     */
    private fun showCenterSlot(v: View?) {
        val slot = binding.centerSlot
        for (i in 0 until slot.childCount) {
            val c = slot.getChildAt(i)
            c.visibility = if (c === v && v != null) View.VISIBLE else View.GONE
        }
    }

    private val hideHint = Runnable {
        binding.hintPill.animate().alpha(0f).setDuration(160)
            .withEndAction { binding.hintPill.visibility = View.GONE }.start()
    }

    /**
     * Aviso efímero pegado a los controles. Sustituye a los más de 20 Toast: en la
     * pantalla grande el Toast salía abajo del todo, lejísimos del chip que se acababa
     * de tocar, y tapaba el obturador justo cuando hacía falta. El Toast se reserva
     * ahora para errores de verdad (no se pudo guardar la foto).
     */
    private fun hint(text: String) {
        val v = binding.hintPill
        v.animate().cancel()
        v.text = text
        v.alpha = 1f
        v.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, 1700)
        v.announceForAccessibility(text)
    }

    private fun applyFlashChip() {
        val icon = when (flashMode) {
            0 -> R.drawable.ic_flash_off
            3 -> R.drawable.ic_torch
            else -> R.drawable.ic_flash_on
        }
        val estado = getString(
            when (flashMode) {
                1 -> R.string.state_auto
                2 -> R.string.state_on
                3 -> R.string.state_torch
                else -> R.string.state_off
            }
        )
        // Solo el modo AUTO necesita letra: el icono ya dice si está encendido o no.
        binding.chipFlash.text = if (flashMode == 1) "AUTO" else ""
        setChipState(binding.chipFlash, flashMode != 0, R.string.cd_flash, estado, icon)
    }

    private fun applyTimerChip() {
        binding.chipTimer.text = if (timerSec == 0) "" else "${timerSec}s"
        val estado = if (timerSec == 0) getString(R.string.state_off) else "$timerSec s"
        setChipState(binding.chipTimer, timerSec != 0, R.string.cd_timer, estado)
    }

    private fun applyResChip() {
        binding.chipRes.text = getString(if (fullRes) R.string.chip_full else R.string.chip_med)
        setChipState(
            binding.chipRes, !fullRes, R.string.cd_res, binding.chipRes.text.toString()
        )
    }

    /** Repinta de una vez los modos que son excluyentes entre sí. */
    private fun syncCaptureModeChips() {
        setChipState(binding.chipHdr, controller.hdrEnabled, R.string.cd_hdr)
        setChipState(binding.chipRaw, controller.rawEnabled, R.string.cd_raw)
        nightOn = controller.nightEnabled
        setChipState(binding.chipNight, nightOn, R.string.cd_night)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { ui.removeCallbacks(it) }
        countdownRunnable = null
        if (binding.countdown.visibility == View.VISIBLE) showCenterSlot(null)
        mirror?.setCountdown(null)
    }

    /**
     * Descripciones y objetivos táctiles de todo lo que se toca.
     * El visor concentraba cuatro gestos (enfoque, pellizco, doble toque y pulsación
     * larga) sin una sola acción accesible: en la pantalla interior son 692x716dp
     * completamente inservibles sin vista.
     */
    private fun setUpAccessibility() {
        ViewCompat.setAccessibilityDelegate(
            binding.gestureArea,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_focus_center, getString(R.string.cd_focus_center)
                        )
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_ae_lock, getString(R.string.cd_toggle_ae_lock)
                        )
                    )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: android.os.Bundle?
                ): Boolean = when (action) {
                    R.id.action_focus_center -> { focusCenter(); true }
                    R.id.action_ae_lock -> { toggleAeAfLock(); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        )

        // Todos los chips se anuncian como botón conmutable con su estado.
        arrayOf(
            binding.chipFlash, binding.chipTimer, binding.chipGrid, binding.chipNight,
            binding.chipMore, binding.chipHdr, binding.chipRaw, binding.chipRatio,
            binding.chipRes, binding.chipFilter, binding.chipLenses, binding.chipFlip,
            binding.chipMirror, binding.chipWa, binding.proToggle, binding.chipVid,
            binding.chipVres, binding.chipVfps, binding.chipVcodec, binding.chipTl,
            binding.chipEv, binding.chipIso, binding.chipVel, binding.chipWb,
            binding.chipK, binding.chipAuto, binding.tabPhoto, binding.tabVideo,
            binding.btnQrCopy, binding.btnQrOpen, binding.btnQrClose, binding.qrHint
        ).forEach { markAsButton(it) }
    }

    // ---- Ajustes de video ----
    private fun toggleVideoPanel() {
        showPanel(if (binding.videoPanel.visibility == View.VISIBLE) null else binding.videoPanel)
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
        setChipState(binding.chipVres, vresIndex != 0, R.string.cd_vres, vresLabels[vresIndex])
        binding.chipVfps.text = "${vfps}fps"
        setChipState(binding.chipVfps, vfps == 60, R.string.cd_vfps, "$vfps")
        binding.chipVcodec.text = if (vhevc) "HEVC" else "H264"
        setChipState(
            binding.chipVcodec, vhevc, R.string.cd_vcodec, binding.chipVcodec.text.toString()
        )
        setChipState(binding.chipTl, tlOn, R.string.cd_tl)
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
            hint(getString(R.string.hint_no_front))
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
        val etiqueta = when {
            camCycleIndex == 0 -> getString(R.string.lens_back)
            frontCount > 1 -> "${getString(R.string.lens_front)} $camCycleIndex"
            else -> getString(R.string.lens_front)
        }
        // El chip solo lleva el icono: el nombre de la cámara ya está en el chip de
        // lente, arriba en el visor, y repetirlo aquí obligaba a que la fila creciera
        // hasta 388dp y se comiera el botón de al lado.
        setChipState(binding.chipFlip, camCycleIndex != 0, R.string.cd_flip, etiqueta)
        announceChip(binding.chipFlip)
    }

    private fun startPhotoOrTimer() {
        if (capturing) return
        if (timerSec <= 0) {
            takePhoto()
            return
        }
        var remaining = timerSec
        cancelCountdown()
        binding.countdown.text = remaining.toString()
        showCenterSlot(binding.countdown)
        // La cuenta atrás también sale en la pantalla externa: si el espejo sirve para
        // hacerse un autorretrato, hay que ver desde fuera cuánto queda.
        mirror?.setCountdown(remaining.toString())
        val r = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    countdownRunnable = null
                    showCenterSlot(null)
                    mirror?.setCountdown(null)
                    takePhoto()
                } else {
                    binding.countdown.text = remaining.toString()
                    mirror?.setCountdown(remaining.toString())
                    ui.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = r
        ui.postDelayed(r, 1000)
    }

    // ---- PRO ----
    private fun togglePro() {
        showPanel(if (binding.proPanel.visibility == View.VISIBLE) null else binding.proPanel)
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
        binding.chipWb.contentDescription = getString(R.string.cd_wb, wbLabels[wbIndex])
        announceChip(binding.chipWb)
    }

    private fun selectKelvin() {
        if (!controller.hasManualWb) {
            hint(getString(R.string.hint_no_manual_wb))
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
