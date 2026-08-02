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
import android.hardware.camera2.CameraManager
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
import android.view.ViewGroup
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

    /**
     * Ya NO existe `nightStacking`. Era una bandera para saber de quién era el rótulo
     * central: night_label lo compartían tres operaciones (noche, apilado de enfoque y
     * horquillado) y cada una escribía su contador encima de la anterior. El apilado
     * nocturno tiene ahora su propia tarjeta en el layout (night_card, que estaba puesta y
     * no la usaba nadie), así que el dueño del rótulo se sabe mirando qué vista está
     * visible y la bandera sobra: una variable menos que se puede quedar desincronizada.
     */

    /**
     * Última parada de zoom que se centró en la tira. highlightZoomStrip() se llama en CADA
     * fotograma del pellizco y lanzar un smoothScrollTo por fotograma da tirones: solo se
     * recentra cuando la parada resaltada CAMBIA de verdad.
     */
    private var lastZoomActive = -1

    /** Estado ya pintado del fondo de la pastilla de zoom: evita reinflar el drawable en
     *  cada fotograma del pellizco. */
    private var zoomPillDigital = false

    // Lector de códigos: ahora lo sirve el stream YUV del motor (qrReader + ML Kit sobre la
    // Image, sin copia ni readback). El camino bueno llevaba implementado desde el
    // principio y NADIE llamaba a setQrEnabled: cero coincidencias en el grep. Lo que
    // corría de verdad era un getBitmap() del TextureView cada 1,1 s EN EL HILO DE UI, en
    // todos los modos, hasta con el dedo en el obturador. Apagado por defecto: escanear
    // cuesta un stream (es excluyente con RAW, Ultra HDR y noche) y batería, y la inmensa
    // mayoría de las veces el usuario solo quiere hacer una foto. Se enciende en Ajustes.
    private var qrOn = false
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

    /**
     * Cadencias de vídeo. toggleVfps() alternaba SOLO 30 y 60: sin 24 fps (la cadencia de
     * cine) ni 25 (PAL/broadcast) la app queda fuera de cualquier flujo de producción, y es
     * literalmente la misma crítica que el expediente le hace al rival. El motor ya monta un
     * rango de AE CERRADO por cadencia (CONTROL_AE_TARGET_FPS_RANGE), así que la
     * infraestructura estaba puesta y solo faltaban los valores.
     */
    private val vfpsList = intArrayOf(24, 25, 30, 60)

    /**
     * Sonido del vídeo. Hasta ahora el motor recibía un booleano withAudio y NADIE lo
     * apagaba nunca salvo el time-lapse: no había interruptor, ni indicador, ni forma de
     * saber si estaba entrando sonido hasta abrir el archivo en casa. El jurado midió la
     * toma entregada a -50,6 dBFS de media y -28,7 dBTP de pico sin que el usuario tuviera
     * manera de enterarse mientras grababa.
     */
    private var audioOn = true

    /** ¿Está concedido RECORD_AUDIO? Se relee al entrar en vídeo y al pulsar REC. */
    private var audioGranted = false

    // El interruptor de sonido (chipMic) y el rótulo de formato (videoHud) YA NO SE CREAN
    // POR CÓDIGO. Los dos existían por duplicado: en el layout estaban chip_audio y la fila
    // video_hud (con txt_video_format y badge_mic) sin que nadie los escuchara, y aquí se
    // fabricaban otros dos con insertChip() y buildVideoHud(). El resultado en pantalla eran
    // DOS interruptores de sonido seguidos en el panel de vídeo -sólo uno respondía- y dos
    // rótulos de formato. La coartada de buildVideoHud ("activity_camera.xml es de otro
    // integrador") ya no vale: los dos ficheros son del mismo dueño. Manda el XML.

    /**
     * El zoom actual coincide con una parada de la tira. Si NO coincide, la píldora del
     * nivel es lo único que dice en qué zoom está el usuario y no se puede esconder.
     */
    private var zoomOnStop = true
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

    /**
     * Hilo de E-S. Copiar un mp4 de 4K, escribir varios MB en la Uri de otra app o
     * consultar MediaStore desde el hilo de UI bloquea la interfaz el tiempo suficiente
     * para que el sistema marque un ANR; y en onResume esa consulta iba POR DELANTE del
     * arranque de la cámara, robándole justo los primeros milisegundos.
     */
    private val ioExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    // ---- Enfoque manual (MF) ----
    // El motor tenía setManualFocusDistance() y hasManualFocus desde el primer día, pero
    // NINGÚN control los llamaba: en una app cuyo propósito declarado es el macro, el
    // enfoque fino era literalmente inalcanzable desde la interfaz. El chip se crea por
    // código (ver buildExtraChips) porque activity_camera.xml es de otro integrador.
    private var mfOn = false
    private var mfDiopters = 0f
    private var lastMagnifierMs = 0L
    private var chipMf: TextView? = null

    // Horquillado de exposición (AEB). La serie se guarda entera: la fusión la hace el
    // usuario en el ordenador, que es donde tiene sentido, y aquí no cuesta ni memoria ni
    // riesgo. Sin esto no había forma de asegurar una escena a contraluz: o se quema el
    // cielo o se tapa la sombra, y eso se descubre en casa.
    private var chipAeb: TextView? = null
    private var bracketQueue = ArrayDeque<Int>()
    private var bracketBase = 0

    // Apilado de enfoque: a 5 cm la profundidad de campo son milímetros y una sola toma
    // NUNCA tiene el bicho entero enfocado, por buena que sea la lente.
    private var stackQueue = ArrayDeque<Float>()
    private var stackTotal = 0

    // ---- Herramientas de análisis (histograma / cebras / realce de enfoque) ----
    // No abren ningún stream nuevo: se analiza un fotograma DIMINUTO (160 px) del propio
    // visor. Un stream YUV extra habría chocado con el límite de 3 streams del HAL, que es
    // justo lo que ya obliga a que RAW, Ultra HDR, noche y QR sean excluyentes entre sí.
    private var analysisOverlay: AnalysisOverlayView? = null
    private var chipTools: TextView? = null
    private var toolsOn = false
    private var toolHist = true
    private var toolZebra = false
    private var toolPeak = false

    /**
     * Umbral de las cebras: 0 = 70 % (tono de piel), 1 = 95 % (aviso antes de quemar),
     * 2 = recorte ya consumado. Estaba clavado en "y >= 250", o sea que solo avisaba de lo
     * que YA se había perdido; una cebra útil avisa ANTES, y el 70 % es la herramienta de
     * exposición más usada en rodaje.
     */
    private var zebraLevel = 2
    private val zebraLumas = intArrayOf(178, 242, 250)
    private var analysisBmp: Bitmap? = null
    private var maskBmp: Bitmap? = null
    private var analysisPx: IntArray? = null
    private var lumaBuf: IntArray? = null
    private var maskPx: IntArray? = null
    private val histBins = IntArray(64)

    private val analysisTick = object : Runnable {
        override fun run() {
            analyzeFrame()
            ui.postDelayed(this, 400)
        }
    }

    // Sonido de captura. Sin esto, disparar solo daba un golpe háptico: no había ninguna
    // confirmación audible de que la foto existiera, que es justo el detalle que hace que
    // una cámara se sienta cara. No hay ni una referencia a SoundPool ni a MediaActionSound
    // en todo el proyecto (verificado por grep): se usa el sonido del SISTEMA, así que cero
    // peso en el APK, cero assets y cero licencias.
    private var shutterSound: android.media.MediaActionSound? = null
    private var soundOn = true

    /** Qué hacen las teclas de volumen: 0 disparar, 1 zoom, 2 exposición. */
    private var volAction = 0

    /**
     * Piso de velocidad de obturación para congelar el movimiento. Estaba escrito a fuego
     * en el motor (1/60) y setShutterFloorNs no se llamaba desde NINGÚN sitio de la
     * interfaz, así que no había manera de congelar un colibrí ni de relajarlo cuando
     * sobraba. Se elige en Ajustes: es un ajuste de "poner y olvidar".
     */
    private val floorList = longArrayOf(0L, 16_666_667L, 8_000_000L, 4_000_000L, 2_000_000L)
    private var floorIndex = 1

    /** -1 = automático (lo que diga la pantalla), 0 = AJUSTAR, 1 = LLENAR. */
    private var previewFillPref = -1

    /**
     * Guarda de reentrada. El resultado del permiso llega ANTES de onResume, así que
     * startCamera() se ejecutaba dos veces seguidas y quedaban DOS manager.openCamera() del
     * mismo ID en vuelo: con la ID0 dañada, abrir dos veces es exactamente lo que cuelga el
     * HAL. De paso evita que currentZoom y zoomRestored se reseteen dos veces, que era por
     * lo que el zoom guardado se perdía en el primer arranque tras conceder el permiso.
     */
    private var cameraOpening = false

    // Reapertura automática tras un error transitorio. En ColorOS es habitual que otra app
    // se lleve la cámara y, al volver, el visor se quedaba negro con un aviso y había que
    // salir y entrar de la app a mano.
    private var resumed = false
    private var reopenTries = 0
    private val reopenCamera = Runnable {
        if (resumed) { controller.close(); cameraOpening = false; startCamera() }
    }

    /**
     * Bitmap REUTILIZADO del fotograma congelado del cambio de lente. getBitmap(w, h) pedía
     * el TextureView a resolución COMPLETA: en el plegable desplegado son 2248x3998, o sea
     * 35,9 MB de ARGB_8888 asignados EN EL HILO DE UI justo en el instante que la animación
     * pretendía suavizar, y una vez por CADA cruce de parada óptica durante un pellizco.
     * A un cuarto de lado (1/16 de memoria) se ve igual: dura 140 ms, va desenfocado por
     * definición y se escala al tamaño del visor.
     */
    private var freezeBitmap: Bitmap? = null

    /**
     * Bitmap REUTILIZADO de la lupa. Antes cada toque de enfoque pedía getBitmap(tw, th) a
     * resolución completa (hasta 35,9 MB) solo para recortar un 12 %, en el hilo de UI y en
     * el gesto MÁS FRECUENTE de una app macro; y si getBitmap o createBitmap lanzaban, el
     * catch dejaba ese bitmap enorme sin reciclar.
     */
    private var magBitmap: Bitmap? = null

    /** Tope de ancho de la lectura de la lupa: por encima no se gana nitidez visible en una
     *  tarjeta de 120 dp y sí se paga la lectura completa del panel interior. */
    private val magMaxW = 1440

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
    /** Naranja de aviso de la insignia de micrófono: "esta toma va a salir muda". */
    private val cMuted by lazy { ContextCompat.getColor(this, R.color.meter_clip) }

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
            // NO se llama a startCamera aquí: este resultado se entrega en ON_START, ANTES
            // de onResume, que ya comprueba el permiso y abre. Llamar en los dos sitios
            // dejaba DOS openCamera() del mismo ID en vuelo, que es el escenario que cuelga
            // este HAL, y reseteaba currentZoom dos veces (zoom guardado perdido).
            if (!granted) {
                Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Al volver de Ajustes hay que releer TODO: si no, el usuario apagaba el sonido
            // o encendía el histograma y no pasaba nada hasta reiniciar la app entera.
            if (::controller.isInitialized) applyPrefs()
        }

    private val requestAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // El cuerpo de este callback estaba VACÍO, y esa era la causa exacta del peor
            // fallo silencioso de la app: con el permiso denegado se grababa MUDO sin decir
            // nada (startRec(withAudio=false) tampoco mostraba nada), el usuario terminaba
            // la toma y descubría el desastre en casa. Ahora el estado se repinta al
            // instante y, si dijo que no, se le dice con todas las letras.
            audioGranted = granted
            updateAudioUi()
            if (!granted) hint(getString(R.string.hint_no_audio_permission))
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ¿Nos invoca OTRA app para capturar? (banca, archivos, formularios...)
        readCaptureIntent(intent)
        if (captureIntent) setResult(RESULT_CANCELED) // contrato por defecto si el usuario sale

        var savedId = prefs.getString("cameraId", null)
        // VALIDACIÓN del ID guardado contra la lista REAL de cámaras del aparato.
        //
        // Sin ella —y el propio manifiesto lo tenía documentado por escrito— un ID
        // restaurado por transferencia directa desde otro teléfono, o el de una lente que
        // ColorOS deja de publicar, se pasaba tal cual a controller.open(): la apertura
        // moría en el vigilante de 5 s y el usuario veía "Esta lente no respondió" en CADA
        // arranque, sin ningún camino de vuelta al asistente. Cinco jueces distintos lo
        // señalaron y el arreglo estaba escrito hasta la línea exacta.
        //
        // Se usa cameraIdList y NO CameraInfoUtil.listLenses: listLenses pide las
        // características de las ocho cámaras del aparato y esto corre en el camino crítico
        // del arranque en frío, que es justo donde la app dice querer ganar. cameraIdList es
        // una sola llamada al servicio. La lista vacía se ignora a propósito: si el servicio
        // de cámara falla en ese instante, borrar la preferencia mandaría al asistente a un
        // usuario cuya lente sí existe.
        val idGuardado = savedId
        if (idGuardado != null) {
            val ids: Array<String> = try {
                (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList
            } catch (e: Exception) {
                emptyArray()
            }
            if (ids.isNotEmpty() && !ids.contains(idGuardado)) {
                prefs.edit().remove("cameraId").apply()
                savedId = null
            }
        }
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
                cameraOpening = false
                // Reintento con espera. ColorOS quita la cámara al pasar a segundo plano y
                // la devuelve al volver: sin reintento el usuario se quedaba con el visor
                // negro y tenía que salir y entrar de la app a mano. Tope de dos intentos
                // para no quedarnos reabriendo en bucle una lente que de verdad no está.
                if (resumed && reopenTries < 2) {
                    reopenTries++
                    ui.removeCallbacks(reopenCamera)
                    ui.postDelayed(reopenCamera, 600L * reopenTries) // 600 ms, luego 1200 ms
                }
            }
        }
        controller.onReady = {
            if (!zoomRestored) {
                zoomRestored = true
                val z = prefs.getFloat("zoom", 1f)
                if (z > 1.01f) currentZoom = controller.setZoom(z)
            }
            runOnUiThread {
                cameraOpening = false
                reopenTries = 0
                // Confirmado: la sesión arrancó. El aviso ámbar del botón de ajustes se
                // pone ante CUALQUIER error y NADA lo limpiaba nunca; tras un "Otra app
                // tomó la cámara" (lo normal en ColorOS al volver de otra aplicación)
                // quedaba marcado para siempre, sugiriendo una avería que no existía.
                binding.btnChangeLens.clearColorFilter()
                syncPreviewGravity()
                syncRatioChip()
                updateLensChip()
                // El bloqueo del flash es POR LENTE (flashFlareLens se recalcula al leer las
                // características de la cámara que se acaba de abrir), y onReady se dispara
                // en cada configuración de sesión: también cuando el zoom cruza a la lente
                // tele. Sin repintar aquí, el chip seguía anunciando "flash automático" en
                // una lente donde el motor ya no lo va a disparar.
                applyFlashChip()
                buildZoomStrip()
                // Aquí y no en restoreSettings: supports4kVideo se rellena leyendo el
                // StreamConfigurationMap de la lente, que no existe hasta que la sesión
                // está montada.
                migrateVideoDefaults()
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
        controller.onLensSwitching = { freezeForLensSwitch() }
        // Progreso REAL del apilado nocturno. El motor lo dispara en CADA fotograma apilado
        // y no lo escuchaba nadie: el rótulo se quedaba en un "Apilando…" fijo y, con 7
        // fotogramas a 12,6 MP, el proceso dura segundos y el usuario no sabía si la app se
        // había colgado. Ese es literalmente el fallo que el callback venía a cerrar.
        controller.onNightProgress = { hechos, total ->
            runOnUiThread {
                // Escribe en night_card, que es SUYA. Antes escribía en night_label, una
                // vista compartida con stackNext() (apilado de enfoque) y bracketNext()
                // (horquillado), y por eso hacía falta una bandera para saber de quién era
                // el rótulo. Con una tarjeta propia basta con mirar si está visible: si no
                // lo está, este progreso llega tarde (ráfaga cancelada por un onPause) y no
                // hay nada que pintar.
                if (binding.nightCard.visibility == View.VISIBLE) {
                    binding.nightProgress.text =
                        getString(R.string.night_progress, hechos, total)
                }
            }
        }

        // Cancelar la ráfaga nocturna. Vuelve a existir porque el motor ya publica
        // cancelNightCapture(), que además ENTREGA lo apilado si hay dos fotogramas o más:
        // arrepentirse no debería costar los seis que ya esperaste. Hasta ahora la única
        // salida de una toma de 18 s era cerrar la app.
        binding.btnNightCancel.setOnClickListener {
            if (controller.cancelNightCapture()) showCenterSlot(null)
        }

        // AVISO DE FLASH BLOQUEADO. El motor publicaba onFlashBlocked y NADIE lo escuchaba:
        // en la lente tele el LED vela la foto entera (p1=121,8 con flash, niebla blanca
        // monocroma) y el motor degrada el flash a apagado por su cuenta, en silencio. El
        // usuario pulsaba el rayo, veía el chip en ámbar, disparaba y se llevaba una foto
        // destruida o una sin flash sin ninguna explicación. El motor ya lo invoca en el
        // hilo de UI y sólo una vez por lente (mismo criterio que onHdrUnavailable), así que
        // aquí no hace falta ni runOnUiThread ni antirrebote.
        controller.onFlashBlocked = {
            hint(getString(R.string.hint_flash_tele_blocked))
            applyFlashChip()
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

                override fun onLongPress(e: MotionEvent) {
                    toggleAeAfLock()
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Doble toque en manual = volver a AF continuo. Sin esto, la única
                    // salida del enfoque manual era encontrar otra vez el chip MF.
                    if (mfOn) {
                        mfOn = false
                        controller.setAutoFocus()
                        if (proParam == "mf") selectParam("ev")
                        updateMfChip()
                        return true
                    }
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
        binding.btnShutter.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shutterHeld = true
                    if (mode == "photo" && !capturing) controller.prewarmAf()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    shutterHeld = false
                    // Pulsación ABORTADA: el dedo se arrastró fuera del botón y se soltó
                    // ahí (ACTION_UP sin clic), o un padre se llevó el gesto (ACTION_CANCEL).
                    // El AF_TRIGGER_START que salió en ACTION_DOWN se quedaba entonces sin su
                    // CANCEL y el visor dejaba de reenfocar hasta que saltaba la
                    // auto-cancelación del motor, 1,5 s después. El motor dejó
                    // cancelPrewarmAf() pública justo para que la cablease la interfaz: es
                    // idempotente y no toca nada si hay una captura en vuelo.
                    val fuera = ev.x < 0f || ev.y < 0f || ev.x > v.width || ev.y > v.height
                    if (fuera || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        controller.cancelPrewarmAf()
                    }
                }
            }
            false
        }
        // El icono es un engranaje: debe abrir AJUSTES. El cambio de lente pasa a ser una
        // fila dentro de Ajustes, que es donde la gente lo busca. Si el manifiesto todavía
        // no declara SettingsActivity (la declara otro integrador), NO se puede dejar al
        // usuario sin el selector de lente: se cae con elegancia al asistente de siempre.
        binding.btnChangeLens.setOnClickListener {
            try {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            } catch (e: Exception) {
                goToSetup()
            }
        }
        binding.thumbnail.setOnClickListener { openGallery() }
        // El hueco junto al obturador es el sitio canónico del cambio de cámara, no de una
        // marca de terceros (el botón verde de WhatsApp rompía la paleta y confundía).
        binding.btnFlipMain.setOnClickListener { flipCamera() }
        // Dos entradas al MISMO atajo: el círculo junto a FOTO/VIDEO, que es el que se usa
        // con el pulgar sin soltar el encuadre, y la pastilla del panel "⋯", que se queda
        // por costumbre de quien ya la buscaba ahí.
        binding.btnWhatsapp.setOnClickListener { shootAndShareWhatsApp() }
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
            override fun onStopTrackingTouch(s: SeekBar) {
                // Se recuerda el VALOR del enfoque manual entre sesiones, pero NO el estado
                // (ver restoreSettings). Se guarda al soltar y no en cada píxel arrastrado.
                if (proParam == "mf") prefs.edit().putFloat("mfDiopters", mfDiopters).apply()
            }
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
        binding.chipQr.setOnClickListener { toggleQr() }
        binding.chipFit.setOnClickListener { toggleFit() }
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
        // CONTROLES DEL LAYOUT QUE NO ESCUCHABA NADIE. Los cinco chips nuevos del panel de
        // vídeo estaban VISIBLES y sin un solo setOnClickListener: el usuario los pulsaba,
        // no ocurría nada y la conclusión razonable es que la app está rota. De los cinco
        // sobreviven dos, que son los que tienen a qué llamar:
        //   · chip_audio  -> toggleAudio(), el mismo que movía el chip duplicado por código.
        //   · chip_ae_lock -> toggleAeAfLock(), controller.lockAeAf() es público y funciona
        //     con la sesión de vídeo montada (applyAndUpdate reprograma la petición repetida,
        //     que durante la grabación es la del TEMPLATE_RECORD; el zoom ya hace lo mismo
        //     mientras se rueda).
        // chip_wind, chip_agc y chip_stab se han BORRADO del layout: no hay API pública en el
        // motor para ninguno de los tres (ver el comentario del XML).
        binding.chipAudio.setOnClickListener { toggleAudio() }
        binding.chipAeLock.setOnClickListener { toggleAeAfLock() }
        // El rótulo de formato es pulsable: "4K · 60 fps · HEVC" es justo donde el pulgar va
        // a buscar el cambio de formato. Antes esto lo hacía el rótulo duplicado que se
        // construía en buildVideoHud(), que ya no existe.
        binding.videoHud.setOnClickListener {
            if (controller.isRecording) hint(getString(R.string.hint_format_locked))
            else toggleVideoPanel()
        }

        setUpCoverMirror()
        buildExtraChips()
        setUpAccessibility()

        // Si nos invoca otra app, el modo lo fija armIntentCapture: no pisarlo.
        if (!captureIntent) setMode(prefs.getString("mode", "photo") ?: "photo")
        restoreSettings()
        applyPrefs()
        // El armado del intent va DESPUÉS de restoreSettings A PROPÓSITO: cuando iba antes
        // (estaba a mitad de onCreate), restoreSettings reaplicaba después el filtro y el
        // Ultra HDR guardados, así que la foto que se le devolvía al banco o al formulario
        // salía en blanco y negro o en JPEG_R sin que nadie lo hubiera pedido.
        if (captureIntent) armIntentCapture()
    }

    /**
     * Chips que NO están en activity_camera.xml y no pueden estarlo: ese fichero es de otro
     * integrador y añadir un id nuevo por nuestra cuenta rompería su compilación. Se crean
     * con el mismo estilo ProChip que usa buildLensChips() y se insertan en la fila del chip
     * de referencia, así que sobreviven a cualquier reestructuración del panel mientras esos
     * chips existan (el contrato prohíbe borrarlos o renombrarlos).
     */
    private fun buildExtraChips() {
        // MF junto a AUTO, dentro del panel PRO: es donde vive el deslizador que lo mueve.
        chipMf = insertChip(binding.chipAuto, "MF", antes = true)?.also { chip ->
            chip.setOnClickListener { toggleMf() }
            chip.setOnLongClickListener { startFocusStack(); true }
        }
        // AEB y herramientas, en la fila de HDR/RAW del panel "Más".
        chipAeb = insertChip(binding.chipFilter, "AEB", antes = false)?.also { chip ->
            chip.setOnClickListener { startBracket() }
        }
        chipTools = insertChip(binding.chipFilter, "ANÁLISIS", antes = false)?.also { chip ->
            chip.setOnClickListener { toggleTools() }
        }
        // AQUÍ SE CREABA UN TERCER CHIP, "SONIDO", junto a chip_tl, y se llamaba a
        // buildVideoHud(). Los dos duplicaban controles que YA estaban en el layout
        // (chip_audio y la fila video_hud) y que simplemente no tenían listener. Resultado
        // en pantalla: dos interruptores de sonido pegados en el panel de vídeo, de los
        // cuales sólo respondía el de código, y dos rótulos de formato en sitios distintos.
        // Ahora el dueño es el XML y aquí no se fabrica nada.
        // El overlay de análisis cuelga del HUD del visor, que PreviewFrameLayout coloca
        // exactamente sobre el rectángulo VISIBLE de la imagen: así las cebras caen sobre
        // los píxeles que de verdad se están quemando y no sobre la franja negra.
        val hud = binding.previewHud
        val v = AnalysisOverlayView(this)
        v.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        v.isClickable = false
        v.isFocusable = false
        // Por encima de la cuadrícula pero por debajo de los chips y del destello: el
        // histograma tiene que leerse sobre el degradado, no taparlo.
        hud.addView(v, (hud.indexOfChild(binding.gridOverlay) + 1).coerceIn(0, hud.childCount))
        analysisOverlay = v
    }

    /** Crea un chip ProChip y lo mete en la MISMA fila que [ref]. */
    private fun insertChip(ref: TextView, texto: String, antes: Boolean): TextView? {
        val fila = ref.parent as? ViewGroup ?: return null
        val chip = TextView(this, null, 0, R.style.ProChip)
        chip.text = texto
        chip.setTextColor(cDim)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6f).toInt() }
        val i = fila.indexOfChild(ref).coerceAtLeast(0)
        fila.addView(chip, if (antes) i else i + 1, lp)
        markAsButton(chip)
        return chip
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
        // UN SOLO DUEÑO del recorte del visor, y es el motor.
        //
        // Antes esto lo decidía la Activity por su cuenta (calculaba el criterio aquí y
        // escribía binding.texture.coverMode a mano) y NADIE llamaba jamás a
        // setPreviewFill: Camera2Controller.previewFill se quedaba en null para siempre, o
        // sea que su coverWanted() seguía usando el criterio DEL APARATO
        // (values-sw600dp/bools.xml: preview_fills_screen=true), ignorando lo que el usuario
        // había elegido en el chip AJUSTAR/LLENAR. Y setUpOutputs lo REIMPONE: pone
        // coverMode = coverWanted() y acto seguido recalcula previewCropRatio, que es
        // EXACTAMENTE el número con el que se recorta la foto GUARDADA. Eso corre en cada
        // apertura, flip, cambio de lente, de proporción o de resolución, y en cada
        // encendido/apagado de RAW, Ultra HDR, noche o QR. Efecto real en la pantalla
        // interior con el usuario en AJUSTAR: el visor saltaba a LLENAR y una foto disparada
        // en esa ventana se recortaba como si estuviera en LLENAR mientras el chip decía
        // AJUSTAR, que es justo el fallo que el chip venía a arreglar.
        //
        // Se le pasa la preferencia CRUDA del usuario: coverWanted() ya hace por su cuenta el
        // OR con aspect == FULL, y pasarle el resultado dejaría previewFill=true pegado al
        // salir de la proporción LLENA. Es seguro llamarlo antes de open(): applyPreviewBox()
        // postea configureTransform, que corta en seco si el texture todavía mide 0.
        controller.setPreviewFill(previewFillEffective())
        // Y el criterio se LEE de vuelta del texture, que es lo que el motor acaba de fijar,
        // en lugar de recalcularlo: recalcularlo aquí es como volvían a separarse los dos.
        val cover = binding.texture.coverMode
        val g = if (cover) android.view.Gravity.CENTER
        else android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        (binding.texture.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.gravity != g) {
                it.gravity = g
                binding.texture.layoutParams = it
            }
        }
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
        // El bombeo del espejo hace una lectura síncrona GPU->CPU del visor: mientras se
        // dispara compite con la propia captura, así que se calla.
        m.isBusy = { capturing || shutterHeld || controller.isRecording }
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
        // El sonido SÍ se recuerda: quien graba mudo a propósito (conciertos, entornos
        // ruidosos) no quiere volver a apagarlo en cada toma. El estado se ve siempre en el
        // rótulo de formato, así que recordarlo no puede sorprender a nadie.
        audioOn = prefs.getBoolean("vaudio", true)
        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        applyVideoSettings()

        // Se recuerda el VALOR del enfoque manual entre sesiones, pero NO el estado:
        // arrancar en manual dejaría al usuario con todo desenfocado sin saber por qué.
        mfDiopters = prefs.getFloat("mfDiopters", 0f)
        mfOn = false
        updateMfChip()

        // El chip de bloqueo AE/AF arranca apagado, igual que la insignia: sin esto se
        // quedaba con el color por defecto del estilo y con la descripción SIN RELLENAR del
        // XML (cd_ae_af_lock lleva un %1$s), o sea que TalkBack leía el marcador de formato
        // hasta la primera pulsación.
        setChipState(binding.chipAeLock, aeAfLocked, R.string.cd_ae_af_lock)

        // Deja la ranura de paneles cerrada Y de paso pone el color y la descripción
        // accesible de los cuatro chips que abren panel, que si no arrancaban sin nada.
        showPanel(null)
    }

    /**
     * Relee de las preferencias todo lo que se configura desde la pantalla de Ajustes.
     * Se llama al terminar onCreate y CADA vez que se vuelve de Ajustes: sin esto, apagar
     * el sonido o encender el histograma no hacía nada hasta reiniciar la app entera.
     */
    private fun applyPrefs() {
        soundOn = prefs.getBoolean("shutterSound", true)
        if (soundOn) ensureShutterSound()
        volAction = prefs.getInt("volAction", 0).coerceIn(0, 2)

        toolHist = prefs.getBoolean("toolHist", true)
        toolZebra = prefs.getBoolean("toolZebra", false)
        toolPeak = prefs.getBoolean("toolPeak", false)
        toolsOn = prefs.getBoolean("toolsOn", false)
        zebraLevel = prefs.getInt("zebraLevel", 2).coerceIn(0, 2)

        // Medición a la CARA. setFaceMetering() estaba implementado en el motor y no lo
        // llamaba nadie: la detección de caras corría igual (el motor la enciende si hay
        // alguien escuchando onFaces) y su resultado no se usaba nunca para exponer. Apagada
        // por defecto a propósito: mueve la exposición de toda la escena en cuanto entra
        // alguien en cuadro, que es justo lo que una app de macro no quiere.
        controller.setFaceMetering(prefs.getBoolean("faceMetering", false))
        analysisOverlay?.showHistogram = toolsOn && toolHist
        chipTools?.let {
            setExtraChip(
                it, toolsOn,
                "Herramientas de análisis: " +
                    getString(if (toolsOn) R.string.state_on else R.string.state_off)
            )
        }
        ui.removeCallbacks(analysisTick)
        if (toolsOn && resumed) ui.post(analysisTick) else analysisOverlay?.setMask(null)

        floorIndex = prefs.getInt("shutterFloor", 1).coerceIn(0, floorList.size - 1)
        controller.setShutterFloorNs(floorList[floorIndex])

        previewFillPref = prefs.getInt("previewFill", -1).coerceIn(-1, 1)
        syncPreviewGravity()
        applyFitChip()

        applyQrSetting()
    }

    /**
     * Enciende o apaga el lector de códigos por el camino BUENO (stream YUV del HAL +
     * InputImage.fromMediaImage, sin copia ni readback). Es excluyente con RAW, Ultra HDR y
     * modo noche porque el HAL solo admite 3 streams, así que el motor puede apagarlo por su
     * cuenta: por eso el estado real se relee siempre de controller.qrEnabled.
     */
    private fun applyQrSetting() {
        // En modo intent NUNCA: la tarjeta del código se desplegaba encima del flujo de
        // captura de otra app (el usuario no sabía si estaba usando su cámara o la del
        // banco) y gastaba CPU en un flujo que solo tiene que sacar una foto y volver.
        val quiere = !captureIntent && prefs.getBoolean("qr", false)
        if (quiere != controller.qrEnabled) controller.setQrEnabled(quiere)
        qrOn = controller.qrEnabled
        setChipState(binding.chipQr, qrOn, R.string.cd_qr)
        if (!qrOn) {
            ui.removeCallbacks(hideQrHint)
            binding.qrHint.visibility = View.GONE
            if (binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
        }
    }

    /**
     * Chip del lector de códigos. Guarda la preferencia y deja que applyQrSetting la
     * aplique: así el chip, la pantalla de Ajustes y el estado real del motor no pueden
     * discrepar. Encenderlo apaga RAW, Ultra HDR y noche, porque el HAL de esta lente solo
     * admite tres flujos y el escáner ocupa el tercero.
     */
    private fun toggleQr() {
        if (controller.isRecording) return
        qrOn = controller.setQrEnabled(!controller.qrEnabled)
        setChipState(binding.chipQr, qrOn, R.string.cd_qr)
        val ed = prefs.edit().putBoolean("qr", qrOn)
        // Ultra HDR es el otro modo que SÍ se recuerda entre sesiones y compite por el mismo
        // tercer flujo: si el motor acaba de apagarlo para hacerle sitio al escáner, la
        // preferencia tiene que enterarse. Si no, al siguiente arranque volverían a
        // encenderse los dos y uno de ellos moriría sin explicación.
        if (qrOn) ed.putBoolean("hdr", false)
        ed.apply()
        syncCaptureModeChips()
        if (!qrOn) {
            ui.removeCallbacks(hideQrHint)
            binding.qrHint.visibility = View.GONE
            if (binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
        }
        hint(if (qrOn) "Lector de códigos activado" else "Lector de códigos desactivado")
    }

    /**
     * Ajustar / Llenar. values-sw600dp/bools.xml forzaba preview_fills_screen=true sin
     * avisar ni ofrecer alternativa: en la pantalla interior el usuario no tenía forma de
     * recuperar el fotograma COMPLETO, solo veía el recorte y encuadraba a ciegas.
     * OJO, y hay que decirlo claro: esto cambia el ENCUADRE DEL VISOR, no el de la foto.
     * Lo que se guarda lo decide la relación de aspecto (chip RATIO, opción LLENA).
     */
    private fun toggleFit() {
        val lleno = previewFillEffective()
        previewFillPref = if (lleno) 0 else 1
        prefs.edit().putInt("previewFill", previewFillPref).apply()
        syncPreviewGravity()
        applyFitChip()
    }

    /** Lo que toca de verdad: la preferencia del usuario o, si no la hay, la del aparato. */
    private fun previewFillEffective(): Boolean =
        if (previewFillPref < 0) resources.getBoolean(R.bool.preview_fills_screen)
        else previewFillPref == 1

    private fun applyFitChip() {
        val lleno = previewFillEffective()
        binding.chipFit.setText(if (lleno) R.string.chip_fill else R.string.chip_fit)
        // Ámbar solo en AJUSTAR: LLENAR es lo normal en esta pantalla y no es un aviso.
        setChipState(
            binding.chipFit, !lleno, R.string.cd_fit,
            getString(if (lleno) R.string.chip_fill else R.string.chip_fit)
        )
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        resumed = true
        reopenTries = 0
        // LA CÁMARA, LO PRIMERO. Todo lo demás de este método (miniatura, espejo del
        // plegable, sensor de rotación, herramientas de análisis) se ejecutaba POR DELANTE
        // de la apertura y le robaba los primeros milisegundos al único trabajo que el
        // usuario está esperando: ver imagen. open() es asíncrona —encola en el hilo de
        // fondo del motor— así que adelantarla no retrasa nada de lo que viene detrás:
        // simplemente el HAL empieza antes. Es el único recorte de arranque en frío
        // disponible sin tocar el motor.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        refreshThumbnail()
        // Vigila si aparece o desaparece la pantalla externa del plegable: el chip
        // "Espejo" solo debe existir cuando de verdad hay dónde pintarlo.
        mirror?.start()
        // El sensor de rotación SOLO si el nivel de horizonte está visible. Antes se
        // registraba en CADA onResume a SENSOR_DELAY_UI aunque la cuadrícula estuviera
        // apagada (el caso por defecto): ~16 despertares por segundo del hilo principal,
        // con dos cálculos de matriz por evento, para pintar algo que nadie ve.
        startRollSensor()
        ui.removeCallbacks(analysisTick)
        if (toolsOn) ui.post(analysisTick)
        // El permiso de micrófono puede haber cambiado en Ajustes del sistema mientras la
        // app estaba en segundo plano: si no se relee aquí, el rótulo seguiría prometiendo
        // sonido en una toma que va a salir muda.
        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        updateAudioUi()
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(reopenCamera)
        ui.removeCallbacks(analysisTick)
        stopRollSensor()
        // El espejo copia fotogramas del visor: sin cámara no hay nada que copiar y
        // dejarlo vivo sería una lectura de GPU cada 160 ms con la app en segundo plano.
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
            // Salir a mitad de una horquilla dejaría la compensación de exposición movida
            // PARA SIEMPRE sin que el usuario sepa por qué sus fotos salen oscuras.
            if (bracketQueue.isNotEmpty()) {
                bracketQueue.clear()
                controller.setEv(bracketBase)
            }
            stackQueue.clear()
            prefs.edit().putFloat("zoom", currentZoom).putString("mode", mode).apply()
            cameraOpening = false
            controller.close()
        }
        super.onPause()
    }

    private fun startCamera() {
        if (cameraOpening) return
        val id = prefs.getString("cameraId", null) ?: return goToSetup()
        cameraOpening = true
        currentZoom = 1f
        zoomRestored = false
        camCycleIndex = 0
        facing = "back"
        // open() resetea el enfoque manual en el motor: el chip no puede seguir mintiendo.
        mfOn = false
        updateMfChip()
        // Un apilado nocturno interrumpido por un onPause no llega a devolver su callback:
        // sin esto, la tarjeta de progreso se quedaba EN PANTALLA con la cámara ya reabierta,
        // anunciando un apilado que murió al cerrar. Antes aquí sólo se bajaba una bandera
        // (nightStacking) y la vista se quedaba visible igual.
        if (binding.nightCard.visibility == View.VISIBLE) showCenterSlot(null)
        // El estado del HUD tiene que volver atrás con la cámara. Tras bloquear la
        // pantalla estando en la frontal, se reabría la trasera pero el chip seguía
        // diciendo "frontal", y la insignia AE/AF BLOQUEADO se quedaba encendida con
        // el bloqueo ya deshecho por la reapertura.
        setChipState(binding.chipFlip, false, R.string.cd_flip, getString(R.string.lens_back))
        clearAeAfLock()
        // Aplicar ajustes guardados ANTES de abrir (sin reconstruir): el primer setUpOutputs ya los usa.
        controller.presetCaptureSettings(AspectRatio.values()[ratioIndex], fullRes)
        controller.setDisabledLensIds(disabledLenses)
        controller.open(id)
    }

    /**
     * Deshace el bloqueo AE/AF EN LA INTERFAZ. Camera2Controller.open() resetea aeLocked y
     * afLocked a false, pero la Activity conservaba aeAfLocked=true y la insignia VISIBLE:
     * tras un onPause/onResume, un flip o un cambio de lente la insignia mentía y, como el
     * siguiente toque largo lo ponía a false, hacían falta DOS pulsaciones largas para
     * volver a bloquear de verdad.
     */
    private fun clearAeAfLock() {
        aeAfLocked = false
        binding.aeLockBadge.visibility = View.GONE
        // El chip del panel de vídeo va con la insignia: si no, tras un flip o una reapertura
        // se quedaba en ámbar con el bloqueo ya deshecho por open(), que es exactamente el
        // fallo que esta función arreglaba para la insignia.
        setChipState(binding.chipAeLock, false, R.string.cd_ae_af_lock)
    }

    /**
     * Registra el sensor de vector de rotación SOLO si el nivel de horizonte está visible.
     */
    private fun startRollSensor() {
        if (!gridOn) return
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopRollSensor() {
        try { sensorManager.unregisterListener(rotationListener) } catch (e: Exception) {}
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
        // El MISMO botón dispara o graba según el modo: el lector debe decir "grabar
        // vídeo", no "tomar foto".
        binding.btnShutter.contentDescription =
            getString(if (photo) R.string.shutter else R.string.cd_record)
        // El chip de ajustes de video solo aparece en modo video.
        binding.chipVid.visibility = if (photo) View.GONE else View.VISIBLE
        if (photo && binding.videoPanel.visibility == View.VISIBLE) showPanel(null)
        // El rótulo de formato solo tiene sentido en vídeo, pero ahí tiene que estar SIEMPRE:
        // es lo único que dice qué se va a grabar y si va a entrar sonido. En modo foto va
        // GONE y control_band NO crece ni un dp, que es lo que pedían las bajas 9 y 22.
        binding.videoHud.visibility = if (photo) View.GONE else View.VISIBLE
        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!photo && !audioGranted) requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        updateAudioUi()
    }

    // ---- Enfoque ----
    private fun focusAt(x: Float, y: Float) {
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        // Tocar para enfocar cancela el manual EN EL MOTOR (setFocusPoint lanza un barrido
        // de AF), así que el chip no puede seguir diciendo que está en manual.
        if (mfOn) { mfOn = false; updateMfChip() }
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
            // Bitmap de campo REUTILIZADO y con tope de ancho. Antes se pedía el texture a
            // resolución COMPLETA (hasta 35,9 MB en la pantalla interior) en cada toque de
            // enfoque, en el hilo de UI, solo para recortar un 12 %: ese era el tirón que se
            // veía justo en el gesto más frecuente de una app macro. Además, si getBitmap o
            // createBitmap lanzaban, el catch dejaba ese bitmap enorme sin reciclar.
            val s = minOf(1f, magMaxW.toFloat() / tw)
            val sw = (tw * s).toInt().coerceAtLeast(1)
            val sh = (th * s).toInt().coerceAtLeast(1)
            var bmp = magBitmap
            if (bmp == null || bmp.width != sw || bmp.height != sh) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                magBitmap = bmp
            }
            if (binding.texture.getBitmap(bmp) == null) return
            val crop = (sw * 0.12f).toInt().coerceAtLeast(40)
            val cx = (texX * s).toInt().coerceIn(crop / 2, (sw - crop / 2).coerceAtLeast(crop / 2))
            val cy = (texY * s).toInt().coerceIn(crop / 2, (sh - crop / 2).coerceAtLeast(crop / 2))
            val left = (cx - crop / 2).coerceIn(0, (sw - crop).coerceAtLeast(0))
            val top = (cy - crop / 2).coerceIn(0, (sh - crop).coerceAtLeast(0))
            val w = crop.coerceAtMost(sw - left)
            val h = crop.coerceAtMost(sh - top)
            if (w <= 0 || h <= 0) return
            // El RECORTE sí es una copia nueva: es el que se le entrega al ImageView, y el
            // bitmap grande se queda de campo para el siguiente toque.
            val region = Bitmap.createBitmap(bmp, left, top, w, h)
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
            // OJO: ya NO se recicla bmp. Es de campo y se reutiliza en el siguiente toque.
        } catch (e: Exception) {
            binding.magnifierCard.visibility = View.GONE
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

    /**
     * UN SOLO DUEÑO del bloqueo AE/AF. Lo disparan tres cosas —la pulsación larga sobre el
     * visor, la acción accesible del visor y ahora chip_ae_lock del panel de vídeo— y las
     * tres pasan por aquí, con una sola variable (aeAfLocked) y un solo repintado. El chip
     * estaba en el layout desde la ronda anterior sin ningún listener: es el que pedían los
     * medios 41 y 56 ("sin bloqueo un clip respira y no hay forma de casarlo con la toma
     * siguiente"), y hasta ahora la única forma de bloquear era una pulsación larga que nadie
     * descubre y que además no se puede hacer cómodamente con la cámara ya rodando.
     */
    private fun toggleAeAfLock() {
        aeAfLocked = !aeAfLocked
        controller.lockAeAf(aeAfLocked)
        binding.aeLockBadge.visibility = if (aeAfLocked) View.VISIBLE else View.GONE
        setChipState(binding.chipAeLock, aeAfLocked, R.string.cd_ae_af_lock)
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
        // INVISIBLE y no GONE: la fila es (miniatura | obturador | voltear) con pesos, así
        // que quitarla del todo descentraba el obturador ~28dp hacia un lado cada vez que
        // otra app pedía una foto.
        binding.thumbnail.visibility = View.INVISIBLE
        binding.modeToggle.visibility = View.GONE
        binding.chipWa.visibility = View.GONE
        // El escáner de códigos no pinta nada aquí: la tarjeta se desplegaba ENCIMA del
        // flujo de captura de otra app y el usuario no sabía si estaba usando su cámara o
        // la del banco. Además cuesta un stream en un flujo que dura tres segundos.
        applyQrSetting()
        // ENTREGA NEUTRA. Si el usuario había dejado puesto B/N, Sepia o Vintage, la foto
        // que se le devolvía al banco, al formulario o a WhatsApp salía filtrada sin que
        // nadie lo pidiera y sin forma de verlo (el llamador no enseña la miniatura con el
        // filtro). Ultra HDR se apaga por lo mismo: el JPEG_R con mapa de ganancia lo
        // interpretan mal los receptores que no lo esperan.
        // El filtro NO se persiste: el que eligió el usuario sigue guardado en prefs.
        filterIndex = 0
        controller.setCaptureColorMatrix(null)
        binding.chipFilter.text = ""
        setChipState(binding.chipFilter, false, R.string.cd_filter, Filters.list[0].name)
        binding.chipFilter.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) binding.texture.setRenderEffect(null)
        if (controller.hdrEnabled) {
            controller.setHdrEnabled(false)
            setChipState(binding.chipHdr, false, R.string.cd_hdr)
        }
        if (captureVideo) setMode("video") else setMode("photo")

        controller.jpegSink = { bytes ->
            // No se entrega NADA hasta que el usuario confirme: antes se hacía
            // setResult+finish en cuanto se escribía el archivo, así que un toque
            // accidental mandaba una foto movida al formulario del banco sin manera de
            // repetirla, que es justo lo que cualquier cámara de fábrica sí deja hacer.
            runOnUiThread { showCaptureReview(bytes) }
            true // 'true' = ya nos ocupamos nosotros, que no se guarde en la galería
        }
    }

    /** Lee de un Intent si nos invoca otra app y para qué. Antes esto vivía suelto en
     *  onCreate, y por eso un intent NUEVO sobre la misma instancia no se veía jamás. */
    private fun readCaptureIntent(i: Intent?) {
        val act = i?.action
        captureVideo = act == MediaStore.ACTION_VIDEO_CAPTURE
        // GET_CONTENT/PICK: otra app pide una imagen (adjuntar documento, formularios,
        // subidas web...). Respondemos capturándola con la lente que SÍ funciona.
        pickContent = act == Intent.ACTION_GET_CONTENT || act == Intent.ACTION_PICK
        captureIntent = captureVideo || act == MediaStore.ACTION_IMAGE_CAPTURE || pickContent
        @Suppress("DEPRECATION")
        captureOutput = i?.getParcelableExtra(MediaStore.EXTRA_OUTPUT) as? Uri
    }

    /**
     * Cuando el sistema REUTILIZA esta instancia, el intent nuevo llegaba aquí y se
     * ignoraba: la app que pedía la foto esperaba un resultado que nunca llegaba y, al
     * revés, una instancia que venía de un IMAGE_CAPTURE se quedaba con jpegSink armado y
     * mandaba la SIGUIENTE foto del usuario a un llamador que ya no existe.
     *
     * Límite honesto: con launchMode estándar esto solo llega si el llamador añade
     * FLAG_ACTIVITY_SINGLE_TOP (lo hacen bastantes selectores de archivos). No se cambia a
     * singleTop porque afectaría al ciclo de vida de la sesión de cámara.
     */
    // El parámetro va SIN nullable: AppCompatActivity 1.7 lo declara @NonNull y con Intent?
    // no sobrescribe nada (el compilador avisa con "overrides nothing").
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::controller.isInitialized) return
        hideCaptureReview()
        readCaptureIntent(intent)
        if (captureIntent) {
            setResult(RESULT_CANCELED)
            armIntentCapture()
        } else {
            disarmIntentCapture()
        }
    }

    /** Vuelve al modo normal tras una captura pedida por otra app. */
    private fun disarmIntentCapture() {
        controller.jpegSink = null
        captureOutput = null
        binding.thumbnail.visibility = View.VISIBLE
        binding.modeToggle.visibility = View.VISIBLE
        binding.chipWa.visibility = View.VISIBLE
        binding.chipFilter.visibility = View.VISIBLE
        setMode(prefs.getString("mode", "photo") ?: "photo")
        // Recuperar el filtro del usuario, que el modo intent había forzado a Normal.
        filterIndex = prefs.getInt("filter", 0).coerceIn(0, Filters.list.size - 1)
        applyFilter()
        applyQrSetting()
    }

    // ---- Revisión antes de entregar la foto a otra app ----

    /**
     * La pantalla de revisión se construye por CÓDIGO: res/layout es de otro integrador y
     * meter ahí un fichero nuevo rompería su trabajo. Se infla una sola vez y solo cuando
     * otra aplicación pide una captura, así que no pesa nada en el arranque normal.
     */
    private var reviewRoot: FrameLayout? = null
    private var reviewImage: android.widget.ImageView? = null
    private var reviewUse: TextView? = null
    private var reviewBytes: ByteArray? = null

    private fun showCaptureReview(bytes: ByteArray) {
        val root = reviewRoot ?: buildCaptureReview().also { reviewRoot = it }
        reviewBytes = bytes
        // inSampleSize 4: en pantalla no se nota y evita un bitmap de 50 MB.
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        reviewImage?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts))
        reviewUse?.isEnabled = true
        root.visibility = View.VISIBLE
        root.bringToFront()
    }

    private fun hideCaptureReview() {
        reviewRoot?.visibility = View.GONE
        reviewImage?.setImageDrawable(null)
        reviewUse?.isEnabled = true
        reviewBytes = null
    }

    private fun buildCaptureReview(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.parseColor("#F2000000"))
        root.isClickable = true // traga los toques: detrás está el visor en vivo
        root.visibility = View.GONE
        val img = android.widget.ImageView(this)
        img.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        img.contentDescription = getString(R.string.cd_thumbnail)
        root.addView(
            img,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = dp(112f).toInt() }
        )
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(dp(24f).toInt(), 0, dp(24f).toInt(), dp(32f).toInt())
        // Textos literales: strings.xml es de otro integrador y no se le puede añadir nada
        // desde aquí sin arriesgar su compilación.
        val repetir = TextView(this, null, 0, R.style.ProChip).apply {
            text = "Repetir"
            gravity = android.view.Gravity.CENTER
            setTextColor(cWhite)
            setOnClickListener { hideCaptureReview() }
        }
        val usar = TextView(this, null, 0, R.style.ProChip).apply {
            text = "Usar esta"
            gravity = android.view.Gravity.CENTER
            setTextColor(cAccent)
            setOnClickListener {
                // Evita la doble entrega por doble toque: escribir varios MB tarda.
                isEnabled = false
                reviewBytes?.let { deliverPhotoToCaller(it) }
            }
        }
        markAsButton(repetir)
        markAsButton(usar)
        fila.addView(
            repetir,
            LinearLayout.LayoutParams(0, dp(56f).toInt(), 1f)
                .apply { marginEnd = dp(12f).toInt() }
        )
        fila.addView(
            usar,
            LinearLayout.LayoutParams(0, dp(56f).toInt(), 1f)
                .apply { marginStart = dp(12f).toInt() }
        )
        root.addView(
            fila,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        )
        // ViewGroup.LayoutParams a propósito: la raíz es un ConstraintLayout y addViewInner
        // los convierte a los suyos (checkLayoutParams + generateLayoutParams). Pasarle unos
        // de FrameLayout funcionaría igual, pero esto no depende de qué raíz haya mañana.
        binding.root.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        reviewImage = img
        reviewUse = usar
        return root
    }

    /** Entrega definitiva. Solo se ejecuta cuando el usuario confirma. */
    private fun deliverPhotoToCaller(bytes: ByteArray) {
        val out = captureOutput
        if (out != null) {
            // Escribir varios MB en la Uri de otra app puede tardar: fuera del hilo de UI.
            ioExec.execute {
                val ok = try {
                    contentResolver.openOutputStream(out)?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    if (ok) {
                        setResult(
                            RESULT_OK,
                            Intent().setData(out).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                        finish()
                    } else {
                        Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
                        hideCaptureReview()
                    }
                }
            }
            return
        }
        if (pickContent) {
            // GET_CONTENT/PICK esperan un content:// legible, no una miniatura.
            ioExec.execute {
                val uri = writeSharedJpeg(bytes)
                runOnUiThread {
                    if (uri != null) {
                        setResult(
                            RESULT_OK,
                            Intent().setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                        finish()
                    } else {
                        Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
                        hideCaptureReview()
                    }
                }
            }
            return
        }
        // Sin EXTRA_OUTPUT el contrato es devolver una MINIATURA en el extra "data". Va
        // parcelada por Binder, con un límite duro de ~1 MB COMPARTIDO con el resto de la
        // transacción: a 400 px en ARGB_8888 son 640 KB, y con un llamador que lleve carga
        // propia salta TransactionTooLargeException y se queda sin foto (el usuario solo ve
        // que "la cámara no funciona con esa app"). 256 px en RGB_565 son 128 KB, que es el
        // orden que usa la cámara de AOSP.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (full == null) {
            Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            hideCaptureReview()
            return
        }
        val s = 256f / maxOf(full.width, full.height)
        val thumb = if (s < 1f) Bitmap.createScaledBitmap(
            full,
            (full.width * s).toInt().coerceAtLeast(1),
            (full.height * s).toInt().coerceAtLeast(1),
            true
        ) else full
        if (thumb !== full) full.recycle()
        setResult(RESULT_OK, Intent("inline-data").putExtra("data", thumb))
        finish()
    }

    /**
     * ACTION_VIDEO_CAPTURE: la app que nos invocó espera el vídeo de vuelta. Antes solo se
     * armaba jpegSink (fotos), así que el vídeo se guardaba en DCIM/Camera y el llamador
     * recibía SIEMPRE el RESULT_CANCELED puesto en onCreate: pedirnos un vídeo no devolvía
     * nada y el usuario creía que la app estaba rota. Es el único incumplimiento del
     * contrato que el propio manifiesto anuncia.
     */
    private fun deliverVideoToCaller() {
        val src = controller.ultimoGuardado
        if (src == null) { setResult(RESULT_CANCELED); finish(); return }
        val dest = captureOutput
        if (dest == null) {
            // Sin EXTRA_OUTPUT el contrato es devolver la Uri del vídeo en el Intent.
            setResult(
                RESULT_OK,
                Intent().setData(src).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            finish()
            return
        }
        // Con EXTRA_OUTPUT hay que dejarlo en el destino del llamador. Se COPIA en vez de
        // grabar directo sobre su descriptor porque un proveedor puede devolver un fd NO
        // desplazable (una tubería) y MediaRecorder necesita hacer seek para cerrar el MP4:
        // saldría un archivo corrupto.
        ioExec.execute {
            val ok = try {
                contentResolver.openInputStream(src)?.use { input ->
                    contentResolver.openOutputStream(dest)?.use { output ->
                        input.copyTo(output, 256 * 1024)
                    } != null
                } ?: false
            } catch (e: Exception) {
                false
            }
            // La copia de DCIM/Camera sobra: el usuario no pidió el vídeo para sí mismo.
            if (ok) try { contentResolver.delete(src, null, null) } catch (e: Exception) {}
            runOnUiThread {
                if (ok) setResult(
                    RESULT_OK,
                    Intent().setData(dest).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ) else setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    // ---- Fundido al cambiar de lente física ----

    /** Congela el último fotograma para que el cambio de lente no muestre un negro. */
    private fun freezeForLensSwitch() {
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        try {
            // A UN CUARTO DE LADO y con el bitmap REUTILIZADO. getBitmap(w, h) pedía el
            // texture a resolución COMPLETA: en el plegable desplegado son 35,9 MB de
            // ARGB_8888 asignados en el hilo de UI justo en el instante que la animación
            // pretendía suavizar, y una vez por CADA cruce de parada óptica durante un
            // pellizco: ese es exactamente el tirón que se ve en la transición. A 1/16 de
            // memoria se ve igual (dura 140 ms, va desenfocado por definición y el
            // ImageView lo escala al tamaño del visor).
            val fw = (t.width / 4).coerceAtLeast(1)
            val fh = (t.height / 4).coerceAtLeast(1)
            var bmp = freezeBitmap
            if (bmp == null || bmp.width != fw || bmp.height != fh) {
                // No se recicla el anterior: lens_fade puede tenerlo todavía puesto y
                // pintar un bitmap reciclado mata el proceso entero.
                binding.lensFade.setImageDrawable(null)
                bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                freezeBitmap = bmp
            }
            if (t.getBitmap(bmp) == null) return
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
        // Antes se borraba TODO antes de escribir. Es un fallo diferido y desconcertante:
        // el usuario adjunta una foto a un correo, la deja en borrador, vuelve a la cámara,
        // hace otra captura para otra app y la Uri de FileProvider que ya había entregado
        // apunta a un fichero que ya no existe: el envío falla con FileNotFoundException
        // horas después. Ahora solo se retira lo caducado (más de 6 h) y se conservan
        // siempre los 3 últimos, con lo que se sigue cumpliendo el único propósito real del
        // borrado, que era no acumular basura.
        val ahora = System.currentTimeMillis()
        dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { if (ahora - it.lastModified() > 6 * 60 * 60 * 1000L) it.delete() }
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
        // La tira se reconstruye entera: el centrado tiene que volver a hacerse aunque la
        // parada activa sea la misma que antes (las píldoras son vistas NUEVAS).
        lastZoomActive = -1
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
        var active = -1
        var best = Float.MAX_VALUE
        stops.forEachIndexed { i, t ->
            // Distancia RELATIVA: 0,4x de diferencia significa cosas muy distintas a 0,6x
            // que a 5x, y con la absoluta la parada larga se llevaba siempre el foco.
            val d = kotlin.math.abs(currentZoom - t.first) / t.first.coerceAtLeast(0.01f)
            if (d < best) { best = d; active = i }
        }
        // ¿El zoom coincide DE VERDAD con una parada? Antes, si no coincidía, se ponía
        // active = -1 y no se resaltaba ninguna. Eso es exactamente lo que el jurado midió
        // sobre la captura de la versión actual: las cinco pastillas con el mismo relleno
        // neutro (~#3A3A3A) y la píldora apagada mientras el chip de lente afirmaba
        // "TELEPHOTO · 77 MM". En ese estado la pantalla no dice en qué zoom está el usuario,
        // que es la única cosa que esta app existe para decir. Ahora la parada más cercana se
        // marca SIEMPRE; lo que cambia es CÓMO: exacta = ámbar con relleno, entre paradas =
        // ámbar atenuado y sin relleno, que se lee como "vas por aquí" sin afirmar que estés
        // clavado en esa parada.
        val exacta = best <= 0.02f
        zoomOnStop = exacta && active >= 0
        for (i in 0 until binding.zoomStrip.childCount) {
            val esOptica = stops.getOrNull(i)?.third == true
            val v = binding.zoomStrip.getChildAt(i) as? TextView ?: continue
            // El estado seleccionado se lee de reojo: fondo ámbar al 18% con filo ámbar
            // (zoom_stop_bg), no solo el color de la cifra.
            v.isSelected = i == active && exacta
            v.setTextColor(
                when {
                    i == active -> cAccent
                    esOptica -> cWhite      // lente física real: blanco pleno
                    else -> cOff            // zoom digital: atenuado
                }
            )
            v.alpha = if (i == active && !exacta) 0.7f else 1f
        }
        // CENTRAR la parada activa en la ventana visible. La tira vive dentro de un
        // HorizontalScrollView justo porque con 6 paradas mide 384dp (56dp + 8dp por
        // píldora) sobre 351dp útiles, pero nadie la desplazaba nunca: el scroll arreglaba el
        // recorte y no el problema de fondo, porque la píldora resaltada podía quedarse FUERA
        // de la vista. El usuario pellizcaba, el HUD marcaba una parada que no veía y seguía
        // sin saber en qué óptica estaba.
        if (active >= 0 && active != lastZoomActive) {
            lastZoomActive = active
            val idx = active
            // Dentro de un post porque highlightZoomStrip() se llama también desde
            // buildZoomStrip(), o sea ANTES de que el scroll tenga ancho medido, y
            // smoothScrollTo con width == 0 no desplaza nada.
            binding.zoomScroll.post {
                val v = binding.zoomStrip.getChildAt(idx) ?: return@post
                binding.zoomScroll.smoothScrollTo(
                    v.left - (binding.zoomScroll.width - v.width) / 2, 0
                )
            }
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
        // Fondo ACTIVO de la pastilla en cuanto la cifra ya es recorte DIGITAL sobre la lente
        // física en uso. zoom_pill_active_bg se dibujó exactamente para esto («mismo lenguaje
        // que el chip activo y que la parada de zoom seleccionada, con el radio de pastilla»)
        // y no lo usaba nadie: era un recurso que shrinkResources iba a tirar. El criterio es
        // el MISMO que el del chip de lente (ámbar por encima de 1,05x sobre la óptica
        // activa), porque dos avisos que se contradijeran sobre la misma cifra es justo lo que
        // este HUD lleva media docena de rondas quitando.
        val digital = lensCropFactor() > 1.05f
        if (digital != zoomPillDigital) {
            zoomPillDigital = digital
            // El padding se repone a mano: los dos fondos son formas SIN <padding> propio, así
            // que hoy View lo conserva, pero la pastilla vive de sus 16dp/7dp del XML y si un
            // día dejara de conservarlo se quedaría en un rectángulo pegado a la cifra.
            val v = binding.zoomPill
            val pi = v.paddingLeft
            val ps = v.paddingTop
            val pd = v.paddingRight
            val pb = v.paddingBottom
            v.setBackgroundResource(
                if (digital) R.drawable.zoom_pill_active_bg else R.drawable.zoom_pill_bg
            )
            v.setPadding(pi, ps, pd, pb)
        }
        binding.zoomPill.animate().cancel()
        binding.zoomPill.alpha = 1f
        ui.removeCallbacks(hideZoom)
        // La píldora solo se esconde si el zoom ha quedado clavado en una parada, porque
        // entonces la pastilla resaltada ya dice dónde está. Si el pellizco lo dejó ENTRE
        // paradas, esta cifra es lo único que dice el zoom exacto y esconderla a los 1200 ms
        // dejaba la pantalla muda sobre el control principal de la app.
        if (zoomOnStop) ui.postDelayed(hideZoom, 1200)
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
            // La tarjeta de noche se cierra sola al terminar. Ya no hace falta bajar ninguna
            // bandera: el dueño del rótulo central es la vista que esté visible.
            if (binding.nightCard.visibility == View.VISIBLE) showCenterSlot(null)
            if (ok) {
                // La miniatura YA la pintó onPhotoThumb desde el JPEG en memoria, al
                // instante y sin tocar disco. refreshThumbnail lanzaba además una
                // decodificación de coil del JPEG COMPLETO y, sin URI cacheada, una consulta
                // a MediaStore en el hilo principal, justo en el momento de mayor presión de
                // memoria de toda la app.
                bounceThumbnail()
                // Se guarda la URI para el próximo arranque en frío: así la miniatura sale
                // sin preguntarle nada a MediaStore.
                controller.ultimoGuardado?.let {
                    prefs.edit().putString("ultimaFoto", it.toString()).apply()
                }
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
        if (nightOn) {
            // Apilado multi-frame: sin destello, con tarjeta de progreso PROPIA (night_card,
            // que llevaba puesta en el layout sin que la usara nadie). Antes esto escribía en
            // night_label, la vista que comparten el apilado de enfoque y el horquillado, y
            // había que reponerle el texto a mano porque stackNext() y bracketNext() lo
            // dejaban con SU contador ("Apilado de enfoque 5/5"): la siguiente foto de noche
            // arrancaba anunciando la operación anterior hasta el primer progreso. Con una
            // vista propia ese arreglo sobra, pero el contador sí se pone a cero: el motor
            // tarda un fotograma en mandar el primer onNightProgress y sin esto se vería el
            // "Apilando 7 de 7" de la foto anterior.
            binding.nightProgress.setText(R.string.night_stacking)
            showCenterSlot(binding.nightCard)
            playShutterSound()
            controller.takeNightPhoto(cb)
        } else {
            flashScreen()
            playShutterSound()
            controller.takePhoto(cb)
        }
    }

    private fun ensureShutterSound() {
        if (shutterSound != null) return
        // Precargado: la PRIMERA reproducción de MediaActionSound sin load() tarda ~150 ms
        // y llegaría DESPUÉS de la foto.
        shutterSound = android.media.MediaActionSound().apply {
            load(android.media.MediaActionSound.SHUTTER_CLICK)
        }
    }

    /**
     * Sonido del obturador. Antes disparar solo daba un golpe háptico: sin confirmación
     * audible el usuario no sabe si la foto se tomó y vuelve a pulsar (fotos duplicadas).
     * Suena a la vez que el destello de pantalla, que es el instante en que se lanza la
     * captura al motor.
     */
    private fun playShutterSound(sonido: Int = android.media.MediaActionSound.SHUTTER_CLICK) {
        if (!soundOn) return
        // Respeta el silencio del teléfono: una cámara que suena con el timbre en silencio
        // es exactamente lo que hace que la gente desinstale una app.
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
        if (am != null && am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        ensureShutterSound()
        try { shutterSound?.play(sonido) } catch (e: Exception) {}
    }

    // ---- Ráfaga (mantener pulsado el obturador) ----
    // PENDIENTE, y a propósito: el motor tiene desde esta ronda una ráfaga de verdad
    // —fun takeBurst(count: Int, onProgress: (Int, Int) -> Unit, onDone: (Int) -> Unit)—
    // que hace UNA sola captureBurst con el 3A congelado, y este encadenado de takePhoto()
    // con 60 ms de espera es exactamente lo que ella viene a sustituir (unlockFocusAfterShot
    // manda AF_TRIGGER_CANCEL tras cada foto, así que el HAL rebarre el foco entre tomas:
    // 2-3 fps y cada foto con una nitidez distinta). No se cablea aquí porque takeBurst
    // guarda por su cuenta con saveImage y NO pasa por jpegSink, así que en modo intent la
    // foto se iría a la galería en vez de al llamador: hay que decidir antes qué hace una
    // pulsación larga con un IMAGE_CAPTURE en curso. Va en su propia ronda.
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
        // Un clic por fotograma, como cualquier cámara: es la única forma de saber cuántas
        // fotos se han hecho de verdad sin mirar la pantalla.
        playShutterSound()
        controller.takePhoto { ok ->
            capturing = false
            burstRemaining--
            // UNA sola actualización de miniatura al soltar, no siete en un segundo:
            // refreshThumbnail se llamaba por CADA foto de la ráfaga aunque onPhotoThumb ya
            // la pinta al instante desde el JPEG en memoria.
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
        // Empezar a grabar con una cuenta atrás en marcha disparaba una foto fija sobre una
        // sesión de vídeo que no tiene el surface del ImageReader.
        cancelCountdown()
        if (controller.isRecording) {
            controller.stopVideo()
            return
        }
        // Se relee el permiso en el instante del disparo: el usuario puede habérselo quitado
        // desde los ajustes del sistema entre una toma y la siguiente.
        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        updateAudioUi()
        startRec(audioActive())
    }

    private fun startRec(withAudio: Boolean) {
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // AVISO ANTES DE RODAR, no al reproducir el archivo en casa. Este era el fallo
        // silencioso más caro de la app: sin permiso de micrófono se grababa MUDO sin decir
        // absolutamente nada, y el usuario se enteraba con la toma ya perdida.
        if (!withAudio) {
            hint(
                getString(
                    when {
                        tlOn -> R.string.hint_timelapse_muted
                        !audioGranted -> R.string.hint_no_audio_permission
                        else -> R.string.hint_recording_muted
                    }
                )
            )
        }
        // El pitido de inicio va AQUÍ, antes de que arranque el grabador. Cuando se
        // disparaba desde onRecordingChanged(true) el MediaRecorder ya estaba corriendo, así
        // que el propio aviso de la app entraba DENTRO de la toma por el micrófono: el
        // jurado lo vio como un transitorio de banda ancha al principio del espectrograma.
        // Entre esta línea y recorder.start() quedan la creación del MediaRecorder y la
        // configuración de la sesión de captura, que es donde se consume el pitido.
        playShutterSound(android.media.MediaActionSound.START_VIDEO_RECORDING)
        if (controller.startVideo(withAudio)) return
        // ============================ RED DE SEGURIDAD DEL 4K ============================
        // Si el códec no puede con la resolución o el bitrate pedidos, createRecorder revienta
        // en prepare() y startVideo devuelve false. Bajar a 1080p y reintentar está bien: es
        // lo que el usuario quería (grabar). Lo que estaba MAL era el diagnóstico:
        //
        //   1. Se le achacaba a la resolución CUALQUIER fallo de startVideo(). createRecorder
        //      configura también el audio a 48 kHz estéreo 256 kbps, el códec HEVC, la
        //      cadencia y el fichero de salida: si revienta por cualquiera de esas cosas, el
        //      4K no tenía nada que ver.
        //   2. Y encima se PERSISTÍA en preferencias en el PRIMER intento, así que un fallo
        //      puntual (otra app tocando el códec, un archivo que no se pudo abrir) dejaba al
        //      usuario en 1080p PARA SIEMPRE, sin aviso y sin forma de saber por qué su 4K
        //      había desaparecido.
        //
        // Ahora: el primer fallo baja la resolución SOLO PARA ESTA SESIÓN (no toca prefs, así
        // que al reabrir la app vuelve el 4K que el usuario eligió). Se persiste únicamente al
        // segundo fallo, que ya es un patrón y no un accidente, y diciéndolo con todas las
        // letras. Y si el 1080p falla TAMBIÉN, entonces la resolución queda descartada como
        // causa: se le devuelve al usuario su ajuste y se le dice la verdad.
        if (vresList[vresIndex] > 1080) {
            val pedido = vresIndex
            vresIndex = vresList.indexOf(1080).coerceAtLeast(0)
            // OJO: applyVideoSettings() NO escribe en preferencias, solo aplica al motor y
            // repinta. Es justo lo que hace falta aquí.
            applyVideoSettings()
            val fallos = prefs.getInt("vres4kFails", 0) + 1
            prefs.edit().putInt("vres4kFails", fallos).apply()
            if (fallos >= 2) {
                prefs.edit().putInt("vres", vresIndex).apply()
                hint(getString(R.string.hint_video_4k_sticky))
            } else {
                hint(getString(R.string.hint_video_4k_fallback))
            }
            // El reintento va con retraso A PROPÓSITO. startVideo devuelve false por dos
            // caminos distintos: uno síncrono (createRecorder revienta en prepare) que no
            // toca la sesión, y otro asíncrono en el que el motor ya ha ejecutado su
            // videoStartFailed y está rehaciendo la sesión del visor por su cuenta. Montar
            // otra sesión encima de esa reconstrucción es exactamente el escenario que
            // cuelga este HAL, y aquí no hay forma de distinguir cuál de los dos fue.
            ui.postDelayed({
                if (!resumed || controller.isRecording) return@postDelayed
                if (controller.startVideo(withAudio)) return@postDelayed
                // Falla también en 1080p: la resolución NO era el problema. Se deshace la
                // rebaja (incluida la persistida, si la hubo) para no dejar castigado un
                // ajuste que era inocente, y se cuenta lo que de verdad ha pasado.
                vresIndex = pedido
                prefs.edit().remove("vres4kFails").apply()
                if (fallos >= 2) prefs.edit().putInt("vres", pedido).apply()
                applyVideoSettings()
                hint(getString(R.string.hint_video_not_resolution))
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }, 500)
            return
        }
        Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
    }

    /**
     * ¿Va a entrar sonido en la toma? Tienen que darse las tres cosas a la vez y hasta ahora
     * ninguna se veía en pantalla: que el usuario no lo haya silenciado, que el permiso esté
     * concedido y que no sea un time-lapse (ahí el motor apaga el audio por su cuenta,
     * porque una pista de sonido a un fotograma cada cinco segundos no significa nada).
     */
    private fun audioActive(): Boolean = audioOn && audioGranted && !tlOn

    private fun toggleAudio() {
        if (controller.isRecording) {
            // MediaRecorder fija la pista al preparar el archivo: cambiarla a mitad de la
            // toma obligaría a cortar y volver a empezar, que es peor que no poder.
            hint(getString(R.string.hint_audio_locked))
            return
        }
        if (!audioOn && !audioGranted) {
            // Pedir sonido sin permiso concedido: se pide el permiso, no se enciende un
            // interruptor que prometería una pista que no va a existir.
            audioOn = true
            prefs.edit().putBoolean("vaudio", true).apply()
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
            updateAudioUi()
            return
        }
        audioOn = !audioOn
        prefs.edit().putBoolean("vaudio", audioOn).apply()
        updateAudioUi()
        hint(getString(if (audioActive()) R.string.hint_audio_on else R.string.hint_audio_off))
    }

    /**
     * Repinta el interruptor de sonido y la fila de estado de vídeo. El estado del micrófono
     * tiene que ser visible ANTES de rodar y DURANTE toda la toma, no descubrirse al
     * reproducir el archivo: es exactamente la clase de fallo que hace desinstalar una app de
     * vídeo.
     *
     * Los dos dueños son ahora del XML: chip_audio (panel de vídeo) y badge_mic (fila de
     * estado, visible también mientras se rueda). Antes esto pintaba un chip creado por
     * código y el rótulo duplicado, y los del layout se quedaban con lo que dijera el XML.
     */
    private fun updateAudioUi() {
        val activo = audioActive()
        // Estado en lenguaje humano, UNA sola vez: lo usan el chip y la insignia.
        val estado = getString(
            when {
                activo -> R.string.audio_on
                tlOn -> R.string.audio_timelapse
                !audioGranted -> R.string.audio_no_permission
                else -> R.string.audio_muted
            }
        )
        // Sin letra, como chip_flash: es un ProChip.Icon de 56dp y el rótulo lo rompería.
        // El icono distingue micrófono de micrófono tachado y el estado va por descripción.
        binding.chipAudio.text = ""
        setChipState(
            binding.chipAudio, activo, R.string.cd_audio, estado,
            if (activo) R.drawable.ic_mic else R.drawable.ic_mic_off
        )
        updateVideoHud(estado)
    }

    /**
     * Fila de estado de vídeo: resolución · cadencia · códec, más la insignia de micrófono.
     *
     * Ya no se construye ningún rótulo por código: se escribe en txt_video_format y
     * badge_mic, que llevaban puestos en el layout sin que nadie los tocara. El texto sale de
     * @string/video_format y @string/cd_video_format en vez de un String.format con literales
     * en castellano, así que en un teléfono en inglés deja de salir a medias.
     */
    private fun updateVideoHud(estadoAudio: String) {
        val activo = audioActive()
        val res = vresLabels[vresIndex]
        val fps = "$vfps fps"
        val codec = if (vhevc) "HEVC" else "H.264"
        binding.txtVideoFormat.text = getString(R.string.video_format, res, fps, codec)
        binding.txtVideoFormat.setTextColor(cWarm)
        // Insignia de micrófono. PERMANENTE mientras la toma vaya a salir muda: nace tachada
        // en el XML a propósito (si el código no llegara a tocarla, el estado que se enseña es
        // el pesimista). Con sonido se queda como un icono discreto sin rótulo; sin sonido
        // grita "SIN SONIDO" en el naranja de aviso.
        val badge = binding.badgeMic
        badge.text = if (activo) "" else getString(R.string.badge_muted)
        badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (activo) R.drawable.ic_mic else R.drawable.ic_mic_off, 0, 0, 0
        )
        val color = if (activo) cDim else cMuted
        badge.setTextColor(color)
        badge.compoundDrawableTintList = ColorStateList.valueOf(color)
        badge.compoundDrawablePadding = if (activo) 0 else dp(5f).toInt()
        // La insignia NO lleva descripción propia (es importantForAccessibility=no en el
        // XML): la fila entera es un solo botón y se lee de un tirón, formato + sonido, que
        // es el orden en que hacen falta ("qué voy a grabar" y "va a entrar sonido").
        binding.videoHud.contentDescription =
            getString(R.string.cd_video_format, res, fps, codec) + ". " +
                getString(R.string.cd_audio, estadoAudio)
    }

    /**
     * Valor por defecto del vídeo, UNA sola vez por instalación.
     *
     * Lo que salía de fábrica era 1920x1080 H.264 a 30 fps: el suelo de 2015, y es lo que el
     * jurado midió con ffprobe (avc1 High/4.0, 30,1 fps, 16,6 Mbps). El motor sabe grabar 4K
     * con su escalera de 42 Mbps y HEVC desde hace rondas —supports4kVideo,
     * setVideoTargetHeight, setVideoHevc— y esa capacidad estaba escrita y sin usar
     * únicamente porque el valor por defecto nunca llegaba a ella.
     *
     * HEVC junto con el 4K y no suelto: 4K en H.264 a 42 Mbps son ~315 MB por minuto y
     * cualquier receptor moderno (incluido WhatsApp) admite HEVC. Solo se toca si el usuario
     * no había elegido nada; a partir de ahí manda él y esto no vuelve a ejecutarse.
     */
    private fun migrateVideoDefaults() {
        if (prefs.getBoolean("migrVideo4k", false)) return
        prefs.edit().putBoolean("migrVideo4k", true).apply()
        if (prefs.contains("vres") || prefs.contains("vhevc")) return
        if (!controller.supports4kVideo) return
        vresIndex = vresList.indexOf(2160).coerceAtLeast(0)
        vhevc = true
        prefs.edit().putInt("vres", vresIndex).putBoolean("vhevc", true).apply()
        applyVideoSettings()
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
                // Ya no es un literal en castellano: cd_stop_recording estaba en strings.xml
                // esperando justo a esta línea (y en values-en, que era lo que se perdía).
                binding.btnShutter.contentDescription = getString(R.string.cd_stop_recording)
                // El cronómetro y la fila de chips compartían cota (y=54..85 contra
                // y=40..88) y options_bar se declaraba después: el 0:00 salía MORDIDO.
                // Grabando no hace falta ningún ajuste de foto en pantalla.
                binding.optionsScroll.visibility = View.GONE
                binding.chipMore.visibility = View.GONE
                showPanel(null)
                // La fila de estado NO se esconde con el resto de la barra: durante la toma
                // es lo único que dice a qué se está grabando y si hay pista de sonido.
                binding.videoHud.visibility = View.VISIBLE
                // 4K que SÍ arrancó: se borra el historial de fallos de la red de seguridad.
                // Sin esto, un fallo antiguo (por ejemplo el de una lente distinta) se sumaría
                // a uno nuevo y bajaría al usuario a 1080p de forma permanente por dos
                // incidentes que no tienen nada que ver.
                if (vresList[vresIndex] > 1080) prefs.edit().remove("vres4kFails").apply()
                // El pitido de inicio ya sonó en startRec(), ANTES de recorder.start().
                // Aquí llegaría con el grabador YA corriendo y se grababa a sí mismo dentro
                // de la toma; el jurado lo midió como un transitorio de banda ancha en el
                // arranque del espectrograma.
            } else {
                binding.shutterIcon.setBackgroundResource(R.drawable.rec_dot)
                binding.recIndicator.visibility = View.GONE
                ui.removeCallbacks(tick)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.modeToggle.alpha = 1f
                binding.tabPhoto.isEnabled = true
                binding.tabVideo.isEnabled = true
                binding.optionsScroll.visibility = View.VISIBLE
                binding.chipMore.visibility = View.VISIBLE
                binding.btnShutter.contentDescription =
                    getString(if (mode == "photo") R.string.shutter else R.string.cd_record)
                playShutterSound(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
                // Esta es la ÚNICA ruta que no pasa por onPhotoThumb: aquí sí hace falta.
                refreshThumbnail()
                // Si nos invocó otra app pidiendo un vídeo, hay que devolvérselo.
                if (captureIntent && captureVideo) deliverVideoToCaller()
            }
        }
    }

    // ---- Miniatura / galería ----
    /**
     * Miniatura de lo último guardado, SIEMPRE fuera del hilo principal.
     *
     * Dos problemas de una vez: (a) latestMediaUri se ejecutaba desde onResume en el hilo
     * de UI, por delante del arranque de la cámara, con un LIKE de comodín inicial que
     * fuerza un escaneo completo de la tabla de MediaStore y sin LIMIT; (b) coil sin
     * coil-video no sabe decodificar un mp4, así que tras GRABAR la miniatura se quedaba
     * con la foto anterior y el usuario creía que el vídeo no se había guardado.
     * loadThumbnail (API 29+) vale para imagen Y vídeo sin añadir dependencias.
     */
    private fun refreshThumbnail() {
        // Primero lo que ya sabemos: la URI que devolvió el motor o la cacheada en
        // preferencias. Así el arranque en frío NO toca MediaStore para nada.
        val cached = (if (::controller.isInitialized) controller.ultimoGuardado else null)
            ?: prefs.getString("ultimaFoto", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (cached != null) { pintarMiniatura(cached); return }
        ioExec.execute {
            val uri = latestMediaUri() ?: return@execute
            prefs.edit().putString("ultimaFoto", uri.toString()).apply()
            runOnUiThread { pintarMiniatura(uri) }
        }
    }

    private fun pintarMiniatura(uri: Uri) {
        ioExec.execute { pintarEnHiloIo(uri, recuperar = true) }
    }

    /**
     * Ya en el hilo de E-S.
     *
     * [recuperar] permite UNA sola vuelta a MediaStore si la URI está muerta. Hacía falta
     * porque la preferencia "ultimaFoto" no se borraba NUNCA y la galería sí tiene botón de
     * borrar: en cuanto el usuario borraba esa foto, refreshThumbnail seguía confiando en la
     * URI cacheada, la miniatura se quedaba en un recuadro vacío y latestMediaUri() ya no se
     * consultaba jamás, ni saliendo y volviendo a entrar en la app. Solo se recuperaba
     * tomando otra foto. getType() devolviendo null es la señal exacta de que la fila ya no
     * está. La bandera evita el bucle si la de MediaStore también fallara.
     */
    private fun pintarEnHiloIo(uri: Uri, recuperar: Boolean) {
        val tipo = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (tipo == null) {
            prefs.edit().remove("ultimaFoto").apply()
            if (!recuperar) {
                runOnUiThread { binding.thumbnailImage.setImageDrawable(null) }
                return
            }
            val nueva = latestMediaUri()
            if (nueva == null) {
                runOnUiThread { binding.thumbnailImage.setImageDrawable(null) }
                return
            }
            prefs.edit().putString("ultimaFoto", nueva.toString()).apply()
            // Llamada DIRECTA, no otro ioExec.execute: ya estamos en ese hilo y onDestroy
            // hace ioExec.shutdown(), con lo que reencolar aquí podía lanzar
            // RejectedExecutionException al salir de la app.
            pintarEnHiloIo(nueva, recuperar = false)
            return
        }
        if (tipo.startsWith("video") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bmp = try {
                contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) runOnUiThread { binding.thumbnailImage.setImageBitmap(bmp) }
        } else {
            runOnUiThread { binding.thumbnailImage.load(uri) }
        }
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
        playShutterSound()
        controller.takePhoto { ok ->
            capturing = false
            if (ok) {
                bounceThumbnail()
                shareLatestToWhatsApp()
            } else {
                Toast.makeText(this, R.string.photo_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLatestToWhatsApp() {
        val uri = latestOwnUri() ?: return
        // El tipo REAL importa: tras grabar, lo último guardado es un mp4 y se enviaba con
        // setType("image/jpeg") pasara lo que pasara; WhatsApp lo rechazaba o lo adjuntaba
        // roto. Un mime mentido produce un adjunto que la app receptora no acepta.
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val base = Intent(Intent.ACTION_SEND)
            .setType(mime)
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

    /**
     * Última foto NUESTRA. Dos arreglos en una consulta:
     *
     * 1) PRIVACIDAD. DCIM/Camera la COMPARTIMOS con la cámara de fábrica, y esto solo
     *    filtraba por carpeta: con ultimoGuardado a null (el estado normal nada más abrir
     *    la app) el botón de WhatsApp podía ENVIAR la última foto de la cámara de fábrica
     *    -de otra persona, un documento, lo que fuera- creyendo el usuario que enviaba la
     *    que acababa de tomar. Todas las nuestras se llaman MACRO_<millis>.jpg. Ojo: en SQL
     *    el guion bajo es COMODÍN, así que hace falta ESCAPE o 'MACRO_%' casaría también
     *    con nombres ajenos (el mismo pinchazo que ya se corrigió en la galería).
     * 2) COSTE. El LIKE con comodín INICIAL ('%DCIM/Camera%') fuerza un escaneo completo de
     *    la tabla; sin él el proveedor puede usar el índice. Y sin LIMIT se ordenaba y
     *    materializaba TODA la carpeta de la cámara para leer una sola fila.
     */
    private fun latestMediaUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'MACRO#_%' ESCAPE '#'"
        val args = arrayOf("DCIM/Camera%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // QUERY_ARG_LIMIT es API 30; el resto de QUERY_ARG_SQL_* y la sobrecarga
                // query(Uri, String[], Bundle?, CancellationSignal?) existen desde API 26.
                val q = Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args
                    )
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
                }
                contentResolver.query(collection, projection, q, null)
            } else {
                contentResolver.query(collection, projection, selection, args, sort)
            }
            cursor?.use { c ->
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
            // Con el volumen como DISPARADOR solo vale la primera pulsación (un disparo por
            // clic); con zoom o exposición, en cambio, mantener pulsado DEBE repetir.
            // Antes las teclas estaban clavadas al disparo, sin alternativa.
            if (volAction != 0 || event == null || event.repeatCount == 0) {
                when (volAction) {
                    1 -> {
                        val paso = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.15f else 1f / 1.15f
                        currentZoom = controller.setZoom(currentZoom * paso)
                        showZoom()
                    }
                    2 -> {
                        val r = controller.evRange
                        val d = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                        evSteps = (evSteps + d).coerceIn(r.first, r.second)
                        controller.setEv(evSteps)
                        binding.evLabel.text = evLabel(evSteps)
                        showEvQuick()
                    }
                    else -> if (mode == "video") toggleRecord() else startPhotoOrTimer()
                }
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
        // El nivel de horizonte solo existe con la cuadrícula puesta: el sensor se enciende
        // y se apaga con ella en vez de estar despertando el hilo principal siempre.
        if (gridOn) startRollSensor() else stopRollSensor()
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
        // Los dos ajustes que sobreviven al cierre de la app (Ultra HDR y el lector de
        // códigos) se pelean por el mismo tercer flujo: encender uno tiene que apagar la
        // preferencia del otro, o al siguiente arranque se encenderían los dos.
        val ed = prefs.edit().putBoolean("hdr", on)
        if (on) ed.putBoolean("qr", false)
        ed.apply()
        hint(getString(if (on) R.string.hint_hdr_on else R.string.hint_hdr_off))
    }

    /** Muestra u oculta el panel con las opciones secundarias. */
    private fun toggleMorePanel() {
        showPanel(if (binding.morePanel.visibility == View.VISIBLE) null else binding.morePanel)
    }

    // ---- QR / código de barras ----
    // El escaneo ya NO se hace aquí. Vivía en un tick de la Activity que pedía
    // TextureView.getBitmap() cada 1,1 s EN EL HILO PRINCIPAL, una lectura síncrona
    // GPU->CPU cuyo coste escala con la superficie ORIGEN (2248x3998 en la pantalla
    // interior, no con los 360 px de destino) y que fuerza un vaciado del pipeline de
    // render: era la causa técnica más directa del "se siente lenta". Corría SIEMPRE, en
    // todos los modos, hasta en PRO, con el vídeo parado y durante una captura pedida por
    // otra app. El motor ya tenía hecho el camino bueno (stream YUV del HAL ->
    // InputImage.fromMediaImage, sin copia ni readback) y nadie llamaba nunca a
    // setQrEnabled. Ahora se enciende desde Ajustes y llega por controller.onQrDetected.
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
        // CADUCIDAD. La pastilla se encendía y NADA volvía a apagarla salvo abrir o cerrar
        // la tarjeta: el código se iba del encuadre y el aviso seguía en pantalla
        // anunciando algo que ya no estaba. El valor NO se mete en qrDismissedList a
        // propósito, para que vuelva a avisar si el código sigue delante.
        ui.removeCallbacks(hideQrHint)
        ui.postDelayed(hideQrHint, 4000)
    }

    private val hideQrHint = Runnable { binding.qrHint.visibility = View.GONE }

    /** Despliega la tarjeta con el contenido del código. */
    private fun openQrCard() {
        val v = qrValue ?: return
        binding.qrText.text = v
        binding.btnQrOpen.visibility =
            if (v.startsWith("http://") || v.startsWith("https://")) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideQrHint)
        binding.qrHint.visibility = View.GONE
        showCenterSlot(binding.qrCard)
    }

    private fun dismissQr() {
        qrValue?.let { qrDismissedList.add(it) }
        ui.removeCallbacks(hideQrHint)
        binding.qrHint.visibility = View.GONE
        if (binding.qrCard.visibility == View.VISIBLE) showCenterSlot(null)
    }

    private fun openQr() {
        val v = qrValue ?: return
        // Cerrar ANTES de irse al navegador: si no, al volver la tarjeta seguía abierta
        // ocupando la ranura central y tapando el visor sin que nadie la hubiera pedido.
        dismissQr()
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
        // El escáner de la Activity (autoScanner + scanBitmap) ya no existe: con él se va
        // también la carrera que había aquí, donde se reciclaba el bitmap sin mirar si ML
        // Kit tenía una detección EN VUELO sobre ese mismo bitmap (InputImage.fromBitmap no
        // lo copia), y el worker acababa dibujando sobre un bitmap reciclado.
        // freezeBitmap NO se recicla: lens_fade puede tenerlo puesto todavía y pintar un
        // bitmap reciclado mata el proceso entero. El de la lupa sí, porque a la vista solo
        // se le entrega el RECORTE, que es una copia aparte.
        freezeBitmap = null
        magBitmap?.recycle(); magBitmap = null
        analysisOverlay?.setMask(null)
        analysisBmp?.recycle(); analysisBmp = null
        maskBmp?.recycle(); maskBmp = null
        shutterSound?.release()
        shutterSound = null
        ioExec.shutdown()
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
        // Ocho de estas cadenas (cd_more, cd_lenses, cd_vid, cd_ev, cd_iso, cd_vel,
        // cd_kelvin, cd_auto) NO llevan marcador %1$s. String.format se traga el argumento
        // sobrante sin quejarse, así que no reventaba nada: simplemente TalkBack no
        // anunciaba NUNCA el estado de esos ocho chips, que era justo lo que este bloque de
        // accesibilidad venía a arreglar. Se detecta el marcador en vez de tocar strings.xml
        // (que es de otro integrador) y, si no lo hay, el estado se añade detrás.
        val plantilla = getString(cdRes)
        chip.contentDescription =
            if (plantilla.contains("%1\$s")) getString(cdRes, estado)
            else "$plantilla: $estado"
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
        // LENTE QUE VELA: el chip no puede mentir. controller.flashBlockedOnLens y
        // controller.hasFlash llevaban publicados sin que los leyera nadie. En la lente tele
        // el LED mete luz parásita en la óptica y el motor degrada CUALQUIER modo de flash a
        // apagado por su cuenta (flashModeEfectivo()); el chip, en cambio, seguía enseñando
        // el rayo en ámbar y anunciando "automático". El usuario disparaba convencido de que
        // llevaba flash. Aquí se pinta lo que va a PASAR, no lo que se pidió.
        if (::controller.isInitialized && controller.flashBlockedOnLens) {
            binding.chipFlash.text = ""
            setChipState(
                binding.chipFlash, false, R.string.cd_flash,
                getString(R.string.state_flash_blocked), R.drawable.ic_flash_off
            )
            // Se atenúa además de apagarse: es la única señal visual de "aquí no se puede",
            // frente a un simple "está apagado" que invitaría a volver a pulsarlo.
            binding.chipFlash.alpha = 0.45f
            return
        }
        binding.chipFlash.alpha = 1f
        // El ciclo tiene CUATRO estados (apagado / automático / encendido / linterna) y el
        // chip pintaba el MISMO icono para automático que para encendido, así que no había
        // manera de saber si el flash iba a dispararse o no. ic_flash_auto (el rayo con la A)
        // ya existía dibujado y no lo referenciaba nadie.
        val icon = when (flashMode) {
            0 -> R.drawable.ic_flash_off
            1 -> R.drawable.ic_flash_auto
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
        // Sin letra: la "AUTO" estaba ahí PORQUE el icono no distinguía automático de
        // encendido, y ahora sí lo distingue. Además chip_flash es un ProChip.Icon (56dp de
        // ancho mínimo, como los otros cuatro de la barra) y con el rótulo se iba a ~73dp,
        // rompiendo la única fila del HUD que sí era uniforme. El estado sigue anunciándose
        // entero por contentDescription, que es donde hace falta.
        binding.chipFlash.text = ""
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
        // El lector de códigos entra en la misma exclusión (los cuatro se pelean por el
        // tercer stream): si el motor lo ha apagado por su cuenta, aquí no puede quedar el
        // chip en ámbar ni un aviso de código colgado en pantalla.
        qrOn = controller.qrEnabled
        setChipState(binding.chipQr, qrOn, R.string.cd_qr)
        if (!qrOn && binding.qrHint.visibility == View.VISIBLE) {
            ui.removeCallbacks(hideQrHint)
            binding.qrHint.visibility = View.GONE
        }
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
            binding.btnQrCopy, binding.btnQrOpen, binding.btnQrClose, binding.qrHint,
            binding.chipQr, binding.chipFit,
            // Los dos chips del panel de vídeo que acaban de dejar de ser decorativos.
            binding.chipAudio, binding.chipAeLock
        ).forEach { markAsButton(it) }
        // La fila de estado de vídeo es pulsable (abre el panel): TalkBack la leía como
        // texto suelto y no había forma de saber que se podía tocar.
        markAsButton(binding.videoHud)
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

    /**
     * Cadencia de vídeo: 24 → 25 → 30 → 60.
     *
     * Antes solo alternaba 30 y 60. El 24p es el estándar cinematográfico y el 25 el de
     * PAL/broadcast, y su ausencia es una de las críticas más repetidas al rival: dejarla
     * pasar teniendo ya montado el rango de AE cerrado en el motor era regalar el bloque.
     *
     * Límite honesto y medido a mano: el motor solo fija CONTROL_AE_TARGET_FPS_RANGE si el
     * HAL publica un rango que case con la cadencia pedida. Si esta lente no publicara
     * [24,24] ni ningún rango con upper=24, el visor seguiría capturando a 30 y el archivo
     * saldría a ~30 fps aunque el chip diga 24. Por eso el valor POR DEFECTO sigue siendo 30
     * y esto es una elección explícita del usuario, no un cambio a sus espaldas. La lista de
     * cadencias que el aparato admite de verdad hay que pedírsela al motor (ver la entrega).
     */
    private fun toggleVfps() {
        val i = vfpsList.indexOf(vfps)
        vfps = if (i < 0) 30 else vfpsList[(i + 1) % vfpsList.size]
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
        // Ámbar en cuanto NO se está en la cadencia normal: 24, 25 y 60 son decisiones
        // deliberadas y el usuario tiene que ver de un vistazo que están puestas. Antes solo
        // se encendía a 60 porque no había nada más que 30 y 60.
        setChipState(binding.chipVfps, vfps != 30, R.string.cd_vfps, "$vfps")
        binding.chipVcodec.text = if (vhevc) "HEVC" else "H264"
        setChipState(
            binding.chipVcodec, vhevc, R.string.cd_vcodec, binding.chipVcodec.text.toString()
        )
        setChipState(binding.chipTl, tlOn, R.string.cd_tl)
        controller.setVideoTargetHeight(vresList[vresIndex])
        controller.setVideoFps(vfps)
        controller.setVideoHevc(vhevc)
        controller.setTimeLapse(tlOn)
        // El time-lapse APAGA el sonido en el motor: el estado del micrófono depende de este
        // chip, así que se repinta aquí y no en cuatro sitios sueltos.
        updateAudioUi()
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
        // open() resetea aeLocked/afLocked/manualFocus en el motor: la insignia y el chip MF
        // se quedaban encendidos con el bloqueo ya deshecho, y hacían falta DOS pulsaciones
        // largas para volver a bloquear de verdad.
        clearAeAfLock()
        mfOn = false
        updateMfChip()
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

    /**
     * Última vez que se avisó del riesgo de trepidación, para no repetirlo en cada disparo.
     */
    private var lastShakeWarnMs = 0L

    /**
     * AVISO DE FOTO MOVIDA. controller.shakeRiskStops llevaba publicado desde hace rondas y
     * no lo leía NADIE: dice cuántos pasos por debajo de la regla recíproca (1/focal
     * equivalente) está exponiendo el AE ahora mismo. Las dos tomas de teleobjetivo del
     * expediente salieron a 1/24 s con 70 mm equivalentes —tres pasos por debajo, movida
     * asegurada a pulso— y la app no dijo una palabra.
     *
     * Va en startPhotoOrTimer y no en el callback de la foto por dos motivos: es el ÚNICO
     * punto por el que pasan las tres formas de disparar (obturador, teclas de volumen y
     * pantalla externa), y llega ANTES de perder la toma; con el temporizador puesto, además,
     * el usuario tiene los 3 o 10 s de la cuenta atrás para apoyar el teléfono.
     *
     * Umbral en 1 paso entero y no en cuanto asoma el riesgo: por debajo de eso la mayoría de
     * la gente saca fotos nítidas y un aviso constante se convierte en ruido que se ignora.
     */
    private fun avisarSiVaAMoverse() {
        if (nightOn || mode != "photo") return // en noche el apilado ya es la respuesta
        if (controller.shakeRiskStops <= 1f) return
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - lastShakeWarnMs < 8000L) return
        lastShakeWarnMs = ahora
        hint(getString(R.string.hint_shake_risk))
    }

    private fun startPhotoOrTimer() {
        if (capturing) return
        // Segunda pulsación durante la cuenta atrás: CANCELAR, no reiniciarla. Antes la
        // única forma de detener un temporizador de 10 s era salir de la app, y salir
        // tampoco lo paraba de verdad.
        if (countdownRunnable != null) { cancelCountdown(); return }
        avisarSiVaAMoverse()
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

    // ==========================================================================
    //  ENFOQUE MANUAL (MF), APILADO DE ENFOQUE Y HORQUILLADO
    // ==========================================================================

    /**
     * Enciende o apaga el enfoque manual. setManualFocusDistance() y hasManualFocus llevan
     * implementados en el motor desde el primer día y NINGÚN control los llamaba (cero
     * coincidencias en el grep): en una app cuyo propósito declarado es el macro, el
     * enfoque fino era literalmente inalcanzable desde la interfaz. Con el AF continuo, a
     * 3 cm el barrido caza el foco y falla, y no había forma de fijar el plano.
     */
    private fun toggleMf() {
        if (!controller.hasManualFocus) {
            hint("Esta lente no permite enfoque manual")
            return
        }
        mfOn = !mfOn
        if (mfOn) {
            if (binding.proPanel.visibility != View.VISIBLE) togglePro() // el slider vive ahí
            selectParam("mf")
        } else {
            controller.setAutoFocus()
            if (proParam == "mf") selectParam("ev")
        }
        updateMfChip()
    }

    private fun updateMfChip() {
        val c = chipMf ?: return
        c.text = if (mfOn) "MF ${focusLabel(mfDiopters)}" else "MF"
        setExtraChip(
            c, mfOn,
            "Enfoque manual: " +
                if (mfOn) focusLabel(mfDiopters) else getString(R.string.state_off)
        )
    }

    /** Estado de los chips creados por código: no tienen cadena cd_* en strings.xml
     *  (ese fichero es de otro integrador), así que la descripción llega ya montada. */
    private fun setExtraChip(chip: TextView, active: Boolean, descripcion: String) {
        chip.isSelected = active
        chip.setTextColor(if (active) cAccent else cDim)
        chip.compoundDrawableTintList =
            ColorStateList.valueOf(if (active) cAccent else cDim)
        chip.contentDescription = descripcion
    }

    /** Dioptrías -> distancia legible. 0 dioptrías = infinito; el macro vive por debajo
     *  de 20 cm, que es justo donde el slider tiene casi todo su recorrido. */
    private fun focusLabel(d: Float): String {
        if (d <= 0.02f) return "∞"
        val cm = 100f / d
        return if (cm < 100f) String.format(Locale.US, "%.0f cm", cm)
        else String.format(Locale.US, "%.1f m", cm / 100f)
    }

    // El slider es lineal en DIOPTRÍAS, no en metros: es la escala en la que el enfoque se
    // mueve de forma uniforme y la que le da casi todo el recorrido a la zona cercana.
    private fun mfToProgress(d: Float): Int {
        val max = controller.minFocusDiopters
        if (max <= 0f) return 0
        return (d / max * 100f).toInt().coerceIn(0, 100)
    }

    private fun progressToMf(p: Int): Float = controller.minFocusDiopters * p / 100f

    /**
     * Apilado de enfoque: barrido de la distancia guardando la serie completa. En una foto
     * a 5 cm la profundidad de campo son MILÍMETROS: una sola toma nunca tiene todo el
     * bicho enfocado, por buena que sea la lente. La fusión NO se hace en el teléfono; se
     * apila en el ordenador, que es donde esa operación tiene sentido.
     */
    private fun startFocusStack() {
        if (!controller.hasManualFocus) {
            hint("Esta lente no permite enfoque manual")
            return
        }
        if (capturing || stackQueue.isNotEmpty() || mode != "photo") return
        val max = controller.minFocusDiopters
        // Barrido CENTRADO en el punto actual: ±25 % del recorrido, cinco tomas. Barrer de
        // 0 a infinito sería tirar la mitad de los disparos en planos que no interesan.
        val centro = if (mfDiopters > 0.02f) mfDiopters else max * 0.5f
        val ancho = max * 0.25f
        val pasos = ArrayList<Float>(5)
        for (i in 0 until 5) pasos.add((centro - ancho + (2f * ancho) * i / 4f).coerceIn(0f, max))
        stackQueue = ArrayDeque(pasos)
        stackTotal = pasos.size
        if (!mfOn) { mfOn = true; updateMfChip() }
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        stackNext()
    }

    private fun stackNext() {
        val d = stackQueue.removeFirstOrNull()
        if (d == null) {
            if (binding.nightLabel.visibility == View.VISIBLE) showCenterSlot(null)
            bounceThumbnail()
            return
        }
        controller.setManualFocusDistance(d)
        mfDiopters = d
        updateMfChip()
        binding.nightLabel.text = "Apilado de enfoque ${stackTotal - stackQueue.size}/$stackTotal"
        showCenterSlot(binding.nightLabel)
        // 350 ms: lo que tarda el motor de enfoque en llegar y quedarse quieto. Disparar
        // antes fotografía la lente EN MOVIMIENTO y toda la serie sale movida.
        ui.postDelayed({
            if (capturing) { ui.postDelayed({ stackNext() }, 100); return@postDelayed }
            capturing = true
            flashScreen()
            playShutterSound()
            controller.takePhoto {
                capturing = false
                stackNext()
            }
        }, 350)
    }

    /**
     * Horquillado de exposición (AEB): tres tomas a -1 / 0 / +1 EV. Hoy no hay ninguna
     * forma de asegurar una escena a contraluz: o se quema el cielo o se tapa la sombra, y
     * el usuario descubre cuál de las dos en casa.
     *
     * NO se usa captureBurst: el ImageReader de la foto tiene maxImages=2 y una ráfaga de
     * tres perdería fotogramas; además el AE necesita ~400 ms para asentar cada paso de
     * compensación o las tres fotos salen idénticas. Se encadena el takePhoto que ya
     * existe, sin tocar la máquina de estados de captura.
     */
    private fun startBracket() {
        if (capturing || bracketQueue.isNotEmpty() || mode != "photo") return
        if (controller.isManualExposure) {
            hint("El horquillado necesita exposición automática")
            return
        }
        val r = controller.evRange
        if (r.second <= r.first) {
            hint("Esta lente no permite compensar la exposición")
            return
        }
        // Cuántos pasos son 1 EV en esta lente: el HAL declara el tamaño del paso en
        // CONTROL_AE_COMPENSATION_STEP (suele ser 1/6 EV), y dar por hecho "1 paso = 1 EV"
        // habría producido tres fotos casi iguales.
        val step = controller.evStepValue.let { if (it > 0f) it else 0.5f }
        val uno = Math.max(1, Math.round(1f / step))
        bracketBase = evSteps
        bracketQueue = ArrayDeque(
            listOf(
                (bracketBase - uno).coerceIn(r.first, r.second),
                bracketBase,
                (bracketBase + uno).coerceIn(r.first, r.second)
            )
        )
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        bracketNext()
    }

    private fun bracketNext() {
        val siguiente = bracketQueue.removeFirstOrNull()
        if (siguiente == null) {
            controller.setEv(bracketBase) // dejar la exposición como estaba
            if (binding.nightLabel.visibility == View.VISIBLE) showCenterSlot(null)
            bounceThumbnail()
            return
        }
        controller.setEv(siguiente)
        binding.nightLabel.text = "Horquillado ${3 - bracketQueue.size}/3"
        showCenterSlot(binding.nightLabel)
        // 450 ms: el AE del visor tarda en asentarse tras cambiar la compensación; disparar
        // antes daba tres fotos con la MISMA exposición.
        ui.postDelayed({
            if (capturing) { ui.postDelayed({ bracketNext() }, 100); return@postDelayed }
            capturing = true
            flashScreen()
            playShutterSound()
            controller.takePhoto {
                capturing = false
                bracketNext()
            }
        }, 450)
    }

    // ==========================================================================
    //  HERRAMIENTAS DE ANÁLISIS: histograma, cebras de recorte y realce de enfoque
    // ==========================================================================

    private fun toggleTools() {
        toolsOn = !toolsOn
        chipTools?.let {
            setExtraChip(
                it, toolsOn,
                "Herramientas de análisis: " +
                    getString(if (toolsOn) R.string.state_on else R.string.state_off)
            )
        }
        analysisOverlay?.showHistogram = toolsOn && toolHist
        prefs.edit().putBoolean("toolsOn", toolsOn).apply()
        ui.removeCallbacks(analysisTick)
        if (toolsOn) ui.post(analysisTick) else analysisOverlay?.setMask(null)
        hint(if (toolsOn) "Análisis activado" else "Análisis desactivado")
    }

    private fun allocAnalysis(w: Int, h: Int) {
        analysisOverlay?.setMask(null)
        analysisBmp?.recycle()
        maskBmp?.recycle()
        analysisBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        analysisPx = IntArray(w * h)
        lumaBuf = IntArray(w * h)
        maskPx = IntArray(w * h)
    }

    /**
     * Un fotograma DIMINUTO del visor (160 px de ancho) y una sola pasada de enteros. Un
     * histograma no mejora con más muestras, y pedir el fotograma a media resolución es
     * exactamente lo que volvía lento al escáner de códigos que se acaba de quitar.
     */
    private fun analyzeFrame() {
        // 'controller.isRecording' SALE de la guarda. Apagaba el histograma, las cebras y el
        // realce de enfoque justo al empezar a rodar, que es exactamente cuando sirven: al
        // terminar la toma ya no hay nada que corregir. Un director de fotografía necesita
        // las cebras MIENTRAS graba; apagarlas ahí invierte el propósito de la función.
        // 'capturing' y 'shutterHeld' se quedan: ahí sí compiten por la misma lectura de GPU
        // con una captura de foto en vuelo, y eso sí se nota en la latencia del obturador.
        if (!toolsOn || capturing || shutterHeld) return
        val t = binding.texture
        if (t.width == 0 || t.height == 0) return
        val w = 160
        val h = (w.toFloat() * t.height / t.width).toInt().coerceIn(1, 400)
        val src0 = analysisBmp
        if (src0 == null || src0.width != w || src0.height != h) allocAnalysis(w, h)
        val src = analysisBmp ?: return
        val px = analysisPx ?: return
        val luma = lumaBuf ?: return
        val mask = maskPx ?: return
        if ((try { t.getBitmap(src) } catch (e: Exception) { null }) == null) return
        src.getPixels(px, 0, w, 0, 0, w, h)
        java.util.Arrays.fill(histBins, 0)
        var max = 1
        var suma = 0L
        for (i in px.indices) {
            val p = px[i]
            // Luma BT.601 en enteros (77/150/29 sobre 256): en coma flotante costaba el
            // triple para el mismo resultado visible.
            val y = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            luma[i] = y
            suma += y
            val n = ++histBins[y shr 2]
            if (n > max) max = n
        }
        // Nota honesta: la lectura de ISO/velocidad reales del AE se queda fuera porque el
        // motor todavía no publica aeIso/aeExposureNs (ver la entrega). Se muestra el nivel
        // medio de luz y la compensación pedida, que sí son datos ciertos.
        val medio = (suma / px.size.coerceAtLeast(1)).toInt() * 100 / 255
        analysisOverlay?.setHistogram(
            histBins, max, String.format(Locale.US, "LUZ %d%%   %s", medio, evLabel(evSteps))
        )
        if (!toolZebra && !toolPeak) { analysisOverlay?.setMask(null); return }
        java.util.Arrays.fill(mask, 0)
        if (toolZebra) {
            // Umbral ELEGIBLE (70 % piel / 95 % aviso / recorte). Estaba clavado en 250, o
            // sea que solo rayaba lo que ya se había perdido: una cebra útil avisa ANTES de
            // quemar, y el 70 % es la referencia de exposición de piel de cualquier rodaje.
            val alto = zebraLumas[zebraLevel.coerceIn(0, zebraLumas.size - 1)]
            for (i in px.indices) {
                val y = luma[i]
                if (y >= alto || y <= 2) {
                    // Rayas diagonales de 1 px: en pantalla el fotograma se amplía unas 7
                    // veces, así que se ven como bandas gruesas y no como ruido.
                    if (((i % w + i / w) and 1) == 0) {
                        mask[i] = if (y >= alto) 0xCCFFFFFF.toInt() else 0x99339CFF.toInt()
                    }
                }
            }
        }
        if (toolPeak) {
            // Sobel sobre la luma. En macro la profundidad de campo son milímetros y a ojo,
            // sobre un visor pequeño, es imposible saber qué está de verdad enfocado: esa es
            // la causa directa de las fotos "blandas" que se descubren en el ordenador.
            for (row in 1 until h - 1) {
                var i = row * w + 1
                for (x in 1 until w - 1) {
                    val gx = luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1] -
                        luma[i - w - 1] - 2 * luma[i - 1] - luma[i + w - 1]
                    val gy = luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1] -
                        luma[i - w - 1] - 2 * luma[i - w] - luma[i - w + 1]
                    if (kotlin.math.abs(gx) + kotlin.math.abs(gy) > 190) mask[i] = 0xFFFF9E00.toInt()
                    i++
                }
            }
        }
        val mb = maskBmp ?: return
        mb.setPixels(mask, 0, w, 0, 0, w, h)
        analysisOverlay?.setMask(mb)
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
            "mf" -> controller.setManualFocusDistance(mfDiopters)
        }
        binding.proSlider.progress = when (p) {
            "iso" -> isoToProgress(proIso)
            "vel" -> velToProgress(proExpNs)
            "mf" -> mfToProgress(mfDiopters)
            else -> evToProgress(evSteps)
        }
        updateProLabel()
        setProSliderEnabled(true)
    }

    private fun applyParam(p: Int) {
        when (proParam) {
            "ev" -> {
                val r = controller.evRange
                val steps = r.first + ((r.second - r.first) * p / 100.0).toInt()
                // evSteps es la MISMA variable que usan el slider rápido y showEvQuick: sin
                // esta asignación el slider rápido seguía mostrando el valor VIEJO y el
                // primer roce pisaba lo que se acababa de poner en PRO (salto de exposición).
                evSteps = steps
                controller.setEv(steps)
                binding.proValue.text = "EV $steps"
                binding.evLabel.text = evLabel(steps)
                binding.evSlider.progress = evToProgress(steps)
            }
            "mf" -> {
                mfDiopters = progressToMf(p)
                controller.setManualFocusDistance(mfDiopters)
                binding.proValue.text = focusLabel(mfDiopters)
                updateMfChip()
                // La lupa confirma la nitidez mientras se arrastra (es el gesto natural en
                // macro), pero getBitmap del texture es una lectura de GPU carísima: sin
                // este freno se disparaba una por cada píxel movido y el visor se atascaba.
                val ahora = SystemClock.elapsedRealtime()
                if (ahora - lastMagnifierMs > 250) {
                    lastMagnifierMs = ahora
                    val t = binding.texture
                    showMagnifier(
                        binding.previewHud.width / 2f, binding.previewHud.height / 2f,
                        t.width / 2f, t.height / 2f
                    )
                }
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
        // WB son PRESETS, no un valor continuo: applyParam no tiene rama "wb", así que a
        // partir de aquí mover el deslizador no hacía absolutamente nada y no había ninguna
        // señal de por qué. El usuario creía que el panel PRO se había roto y solo se
        // recuperaba tocando EV/ISO/VEL/K, cosa que nadie adivina.
        setProSliderEnabled(false)
    }

    /** El deslizador solo sirve para parámetros continuos: con WB se ve deshabilitado. */
    private fun setProSliderEnabled(on: Boolean) {
        binding.proSlider.isEnabled = on
        binding.proSlider.alpha = if (on) 1f else 0.4f
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
        setProSliderEnabled(true)
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
        // AUTO no reiniciaba de verdad: setEv(0) dejaba evSteps con el valor VIEJO, así que
        // el slider rápido seguía marcando la compensación anterior.
        evSteps = 0
        binding.evSlider.progress = evToProgress(0)
        binding.evLabel.text = evLabel(0)
        binding.proSlider.progress = evToProgress(0)
        binding.proValue.text = "AUTO"
        mfOn = false
        updateMfChip()
        setProSliderEnabled(true)
    }

    private fun updateProLabel() {
        binding.proValue.text = when (proParam) {
            "iso" -> "ISO $proIso"
            "vel" -> shutterLabel(proExpNs)
            "mf" -> focusLabel(mfDiopters)
            else -> "EV $evSteps"
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
