package com.pepe.camaramacro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Estado del enfoque comunicado a la UI (mapea CONTROL_AF_STATE). */
enum class FocusState { SCANNING, FOCUSED, NOT_FOCUSED, INACTIVE }

/**
 * Relación de aspecto de captura. NATIVE = el del sensor (máxima área).
 * FULL = pantalla completa: captura todo el sensor y recorta a la proporción de la pantalla.
 */
enum class AspectRatio(val w: Int, val h: Int) { NATIVE(0, 0), R4_3(4, 3), R16_9(16, 9), R1_1(1, 1), FULL(0, 0) }

/**
 * Abre una lente concreta por su ID, muestra la vista previa en un AutoFitTextureView
 * y permite tomar fotos. Soporta enfoque por toque (con estado AF), bloqueo AE/AF,
 * enfoque manual por distancia y zoom — degradando con gracia si la lente no expone
 * alguna capacidad.
 *
 * Diseñado para EVITAR la lente principal dañada: tiene un "watchdog" que avisa si una
 * lente no responde, y nunca bloquea el hilo de UI.
 */
class Camera2Controller(
    private val activity: Activity,
    private val textureView: AutoFitTextureView
) {

    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onFocusState: ((FocusState) -> Unit)? = null
    /**
     * Miniatura de la foto recién tomada, entregada EN CUANTO existen los bytes.
     * Antes la miniatura esperaba a que MediaStore indexara el archivo y se notaba
     * un retraso claro; ahora se pinta al instante desde el JPEG en memoria.
     */
    var onPhotoThumb: ((android.graphics.Bitmap) -> Unit)? = null

    /** Se avisa justo antes de cerrar una lente para abrir otra (para el fundido). */
    var onLensSwitching: (() -> Unit)? = null

    /**
     * Si está puesto, la foto NO va a la galería: se entrega aquí. Lo usa la captura
     * solicitada por otra app (ACTION_IMAGE_CAPTURE), que debe escribirla donde diga
     * el llamador. Devuelve true si la consumió correctamente.
     */
    @Volatile
    var jpegSink: ((ByteArray) -> Boolean)? = null

    /** Resultado de guardar el DNG (RAW). true = guardado, false = fallo. */
    var onRawSaved: ((Boolean) -> Unit)? = null
    /** Se llama si RAW no se pudo activar (la lente no admite 3 streams) y se cayó a JPEG. */
    var onRawUnavailable: (() -> Unit)? = null

    /**
     * Progreso REAL del apilado nocturno: (fotograma apilado, total). Antes la etiqueta
     * "Apilando..." era fija y el usuario no sabía si la app se había colgado; con 7
     * fotogramas a 12,6 MP el proceso dura segundos.
     */
    var onNightProgress: ((Int, Int) -> Unit)? = null

    /**
     * RAW, Ultra HDR, noche y QR son EXCLUYENTES (cada uno añade un stream y el HAL solo
     * admite tres). Activar uno apaga los otros, así que la UI tiene que repintar los
     * cuatro chips cuando esto se dispare o mostrará encendido algo que ya está apagado.
     */
    var onCaptureModesChanged: (() -> Unit)? = null

    /**
     * Primer fotograma REAL de una sesión recién configurada. Sirve para retirar el
     * congelado del cambio de lente cuando de verdad hay imagen nueva, en vez de con un
     * postDelayed ciego que descubre el visor antes de tiempo (o lo tapa de más).
     */
    var onFirstFrame: (() -> Unit)? = null

    private var cameraId: String = "0"
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    /**
     * Era `lateinit`, y eso mentía: isInitialized seguía siendo true después de cerrar la
     * cámara, así que se seguían construyendo peticiones contra un CameraDevice muerto.
     * Ahora es nulable y se pone a null en close(), en switchToLens y en fail(); además
     * TODA mutación pasa por onCameraThread(), porque CaptureRequest.Builder no es
     * thread-safe y se tocaba a la vez desde el hilo de UI (zoom, EV, WB) y desde el de
     * cámara (unlockFocusAfterShot): ajustes que se perdían y peticiones inconsistentes.
     */
    @Volatile private var previewRequestBuilder: CaptureRequest.Builder? = null

    // Token de generación de apertura: descarta callbacks de una lente anterior
    // (p.ej. tras flip/switch) para que no muestren error en la lente nueva.
    // @Volatile: lo escribe el hilo de UI (close, switchToLens) y lo leen los callbacks
    // del HAL desde otro hilo; sin la barrera, onOpened podía leer el valor viejo.
    @Volatile private var cameraGen = 0
    /** Se avisa una vez por sesión, cuando llega el PRIMER resultado real del visor. */
    @Volatile private var firstFrameNotified = false

    private var previewSize: Size = Size(1920, 1080)
    private var sensorOrientation = 0
    private var facingFront = false
    private var afContinuousSupported = false
    private var afVideoSupported = false

    // Capacidades / estado de control
    var afAvailable = false
        private set
    private var minFocusDistance = 0f      // dioptrías; 0 = lente de foco fijo
    private var activeArray: Rect? = null

    private var zoomRatio = 1f
    private var maxZoom = 1f
    private var zoomRatioSupported = false

    // Auto-cambio de lente al zoom (solo lentes que funcionan)
    private val zoomChain = mutableListOf<Pair<String, Float>>() // (id, baseZoom) asc por focal
    private var chainIndex = 0
    private var globalZoom = 1f
    private var switching = false
    private var pendingResidual = -1f
    private var autoLens = false
    private var disabledLensIds: Set<String> = emptySet()
    /**
     * Zoom digital MÁXIMO real de cada lente, leído del HAL. Antes el tope global era
     * "la última lente x4", una constante inventada: en esta lente el HAL declara bastante
     * más, así que se le estaba negando al usuario zoom que el aparato sí tiene.
     */
    private val lensMaxZoom = mutableMapOf<String, Float>()

    // Controles PRO (exposición / WB)
    private var isoMin = 100
    private var isoMax = 100
    private var expMinNs = 0L
    private var expMaxNs = 0L
    private var evMin = 0
    private var evMax = 0
    private var evStepRational: android.util.Rational? = null
    private var manualExposure = false
    private var manualIso = 100
    private var manualExpNs = 8_000_000L
    private var evSteps = 0
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var awbOffSupported = false
    private var manualWb = false
    private var wbKelvin = 5000

    // Anti-blur / estabilización
    private var aeFpsRange: Range<Int>? = null
    private var oisAvailable = false
    private var eisAvailable = false          // estabilización electrónica (solo video)
    private var videoSessionActive = false    // true mientras la sesión es de grabación
    private var refocusRelease: Runnable? = null // vuelve a enfoque continuo tras un toque
    private var manualSensorSupported = false
    // Ultra HDR (JPEG_R): el HAL lo declara a resolución completa en ID3 e ID6. Es un JPEG
    // normal con el mapa de ganancia HDR embebido, así que se ve bien en cualquier visor
    // y espectacular en pantallas HDR. Sustituye al JPEG normal (no se suman streams).
    private var hdrSupported = false
    private var hdrFallbackTried = false
    /**
     * DESEO del usuario, separado de la capacidad. hdrSupported solo se conoce después del
     * primer setUpOutputs (o sea, después de open()), pero la Activity restaura sus ajustes
     * en onCreate: setHdrEnabled(true) devolvía SIEMPRE false y el Ultra HDR se apagaba
     * solo en cada arranque mientras la preferencia guardada seguía diciendo que sí.
     */
    private var hdrRequested = false
    /** Ultra HDR no se pudo activar y se cayó a JPEG normal. */
    var onHdrUnavailable: (() -> Unit)? = null
    var hdrEnabled = false
        private set
    private var nrAvailable: IntArray = IntArray(0)
    private var edgeAvailable: IntArray = IntArray(0)
    private var aberrationAvailable: IntArray = IntArray(0)
    /**
     * Acción pendiente a ejecutar cuando el AF converja antes de disparar.
     * @Volatile: se arma en el hilo de UI (takePhoto) y se consume en el de cámara
     * (previewCallback). Sin la barrera de memoria, el hilo de cámara podía no ver el
     * valor recién escrito y CADA foto se resolvía por el timeout en vez de por el
     * enfoque: el obturador siempre tardaba lo mismo, enfocara o no.
     */
    @Volatile private var afWaitAction: (() -> Unit)? = null
    private var afWaitTimeout: Runnable? = null
    /** Se ha visto un barrido ACTIVO desde el disparador (no el estado pasivo heredado). */
    @Volatile private var afSawActiveScan = false
    /** El AE ha entrado DE VERDAD en PRECAPTURE (no el CONVERGED viejo de antes del trigger). */
    @Volatile private var aeSawPrecapture = false
    @Volatile private var lastAeState = -1
    private var lastSavedUri: Uri? = null
    /** URI de lo ultimo que se guardo: la miniatura y compartir deben usar ESTO,
     *  no una busqueda en MediaStore que puede apuntar a la carpeta equivocada. */
    val ultimoGuardado: Uri? get() = lastSavedUri
    private val lensEquivMm = mutableMapOf<String, Int>()

    /**
     * Paradas ÓPTICAS del zoom: (zoom global, etiqueta). Una por lente física real.
     * Es la base de la tira de zoom en pantalla: nuestra ventaja diferencial hecha visible.
     */
    /**
     * Factor para pasar del zoom interno (1.0 = lente más angular a su campo nativo) a la
     * escala ESTÁNDAR de los teléfonos, donde 1x equivale a unos 24 mm. Así el gran angular
     * se muestra como 0.6x y el 1x es un recorte digital suyo, como en cualquier móvil.
     */
    val zoomDisplayFactor: Float
        get() {
            val baseId = zoomChain.firstOrNull()?.first ?: return 1f
            val mm = lensEquivMm[baseId] ?: return 1f
            return if (mm > 0) mm / 24f else 1f
        }

    private fun fmtZoom(d: Float): String =
        if (kotlin.math.abs(d - Math.round(d)) < 0.05f) "${Math.round(d)}x"
        else String.format(java.util.Locale.US, "%.1fx", d)

    /**
     * Paradas de zoom para la UI: (zoom interno, etiqueta visible, esÓptica).
     * Incluye las lentes físicas reales y las paradas estándar (1x, 2x, 5x) que
     * caigan dentro del rango, resueltas con zoom digital.
     */
    fun zoomStops(): List<Triple<Float, String, Boolean>> {
        if (zoomChain.isEmpty()) return emptyList()
        val f = zoomDisplayFactor
        val byDisplay = sortedMapOf<Float, Triple<Float, String, Boolean>>()
        zoomChain.forEach { (_, base) ->
            val disp = base * f
            byDisplay[disp] = Triple(base, fmtZoom(disp), true) // parada óptica real
        }
        val minDisp = zoomChain.first().second * f
        val maxDisp = globalMaxZoom() * f
        listOf(1f, 2f, 5f).forEach { d ->
            val yaHay = byDisplay.keys.any { kotlin.math.abs(it - d) < 0.18f }
            if (!yaHay && d > minDisp + 0.05f && d < maxDisp) {
                byDisplay[d] = Triple(d / f, fmtZoom(d), false) // zoom digital
            }
        }
        return byDisplay.values.toList()
    }
    @Volatile private var aeWaitAction: (() -> Unit)? = null
    private var aeWaitTimeout: Runnable? = null
    // Nº de fotograma en el que se envió el disparador. Sin esto, el visor (que sigue
    // entregando resultados a 30 fps) colaba un resultado ANTERIOR al disparo y la espera
    // de enfoque se resolvía al instante: el arreglo del enfoque no llegaba a ejecutarse.
    // Lo arma el hilo de cámara y lo cancela el de UI (y al revés): @Volatile o uno de los
    // dos podía quedarse con una referencia vieja y no cancelar nada.
    @Volatile private var captureWatchdog: Runnable? = null
    @Volatile private var afTriggerFrame = Long.MAX_VALUE
    @Volatile private var aeTriggerFrame = Long.MAX_VALUE
    private var activeFocalMm = 0f
    private var activeEquivMm = 0

    /** Etiqueta de la lente física activa, p.ej. "ID3 · 15 mm". Es nuestra ventaja diferencial. */
    val activeLensLabel: String
        get() = if (activeEquivMm > 0) "ID$cameraId · $activeEquivMm mm" else "ID$cameraId"

    /**
     * Cuántas lentes traseras hay desactivadas por el usuario. Si es > 0 el zoom deja de
     * saltar a esa óptica y cae a zoom digital SIN avisar: la UI debe hacerlo visible.
     */
    val disabledLensCount: Int
        get() = disabledLensIds.size

    /** true si el zoom actual ya no puede alcanzar una lente porque está desactivada. */
    val zoomLimitedByDisabledLens: Boolean
        get() = disabledLensIds.isNotEmpty()
    /** Piso de velocidad para congelar el movimiento (0 = automático, sin piso). */
    // 1/60 s. Antes estaba en 1/125 y disparaba el ISO hasta 20.000 en interiores:
    // congelaba el movimiento pero las fotos salían llenas de ruido. Con OIS, 1/60
    // es de sobra para escenas normales a pulso.
    private var shutterFloorNs = 16_666_667L
    /** Tope de ISO al que estamos dispuestos a llegar por acortar la exposición. */
    private val isoCeilingForFloor = 3200

    // Flash
    private var flashAvailable = false
    private var flashMode = 0 // 0 off, 1 auto, 2 on, 3 torch

    // RAW / DNG
    private var camChars: CameraCharacteristics? = null
    private var rawSupported = false
    private var rawReader: ImageReader? = null
    var rawEnabled = false
        private set
    // Emparejamiento Imagen RAW + metadata: ambos llegan por canales independientes y
    // sin orden garantizado; se escribe el DNG solo cuando AMBOS están presentes.
    // Cerrojo del par (Imagen RAW, metadata): la Imagen llega por el hilo de cámara y la
    // cancelación puede venir del de UI. Sin él, los dos hilos cerraban la MISMA Image.
    private val rawLock = Any()
    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null
    private var rawFallbackTried = false

    // Ajustes de captura (resolución / relación de aspecto)
    private var aspect = AspectRatio.NATIVE
    private var fullRes = true
    private var captureMatrix: ColorMatrix? = null // filtro de color aplicado a la foto

    // Modo noche (multi-frame). Excluyente con RAW.
    var nightEnabled = false
        private set
    private var nightReader: ImageReader? = null
    private var nightSize = Size(1920, 1080)
    @Volatile private var nightStacker: NightStacker? = null
    /**
     * Un ÚNICO dueño del final de la ráfaga nocturna. Antes finishNightStack (hilo de
     * cámara) y abortNight (watchdog, hilo de UI) hacían "if (!nightCapturing) return;
     * nightCapturing = false" sobre un campo normal: comprobar-y-asignar no es atómico,
     * así que el usuario podía recibir el error Y la foto buena para el MISMO disparo, y
     * abortNight podía liberar el stacker mientras addFrame seguía escribiendo en él.
     */
    private val nightActive = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var nightCount = 0
    private var nightTarget = 0
    // El apilado NO puede vivir en el hilo de la cámara: son ~130 millones de iteraciones
    // por foto y mientras corren no se entrega ningún callback (visor congelado, capturas
    // perdidas). Hilo propio, creado solo si el modo noche se usa.
    private var stackThread: HandlerThread? = null
    private var stackHandler: Handler? = null
    private var lastAeIso = 800
    private var lastAeExpNs = 33_000_000L
    private var lastFocusDistance = 0f // última distancia de enfoque real del visor
    private var nightWatchdog: Runnable? = null

    // QR / código de barras (ML Kit). Excluyente con RAW y noche.
    var qrEnabled = false
        private set
    private var qrReader: ImageReader? = null
    private var qrScanner: BarcodeScanner? = null
    @Volatile private var qrBusy = false
    /** Generación del escáner: descarta detecciones de una sesión ya cerrada. */
    @Volatile private var qrGen = 0
    var onQrDetected: ((String) -> Unit)? = null

    private var aeLocked = false
    private var afLocked = false
    private var manualFocus = false
    private var manualDiopters = 0f
    // Lo escribe el hilo de cámara y lo lee takePhoto desde el de UI para decidir si hace
    // falta enfocar: sin @Volatile se podía disparar con un estado de enfoque rancio.
    @Volatile private var lastFocusState: FocusState? = null

    // Video
    private var videoSize = Size(1920, 1080)
    private var availableVideoSizes: List<Size> = emptyList()
    private var videoTargetH = 1080   // 2160 / 1080 / 720
    private var videoFps = 30         // 30 / 60
    private var videoHevc = false
    private var timeLapse = false
    private var mediaRecorder: MediaRecorder? = null
    private var recording = false
    private var videoUri: Uri? = null
    private var videoPfd: android.os.ParcelFileDescriptor? = null
    private var videoFos: FileOutputStream? = null
    private var videoFile: File? = null
    var onRecordingChanged: ((Boolean) -> Unit)? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * ÚNICO dueño del callback del disparo. Se arma NADA MÁS pulsar (takePhoto /
     * takeNightPhoto) y se consume una sola vez con getAndSet(null) dentro de finishShot.
     *
     * Antes el callback solo vivía dentro de las lambdas de espera de AF/AE y no se
     * asignaba a un campo hasta captureStillNow. Durante esa ventana (hasta 400+900 ms)
     * cualquier cancelación —close() en onPause, onDisconnected (LA ruta habitual en
     * ColorOS), onError, fail() o un cambio de lente— leía un campo todavía null: nadie
     * invocaba jamás el callback, la Activity se quedaba con capturing=true y el botón de
     * disparo moría hasta recrear la pantalla.
     */
    private val shotCallback =
        java.util.concurrent.atomic.AtomicReference<((Boolean) -> Unit)?>(null)
    private var failed = false
    private var watchdog: Runnable? = null
    private var lastOrientationDegrees = 0

    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                lastOrientationDegrees = orientation
            }
        }
    }

    // -------------------------------------------------------- Capacidades públicas

    /**
     * Tope de recorte digital de la ÚLTIMA lente de la cadena, tal y como lo declara el
     * HAL. Aquí había un "x4" escrito a mano que no salía de ningún sitio: ni coincidía
     * con lo que admite el sensor ni era igual en las dos lentes, así que la tira de zoom
     * enseñaba un tope inventado (de más en una lente y de menos en la otra).
     */
    private fun tailDigitalZoom(): Float {
        val id = zoomChain.lastOrNull()?.first ?: return maxZoom
        return (lensMaxZoom[id] ?: maxZoom).coerceAtLeast(1f)
    }

    /** Zoom global máximo: la focal de la última lente por su recorte digital real. */
    private fun globalMaxZoom(): Float =
        if (autoLens && zoomChain.isNotEmpty()) zoomChain.last().second * tailDigitalZoom()
        else maxZoom

    /** Rango de zoom (mín, máx) considerando toda la cadena de lentes. */
    val zoomRange: Pair<Float, Float>
        get() = Pair(1f, globalMaxZoom())

    /** Máximo de zoom global. */
    val maxZoomRatio: Float
        get() = globalMaxZoom()

    /** ¿La lente permite enfoque manual por distancia? */
    val hasManualFocus: Boolean get() = minFocusDistance > 0f

    /** Distancia mínima de enfoque en dioptrías (0 = foco fijo). */
    val minFocusDiopters: Float get() = minFocusDistance

    fun currentZoom(): Float = zoomRatio

    // ---------------------------------------------------------------- API pública

    fun open(camId: String) {
        failed = false
        zoomRatio = 1f
        aeLocked = false
        afLocked = false
        manualFocus = false
        manualDiopters = 0f
        lastFocusState = null
        cameraId = camId
        buildZoomChain()
        chainIndex = zoomChain.indexOfFirst { it.first == camId }
        autoLens = chainIndex >= 0 && zoomChain.size >= 2
        if (chainIndex < 0) chainIndex = 0
        globalZoom = if (autoLens) zoomChain[chainIndex].second else 1f
        switching = false
        pendingResidual = -1f
        Log.i("CamMacro", "open id=$camId autoLens=$autoLens chainIndex=$chainIndex chain=$zoomChain")
        if (backgroundThread == null) startBackgroundThread()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    /**
     * Dispara. Si el enfoque NO está confirmado, lanza primero un barrido y espera a que
     * converja, con techo duro (AF_WAIT_MAX_MS) para no volver lento el obturador.
     * Antes se capturaba el frame actual aunque estuviera blando: esa era la causa de
     * fotos "suaves" pese a tener buen sensor.
     *
     * El callback se registra ANTES de cualquier espera, no al final: así ninguna
     * cancelación puede dejar a la UI esperando una respuesta que no llegará nunca.
     */
    fun takePhoto(onResult: (Boolean) -> Unit) {
        if (!armShot(onResult)) return
        startFocusThenCapture()
    }

    /**
     * Registra el callback del disparo. Devuelve false si YA hay una foto en vuelo; en ese
     * caso al recién llegado se le contesta que no, pero jamás se le deja sin contestación.
     */
    private fun armShot(onResult: (Boolean) -> Unit): Boolean {
        if (!shotCallback.compareAndSet(null, onResult)) {
            Log.w("CamMacro", "disparo ignorado: ya hay una captura en vuelo")
            activity.runOnUiThread { onResult(false) }
            return false
        }
        return true
    }

    /**
     * Cierra el disparo UNA sola vez, venga de donde venga: éxito, fallo del HAL, timeout,
     * excepción al guardar o cancelación. Antes el desbloqueo del AF solo estaba en la ruta
     * feliz y en onCaptureFailed: si saltaba CAPTURE_TIMEOUT_MS o reventaba saveImage, el
     * AF se quedaba en FOCUSED_LOCKED y TODAS las fotos siguientes salían clavadas a la
     * distancia de aquella.
     */
    private fun finishShot(ok: Boolean) {
        cancelCaptureWatchdog()
        clearAfAeWaits()
        unlockFocusAfterShot()
        val cb = shotCallback.getAndSet(null) ?: return
        activity.runOnUiThread { cb(ok) }
    }

    private fun cancelCaptureWatchdog() {
        val w = captureWatchdog
        captureWatchdog = null
        w?.let { uiHandler.removeCallbacks(it) }
    }

    /**
     * Serializa en el hilo de la cámara todo lo que toque previewRequestBuilder o la
     * sesión. CaptureRequest.Builder no es thread-safe y se estaba mutando desde el hilo
     * de UI (zoom, EV, WB, toque) y desde el de cámara a la vez.
     */
    private fun onCameraThread(block: () -> Unit) {
        val h = backgroundHandler ?: return // motor parado: no hay nada que aplicar
        if (Looper.myLooper() === h.looper) block() else h.post(block)
    }

    /** Decide si hay que enfocar antes de disparar y arranca la secuencia. */
    private fun startFocusThenCapture() {
        val ready = captureSession != null && previewRequestBuilder != null
        // Antes la condición excluía a mano el caso afLocked (foco fijado con un toque), de
        // modo que el gesto más natural -tocar y disparar acto seguido- no esperaba nada y
        // capturaba en pleno barrido activo: foto blanda justo cuando el usuario acababa de
        // señalar a mano lo que quería nítido.
        val needsAf = ready && afAvailable && !manualFocus && lastFocusState != FocusState.FOCUSED
        when {
            !needsAf -> proceedAfterAf()
            // Con el foco dirigido por el usuario ya hay un barrido AUTO en marcha hacia SU
            // punto: solo hay que suscribirse al resultado. Reenviar AF_TRIGGER_START lo
            // cancelaría y arrancaría otro, perdiendo la región que él eligió.
            afLocked -> waitForAfThenCapture(retrigger = false)
            else -> waitForAfThenCapture(retrigger = true)
        }
    }

    /** Tras el enfoque: si hay flash auto/on hace falta la pre-captura para que encienda. */
    private fun proceedAfterAf() {
        val wantFlash = flashAvailable && (flashMode == 1 || flashMode == 2) && !manualExposure
        if (wantFlash && captureSession != null && previewRequestBuilder != null) {
            triggerPrecaptureThenCapture()
        } else {
            captureStillNow()
        }
    }

    /**
     * Secuencia de pre-captura del AE: sin ella el HAL no mide ni carga el flash y la foto
     * sale a oscuras aunque se pida flash obligatorio (comprobado: ISO 14681 y sin destello).
     */
    private fun triggerPrecaptureThenCapture() {
        val session = captureSession
        val builder = previewRequestBuilder
        if (session == null || builder == null) { captureStillNow(); return }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = {
            if (fired.compareAndSet(false, true)) {
                aeWaitAction = null
                aeTriggerFrame = Long.MAX_VALUE // que el próximo disparo no herede la puerta
                aeWaitTimeout?.let { uiHandler.removeCallbacks(it) }
                aeWaitTimeout = null
                captureStillNow()
            }
        }
        // ORDEN: primero se cierra la puerta y luego se arma la espera. Al revés queda una
        // ventana en la que aeWaitAction ya existe mientras aeTriggerFrame todavía guarda el
        // número de fotograma del disparo ANTERIOR, así que cualquier resultado del visor la
        // atraviesa y la pre-captura se da por buena sin haber empezado.
        aeTriggerFrame = Long.MAX_VALUE
        aeSawPrecapture = false
        aeWaitAction = go
        val timeout = Runnable {
            // Queda registrado si el HAL llegó a entrar en PRECAPTURE. Si sale siempre con
            // visto=false, este HAL se salta esa fase y hay que replantear la condición en
            // vez de pagar el timeout entero en cada foto con flash.
            Log.w(
                "CamMacro",
                "AE sin pre-captura en ${AE_PRECAPTURE_MAX_MS}ms (visto=$aeSawPrecapture, estado=$lastAeState)"
            )
            go()
        }
        aeWaitTimeout = timeout
        uiHandler.postDelayed(timeout, AE_PRECAPTURE_MAX_MS)
        onCameraThread {
            try {
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                session.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(
                            s: CameraCaptureSession, r: CaptureRequest, ts: Long, frame: Long
                        ) {
                            aeTriggerFrame = frame
                        }
                    },
                    backgroundHandler
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
            } catch (e: Exception) {
                go() // pase lo que pase, la foto se toma
            }
        }
    }

    /**
     * Espera a que el enfoque converja de verdad. Con retrigger=true manda un barrido
     * nuevo; con false se limita a escuchar el que ya lanzó el toque del usuario.
     */
    private fun waitForAfThenCapture(retrigger: Boolean) {
        val session = captureSession
        val builder = previewRequestBuilder
        if (session == null || builder == null) { proceedAfterAf(); return }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = {
            if (fired.compareAndSet(false, true)) {
                afWaitAction = null
                afTriggerFrame = Long.MAX_VALUE // no dejar la puerta vieja para el próximo
                afWaitTimeout?.let { uiHandler.removeCallbacks(it) }
                afWaitTimeout = null
                proceedAfterAf() // el flash necesita su propia pre-captura
            }
        }
        // Puerta cerrada ANTES de armar la espera (ver el comentario de la pre-captura).
        // Cuando no reenviamos el disparador, el barrido ya está en marcha desde antes, así
        // que la puerta por número de fotograma sobra: manda el estado (solo LOCKED).
        afTriggerFrame = if (retrigger) Long.MAX_VALUE else 0L
        afSawActiveScan = false
        afWaitAction = go
        val timeout = Runnable {
            // Se registra si el HAL llegó a barrer: si esto sale con barrido=false de forma
            // sistemática, el problema no es el tiempo de espera sino que el trigger no llega.
            Log.w("CamMacro", "AF no convergió en ${AF_WAIT_MAX_MS}ms (barrido=$afSawActiveScan)")
            go()
        }
        afWaitTimeout = timeout
        uiHandler.postDelayed(timeout, AF_WAIT_MAX_MS)
        if (!retrigger) return
        onCameraThread {
            try {
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START
                )
                session.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(
                            s: CameraCaptureSession, r: CaptureRequest, ts: Long, frame: Long
                        ) {
                            afTriggerFrame = frame // a partir de aquí sí valen los resultados
                        }
                    },
                    backgroundHandler
                )
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
            } catch (e: Exception) {
                go() // ante cualquier fallo, dispara igual: nunca dejar el obturador muerto
            }
        }
    }

    /**
     * Adelanta el barrido de enfoque al APRETAR el obturador (ACTION_DOWN), antes de que el
     * dedo se levante. Son 100-200 ms gratis: cuando llega el disparo real el AF ya suele
     * estar convergido y takePhoto se salta la espera entera.
     */
    fun prewarmAf() {
        if (!afAvailable || manualFocus || afLocked) return
        if (shotCallback.get() != null) return // ya hay un disparo en vuelo: no interferir
        val session = captureSession ?: return
        onCameraThread {
            val builder = previewRequestBuilder ?: return@onCameraThread
            try {
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START
                )
                session.capture(builder.build(), previewCallback, backgroundHandler)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Aquí NO vale el onCameraThread genérico: si el hilo de cámara ya no existe, dejar el
     * post en el vacío sería exactamente el obturador muerto para siempre que veníamos a
     * arreglar. Si no hay a quién encargar la captura, se contesta que no en el acto.
     */
    private fun captureStillNow() {
        val h = backgroundHandler
        if (h == null) { finishShot(false); return }
        if (Looper.myLooper() === h.looper) captureStillNowOnCamera()
        else if (!h.post { captureStillNowOnCamera() }) finishShot(false)
    }

    private fun captureStillNowOnCamera() {
        val device = cameraDevice
        val session = captureSession
        val reader = imageReader
        if (device == null || session == null || reader == null) {
            finishShot(false); return
        }
        try {
            // Vigilante: si el HAL no entrega la imagen, el obturador se liberaría igual.
            cancelCaptureWatchdog()
            val cw = Runnable {
                if (shotCallback.get() != null) {
                    Log.e("CamMacro", "captura sin respuesta: liberando el obturador")
                    finishShot(false)
                }
            }
            captureWatchdog = cw
            uiHandler.postDelayed(cw, CAPTURE_TIMEOUT_MS)
            val wantRaw = rawEnabled && rawSupported && rawReader != null
            // Descarta cualquier mitad colgante de un disparo previo.
            if (wantRaw) clearPendingRaw()
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(reader.surface)
            if (wantRaw) rawReader?.let { req.addTarget(it.surface) }
            applyControls(req, still = true)
            var flashIso = 0
            if (flashAvailable) {
                // Orden EXPLÍCITA de destello. Dejar que solo mande CONTROL_AE_MODE no
                // funcionaba en este HAL (comprobado por EXIF tres veces): FLASH_MODE_SINGLE
                // es inequívoco. En AUTO solo destella si el AE dijo que hace falta luz.
                val fireFlash = flashMode == 2 ||
                    (flashMode == 1 && lastAeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)
                when {
                    flashMode == 3 ->
                        req.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    fireFlash -> {
                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        req.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                        flashIso = applyFlashExposure(req)
                    }
                }
            }
            // DESPUÉS del flash (si no, el bloque de flash pisaría el AE_MODE_OFF).
            val floorIso = applyShutterFloor(req)
            // El ISO REAL de esta foto, que puede no tener nada que ver con el del visor:
            // decidir el denoise con el del visor pedía detalle máximo para fotos que
            // acababan a ISO 3200 y denoise agresivo para fotos que salían a ISO 100.
            val shotIso = when {
                floorIso > 0 -> floorIso
                flashIso > 0 -> flashIso
                else -> lastAeIso
            }
            applyDetailModes(req, shotIso)
            req.set(CaptureRequest.JPEG_ORIENTATION, currentJpegOrientation())
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    auditDetailModes(request, result)
                    // Solo emparejar metadata si ESTE disparo pidió RAW.
                    if (wantRaw) {
                        synchronized(rawLock) { pendingRawResult = result }
                        tryFlushDng()
                    }
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    // Sin esta limpieza, dos fallos seguidos dejaban el ImageReader de
                    // RAW sin buffers y el modo RAW muerto hasta reabrir la cámara.
                    clearPendingRaw()
                    finishShot(false)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            finishShot(false)
        }
    }

    /**
     * Exposición de sincronización del destello. Medido en el aparato: dos capturas con
     * flash salieron a ISO 21280 y 1/20 s porque el AE mide el AMBIENTE y el destello llega
     * DESPUÉS: ruido extremo y primer plano quemado. Aquí se acota la parte ambiental
     * (1/60 s y un techo de ISO) y el resto de la luz la pone el flash, que es como se hace
     * en cualquier cámara. Con luz suficiente no se toca nada: se deja la ruta del AE del
     * HAL, que es la que está verificada por EXIF. Devuelve el ISO efectivo, o 0 si no tocó.
     */
    private fun applyFlashExposure(b: CaptureRequest.Builder): Int {
        if (!manualSensorSupported || manualExposure) return 0
        if (lastAeExpNs <= 0L || lastAeIso <= 0) return 0
        if (lastAeIso <= FLASH_MAX_ISO && lastAeExpNs <= FLASH_SYNC_EXP_NS) return 0
        val exp = FLASH_SYNC_EXP_NS.coerceIn(
            expMinNs.coerceAtLeast(1L),
            if (expMaxNs > 0) expMaxNs.coerceAtLeast(1L) else FLASH_SYNC_EXP_NS
        )
        val luz = lastAeExpNs.toDouble() * lastAeIso
        val iso = Math.round(luz / exp).toInt()
            .coerceIn(isoMin, minOf(isoMax, FLASH_MAX_ISO).coerceAtLeast(isoMin))
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        Log.i(
            "CamMacro",
            "flash: ${exp / 1000}us ISO$iso (ambiente ${lastAeExpNs / 1000}us ISO$lastAeIso)"
        )
        return iso
    }

    /**
     * Cancela una captura pendiente liberando su callback (evita que el obturador se quede
     * "pegado" si la sesión se cierra/reconstruye con una foto en vuelo).
     */
    private fun abortPendingCapture() {
        nightActive.set(false)
        clearPendingRaw()
        finishShot(false)
    }

    /** Cierra la mitad colgante de un DNG. Bajo cerrojo: la Imagen llega por el hilo de
     *  cámara y la cancelación puede venir del de UI, y cerrarla dos veces revienta. */
    private fun clearPendingRaw() {
        synchronized(rawLock) {
            try { pendingRawImage?.close() } catch (e: Exception) {}
            pendingRawImage = null
            pendingRawResult = null
        }
    }

    /**
     * Suelta el enfoque después de disparar. CRÍTICO: waitForAfThenCapture manda
     * AF_TRIGGER_START y, sin este CANCEL, el AF se queda en FOCUSED_LOCKED. Como
     * takePhoto decide si hace falta enfocar mirando lastFocusState, a partir de la
     * primera foto ni el visor reenfocaba ni las siguientes fotos volvían a enfocar:
     * quedaban clavadas a la distancia de la primera.
     */
    private fun unlockFocusAfterShot() {
        if (afLocked || manualFocus || !afAvailable) return // el toque manda: no lo pisamos
        onCameraThread {
            val session = captureSession ?: return@onCameraThread
            val builder = previewRequestBuilder ?: return@onCameraThread
            try {
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
                )
                session.capture(builder.build(), null, backgroundHandler)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
                lastFocusState = null // que la próxima foto vuelva a enfocar de verdad
                applyControls(builder)
                updatePreview()
            } catch (e: Exception) {
            }
        }
    }

    /** Cancela cualquier espera de enfoque/exposición pendiente. */
    private fun clearAfAeWaits() {
        afWaitAction = null
        aeWaitAction = null
        afTriggerFrame = Long.MAX_VALUE
        aeTriggerFrame = Long.MAX_VALUE
        afSawActiveScan = false
        aeSawPrecapture = false
        afWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        afWaitTimeout = null
        aeWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        aeWaitTimeout = null
    }

    fun close() {
        // PRIMERO invalidar la generación. Si openCamera sigue en vuelo (el HAL todavía no
        // ha entregado onOpened), la comprobación 'gen != cameraGen' de onOpened no detectaba
        // nada: el CameraDevice se asignaba sobre un controlador ya desmontado o, peor, se
        // quedaba abierto sin que nadie lo cerrara. En ColorOS eso deja la lente retenida por
        // nuestro propio proceso y la apertura siguiente muere con MAX_CAMERAS_IN_USE.
        cameraGen++
        abortPendingCapture()
        previewRequestBuilder = null
        try {
            captureSession?.close()
        } catch (e: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
        }
        imageReader = null
        try {
            rawReader?.close()
        } catch (e: Exception) {
        }
        rawReader = null
        clearPendingRaw()
        nightActive.set(false)
        nightWatchdog?.let { uiHandler.removeCallbacks(it) }
        nightWatchdog = null
        nightStacker?.release()
        nightStacker = null
        try { nightReader?.close() } catch (e: Exception) {}
        nightReader = null
        stopStackThread()
        try { qrReader?.close() } catch (e: Exception) {}
        qrReader = null
        try { qrScanner?.close() } catch (e: Exception) {}
        qrScanner = null
        qrBusy = false
        videoSessionActive = false
        refocusRelease?.let { uiHandler.removeCallbacks(it) }
        refocusRelease = null
        afWaitAction = null
        afWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        afWaitTimeout = null
        aeWaitAction = null
        aeWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        aeWaitTimeout = null
        cancelWatchdog()
        orientationListener.disable()
        stopBackgroundThread()
    }

    // ---------------------------------------------------------------- Zoom

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (zoomRatioSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val arr = activeArray ?: return
            val cropW = (arr.width() / zoomRatio).toInt().coerceAtLeast(1)
            val cropH = (arr.height() / zoomRatio).toInt().coerceAtLeast(1)
            val l = arr.left + (arr.width() - cropW) / 2
            val t = arr.top + (arr.height() - cropH) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cropW, t + cropH))
        }
    }

    /**
     * Fija el zoom GLOBAL (across lentes). Cambia de lente física automáticamente al
     * cruzar el umbral óptico, y hace zoom digital dentro de cada lente. Devuelve el zoom global aplicado.
     */
    fun setZoom(g: Float): Float {
        if (!autoLens) {
            zoomRatio = g.coerceIn(1f, maxZoom)
            globalZoom = zoomRatio
            applyZoomNow()
            return globalZoom
        }
        val gmax = globalMaxZoom()
        val gg = g.coerceIn(1f, gmax)
        var ti = 0
        for (i in zoomChain.indices) if (gg >= zoomChain[i].second - 0.01f) ti = i
        val residual = gg / zoomChain[ti].second
        Log.i("CamMacro", "setZoom g=$g gg=$gg ti=$ti chainIndex=$chainIndex residual=$residual switch=${ti != chainIndex}")
        if (ti != chainIndex) {
            pendingResidual = residual
            globalZoom = gg
            switchToLens(ti)
        } else {
            zoomRatio = residual.coerceIn(1f, maxZoom)
            globalZoom = zoomChain[chainIndex].second * zoomRatio
            applyZoomNow()
        }
        return globalZoom
    }

    /** Aplica el zoom al visor SIEMPRE en el hilo de la cámara (el builder no es seguro). */
    private fun applyZoomNow() {
        onCameraThread {
            val b = previewRequestBuilder ?: return@onCameraThread
            applyZoom(b)
            updatePreview()
        }
    }

    /** Lentes traseras que sirven (excluye la dañada ID0 y duplicados de su focal). Para la UI. */
    fun backLensCandidates(): List<Pair<String, Float>> {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { manager.cameraIdList } catch (e: Exception) { return emptyList() }
        var mainFocal = 0f
        try {
            mainFocal = manager.getCameraCharacteristics("0")
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        } catch (e: Exception) {
        }
        val backs = mutableListOf<Pair<String, Float>>()
        for (id in ids) {
            if (id == "0") continue
            try {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                if (focal <= 0f) continue
                // Excluye la principal dañada y cualquier duplicado con su misma focal.
                if (mainFocal > 0f && focal > mainFocal - 0.3f && focal < mainFocal + 0.3f) continue
                // Tope de recorte digital REAL de esta lente. Antes el tope global era "la
                // última lente por 4", un número inventado en el código que no salía del HAL.
                val zrUpper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper else null
                lensMaxZoom[id] = (
                    zrUpper
                        ?: c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: 1f
                    ).coerceAtLeast(1f)
                // Guarda el equivalente 35 mm de cada lente para poder etiquetar el zoom.
                c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { ps ->
                    val diag = kotlin.math.sqrt(
                        (ps.width * ps.width + ps.height * ps.height).toDouble()
                    )
                    if (diag > 0) lensEquivMm[id] = Math.round(focal * 43.27 / diag).toInt()
                }
                backs.add(Pair(id, focal))
            } catch (e: Exception) {
            }
        }
        backs.sortBy { it.second }
        return backs
    }

    /** Construye la cadena de zoom; respeta las lentes desactivadas por el usuario. */
    private fun buildZoomChain() {
        zoomChain.clear()
        val allBacks = backLensCandidates()
        if (allBacks.isEmpty()) return
        // Quita las desactivadas, pero NUNCA la lente abierta ahora (garantiza preview/captura).
        var backs = allBacks.filter { it.first == cameraId || !disabledLensIds.contains(it.first) }
        if (backs.isEmpty()) backs = allBacks // salvaguarda: jamás dejar la cadena vacía
        val minF = backs.first().second
        backs.forEach { zoomChain.add(Pair(it.first, it.second / minF)) }
        Log.i("CamMacro", "buildZoomChain chain=$zoomChain disabled=$disabledLensIds")
    }

    /** Activa/desactiva lentes en la cadena de zoom. La lente activa nunca se quita. */
    fun setDisabledLensIds(ids: Set<String>) {
        disabledLensIds = HashSet(ids)
        if (previewRequestBuilder != null && captureSession != null) refreshZoomChain()
    }

    /** Reconstruye la cadena en caliente (sin recrear sesión: la lente activa sigue viva). */
    private fun refreshZoomChain() {
        val prev = cameraId
        buildZoomChain()
        chainIndex = zoomChain.indexOfFirst { it.first == prev }
        autoLens = chainIndex >= 0 && zoomChain.size >= 2
        if (chainIndex < 0) chainIndex = 0
        globalZoom = if (autoLens) zoomChain[chainIndex].second * zoomRatio else zoomRatio
        setZoom(globalZoom)
    }

    private fun switchToLens(targetIndex: Int) {
        if (switching) return
        switching = true
        // Igual que en close(): invalidar la generación ANTES de cerrar nada, o un onOpened
        // tardío de la lente anterior se asigna encima de la nueva y quedan dos abiertas.
        cameraGen++
        // Congela el último fotograma para tapar el negro del cambio de lente.
        activity.runOnUiThread { onLensSwitching?.invoke() }
        chainIndex = targetIndex
        cameraId = zoomChain[targetIndex].first
        failed = false
        abortPendingCapture()
        previewRequestBuilder = null
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
        if (textureView.isAvailable) {
            openCamera()
        } else {
            switching = false
        }
    }

    // ---------------------------------------------------------------- Enfoque

    /** Enfoque/medición en el punto tocado (coordenadas de la vista). */
    fun setFocusPoint(x: Float, y: Float, viewW: Int, viewH: Int) {
        val session = captureSession ?: return
        if (viewW == 0 || viewH == 0) return
        val rect = meteringRect(x / viewW, y / viewH) ?: return
        val mr = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
        manualFocus = false
        lastFocusState = null
        onCameraThread { applyFocusPoint(session, mr) }
    }

    /** Parte que toca el builder: SIEMPRE en el hilo de la cámara. */
    private fun applyFocusPoint(
        session: CameraCaptureSession,
        mr: Array<MeteringRectangle>
    ) {
        val previewRequestBuilder = previewRequestBuilder ?: return
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mr)
            if (afAvailable) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mr)
                // Secuencia CORRECTA de tap-to-focus en Camera2:
                // 1) CANCEL para abortar el barrido pasivo en curso,
                // 2) modo AUTO (un disparo) para poder dirigir el enfoque a la región,
                // 3) START para lanzar de verdad el barrido hacia el punto tocado.
                // Antes solo se mandaba CANCEL→IDLE: se cancelaba el enfoque y nunca
                // se pedía uno nuevo, por eso el enfoque táctil era lento e impreciso.
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
                )
                session.capture(previewRequestBuilder.build(), null, backgroundHandler)

                afLocked = true // applyControls mantiene AF_MODE_AUTO (foco fijado en el punto)
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                )
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START
                )
                session.capture(previewRequestBuilder.build(), previewCallback, backgroundHandler)
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
                scheduleRefocusRelease()
            } else {
                // Sin AF: solo fijamos exposición/medición en el punto.
                activity.runOnUiThread { onFocusState?.invoke(FocusState.FOCUSED) }
            }
            updatePreview()
        } catch (e: Exception) {
        }
    }

    /**
     * Tras unos segundos con el foco fijado por toque, vuelve al enfoque continuo
     * para que la cámara siga funcionando si el usuario se mueve (como las nativas).
     */
    private fun scheduleRefocusRelease() {
        refocusRelease?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!manualFocus && afLocked) {
                afLocked = false
                onCameraThread {
                    val b = previewRequestBuilder ?: return@onCameraThread
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                    applyControls(b)
                    updatePreview()
                }
            }
        }
        refocusRelease = r
        uiHandler.postDelayed(r, TAP_FOCUS_HOLD_MS)
    }

    /** Bloquea/desbloquea exposición y enfoque. Al desbloquear, vuelve a enfoque continuo. */
    fun lockAeAf(locked: Boolean) {
        aeLocked = locked
        if (!locked) {
            afLocked = false
            manualFocus = false
        }
        applyAndUpdate()
    }

    /**
     * Enfoque manual por distancia (dioptrías). Ignora si la lente no lo soporta.
     * NO es código muerto pese a que hoy nadie lo llame: es el motor del control MF que la
     * interfaz tiene que exponer (una app de macro sin enfoque manual es media app).
     * Se conserva a propósito y se mantiene funcionando.
     */
    fun setManualFocusDistance(diopters: Float) {
        if (minFocusDistance <= 0f) return
        manualFocus = true
        afLocked = false
        manualDiopters = diopters.coerceIn(0f, minFocusDistance)
        applyAndUpdate()
    }

    /** Distancia de enfoque manual en uso (dioptrías); 1/d = metros. Para la etiqueta en cm. */
    val manualFocusDiopters: Float get() = manualDiopters

    /** ¿Está el enfoque en manual ahora mismo? La UI necesita saberlo para pintar el chip. */
    val isManualFocus: Boolean get() = manualFocus

    /** Vuelve al enfoque automático continuo y quita los bloqueos. */
    fun setAutoFocus() {
        manualFocus = false
        afLocked = false
        aeLocked = false
        lastFocusState = null
        onCameraThread {
            val b = previewRequestBuilder ?: return@onCameraThread
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            applyControls(b)
            updatePreview()
        }
    }

    // ---------------------------------------------------------------- Controles PRO

    fun supportsManualExposure(): Boolean = isoMax > isoMin && expMaxNs > expMinNs
    val isoRange: Pair<Int, Int> get() = Pair(isoMin, isoMax)
    val shutterRangeNs: Pair<Long, Long> get() = Pair(expMinNs, expMaxNs)
    val evRange: Pair<Int, Int> get() = Pair(evMin, evMax)
    /** Valor en EV de cada paso de compensación (p.ej. 1/6 EV), para etiquetar bien la UI. */
    val evStepValue: Float get() = evStepRational?.let {
        if (it.denominator != 0) it.numerator.toFloat() / it.denominator else 0f
    } ?: 0f
    val isManualExposure: Boolean get() = manualExposure

    fun setManualExposure(iso: Int, expNs: Long) {
        manualExposure = true
        manualIso = iso.coerceIn(isoMin, isoMax)
        manualExpNs = expNs.coerceIn(if (expMinNs > 0) expMinNs else 1L, if (expMaxNs > 0) expMaxNs else 100_000_000L)
        applyAndUpdate()
    }

    fun setAutoExposure() {
        manualExposure = false
        applyAndUpdate()
    }

    fun setEv(steps: Int) {
        evSteps = steps.coerceIn(evMin, evMax)
        applyAndUpdate()
    }

    fun setWhiteBalance(mode: Int) {
        awbMode = mode
        manualWb = false // un preset cancela el modo Kelvin manual
        applyAndUpdate()
    }

    val hasManualWb: Boolean get() = awbOffSupported
    val kelvinRange: Pair<Int, Int> get() = Pair(2300, 7500)

    fun setWhiteBalanceKelvin(k: Int) {
        if (!awbOffSupported) return
        manualWb = true
        wbKelvin = k.coerceIn(2300, 7500)
        applyAndUpdate()
    }

    /** Aprox.: K alto = imagen cálida (más rojo); K bajo = fría (más azul). Gains >= 1.0. */
    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val t = ((kelvin - 2300).toFloat() / (7500 - 2300)).coerceIn(0f, 1f)
        val r = 1.0f + t * 1.4f   // 1.0 .. 2.4
        val b = 2.2f - t * 1.2f   // 2.2 .. 1.0
        return RggbChannelVector(r, 1.0f, 1.0f, b)
    }

    val hasFlash: Boolean get() = flashAvailable

    fun setFlashMode(m: Int) {
        flashMode = m
        applyAndUpdate()
    }

    /**
     * Activa/desactiva RAW. Reconstruye la sesión para añadir/quitar el stream RAW, de modo
     * que la vista previa por defecto (sin RAW) use la combinación de 2 streams ya probada.
     * Devuelve el estado real (false si la lente no soporta RAW).
     */
    val hasHdr: Boolean get() = hdrSupported

    /**
     * Ultra HDR: la foto se captura en JPEG_R (JPEG con mapa de ganancia HDR embebido).
     * Reconstruye la sesión porque cambia el formato del stream de captura.
     * Es excluyente con RAW: el DngCreator necesita el sensor en crudo.
     */
    fun setHdrEnabled(enabled: Boolean): Boolean {
        if (enabled == hdrRequested) return hdrEnabled
        hdrRequested = enabled
        if (enabled) {
            hdrFallbackTried = false
            keepOnlyExtra(EXTRA_HDR)
        }
        // Si la lente ya está abierta sabemos la capacidad real; si todavía no (restauración
        // de ajustes en onCreate), se resuelve en setUpOutputs y se avisa por
        // onHdrUnavailable / onCaptureModesChanged en vez de mentir devolviendo false.
        hdrEnabled = enabled && hdrSupported
        postRebuildSession()
        return if (hdrSupported) hdrEnabled else hdrRequested
    }

    /**
     * Fija el deseo de Ultra HDR ANTES del primer open (igual que presetCaptureSettings),
     * para que la preferencia guardada sobreviva al arranque sin depender del orden entre
     * onCreate y onResume.
     */
    fun presetHdr(on: Boolean) {
        hdrRequested = on
        if (on) keepOnlyExtra(EXTRA_HDR)
    }

    fun setRawEnabled(enabled: Boolean): Boolean {
        val target = enabled && rawSupported
        if (target == rawEnabled) return rawEnabled
        if (target) {
            rawFallbackTried = false // permite el fallback seguro otra vez
            keepOnlyExtra(EXTRA_RAW)
        }
        rawEnabled = target
        postRebuildSession()
        return rawEnabled
    }

    /**
     * RAW, Ultra HDR, noche y QR son EXCLUYENTES: cada uno añade un stream y esta lente no
     * admite más de tres. Antes cada setter apagaba a los que le parecía (RAW apagaba tres;
     * Ultra HDR, solo RAW), así que se podían tener noche y Ultra HDR a la vez: el HAL
     * rechazaba la sesión y el Ultra HDR se apagaba solo sin explicación o, peor, el
     * obturador seguía llamando a takeNightPhoto con la sesión configurada en JPEG_R.
     * Un único sitio decide, y se avisa a la UI para que repinte los cuatro chips.
     */
    private fun keepOnlyExtra(cual: Int) {
        var cambio = false
        if (cual != EXTRA_RAW && rawEnabled) { rawEnabled = false; cambio = true }
        if (cual != EXTRA_HDR && hdrRequested) {
            hdrRequested = false; hdrEnabled = false; cambio = true
        }
        if (cual != EXTRA_NIGHT && nightEnabled) { nightEnabled = false; cambio = true }
        if (cual != EXTRA_QR && qrEnabled) { setQrEnabledInternal(false); cambio = true }
        if (cambio) activity.runOnUiThread { onCaptureModesChanged?.invoke() }
    }

    // ---------------------------------------------------------------- Ajustes de captura

    val currentAspect: AspectRatio get() = aspect
    val currentFull: Boolean get() = fullRes

    /** Tamaños que cumplen la relación de aspecto actual (o todos si NATIVE / sin coincidencias). */
    private fun sizesForAspect(sizes: Array<Size>): List<Size> {
        if (sizes.isEmpty()) return emptyList()
        val candidates = if (aspect == AspectRatio.NATIVE || aspect == AspectRatio.FULL) sizes.toList() else {
            val target = aspect.w.toFloat() / aspect.h
            sizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - target) < 0.03f }
        }
        return if (candidates.isEmpty()) sizes.toList() else candidates
    }

    /** Elige el tamaño JPEG según la relación de aspecto y resolución (full/media) actuales. */
    private fun pickJpegSize(sizes: Array<Size>): Size {
        val sorted = sizesForAspect(sizes).sortedByDescending { it.width.toLong() * it.height }
        if (sorted.isEmpty()) return Size(1920, 1080)
        return if (fullRes) sorted.first()
        else sorted.getOrNull(sorted.size / 2) ?: sorted.first()
    }

    /** Fija ajustes SIN reconstruir (para usar antes del primer open). */
    fun presetCaptureSettings(newAspect: AspectRatio, full: Boolean) {
        aspect = newAspect
        fullRes = full
    }

    /** Cambia resolución/aspecto reconstruyendo la sesión (no cambia el nº de streams). */
    fun setCaptureSettings(newAspect: AspectRatio, full: Boolean) {
        if (newAspect == aspect && full == fullRes) return
        aspect = newAspect
        fullRes = full
        postRebuildSession()
    }

    /** Reconstruye toda la sesión (recrea ImageReaders según los flags actuales). */
    private fun postRebuildSession() {
        val h = backgroundHandler
        if (cameraDevice != null && !recording && captureSession != null && h != null) {
            // Token tomado AL ENCOLAR: si entre el post y su ejecución hay un switchToLens,
            // un onDisconnected o un close(), esta reconstrucción ya no tiene sentido y
            // ejecutarla dejaría la sesión de la lente NUEVA hecha trizas.
            val gen = cameraGen
            h.post {
                if (gen != cameraGen) return@post
                abortPendingCapture()
                previewRequestBuilder = null
                try { captureSession?.close() } catch (e: Exception) {}
                captureSession = null
                try { imageReader?.close() } catch (e: Exception) {}
                imageReader = null
                try {
                    val mgr = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    setUpOutputs(mgr)
                    configureTransform(textureView.width, textureView.height)
                    startPreview()
                } catch (e: Exception) {
                    fail("No se pudo aplicar el ajuste: ${e.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Modo noche

    val hasNight: Boolean get() = true // YUV_420_888 es universal en Camera2

    /** Activa/desactiva modo noche. Excluyente con RAW (nunca más de 3 streams). */
    fun setNightEnabled(enabled: Boolean): Boolean {
        if (enabled == nightEnabled) return nightEnabled
        if (enabled) keepOnlyExtra(EXTRA_NIGHT)
        nightEnabled = enabled
        postRebuildSession()
        return nightEnabled
    }

    /** Resolución REAL a la que se apila (el heap manda). La UI debe mostrarla al activar. */
    val nightMegapixels: Float
        get() = nightSize.width.toLong() * nightSize.height / 1_000_000f

    // ---------------------------------------------------------------- QR / códigos

    val hasQr: Boolean get() = true

    /**
     * Escaneo de códigos por el stream YUV del propio HAL. ES LA RUTA BUENA y hoy no la
     * llama nadie: la Activity escanea leyendo el TextureView con getBitmap() cada 1,1 s en
     * el hilo de UI, una lectura síncrona GPU->CPU cuyo coste crece con el tamaño del panel
     * (en la pantalla grande del plegable son 36 MB por lectura) y que fuerza un vaciado del
     * pipeline de render: la causa más directa del "se siente lenta". Aquí no hay copia ni
     * readback, solo un tercer stream de 1280 px que el HAL rellena solo.
     * Se conserva y se mantiene viva para que la interfaz la conecte y borre la otra.
     */
    fun setQrEnabled(enabled: Boolean): Boolean {
        if (enabled == qrEnabled) return qrEnabled
        if (enabled) keepOnlyExtra(EXTRA_QR)
        setQrEnabledInternal(enabled)
        postRebuildSession()
        return qrEnabled
    }

    private fun setQrEnabledInternal(enabled: Boolean) {
        qrEnabled = enabled
        // Cada encendido/apagado invalida las detecciones en vuelo de la etapa anterior.
        qrGen++
        if (enabled) {
            if (qrScanner == null) qrScanner = BarcodeScanning.getClient()
        } else {
            try { qrScanner?.close() } catch (e: Exception) {}
            qrScanner = null
            qrBusy = false
        }
    }

    private val onQrImage = ImageReader.OnImageAvailableListener { reader ->
        val gen = qrGen
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return@OnImageAvailableListener
        val scanner = qrScanner
        if (qrBusy || scanner == null) { closeQuietly(image); return@OnImageAvailableListener }
        qrBusy = true
        try {
            val input = InputImage.fromMediaImage(image, sensorOrientation)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val v = barcodes.firstOrNull()?.rawValue
                    // Guarda de generación: una detección que venía de una sesión ya cerrada
                    // no debe abrir la tarjeta del código encima de lo que haya ahora.
                    if (!v.isNullOrEmpty() && gen == qrGen) {
                        activity.runOnUiThread { onQrDetected?.invoke(v) }
                    }
                }
                .addOnCompleteListener {
                    // Este listener corre en el hilo PRINCIPAL: si entretanto close() (al
                    // pausar) o setUpOutputs cerraron el qrReader, cerrar aquí la Image lanza
                    // IllegalStateException en varios HAL y se lleva la app por delante.
                    closeQuietly(image)
                    qrBusy = false
                }
        } catch (e: Exception) {
            closeQuietly(image)
            qrBusy = false
        }
    }

    private fun closeQuietly(image: Image) {
        try { image.close() } catch (e: Exception) {}
    }

    /**
     * Captura una ráfaga de N frames con exposición bloqueada y los apila (denoise nocturno).
     * Si no es posible, cae a una foto JPEG normal.
     */
    fun takeNightPhoto(onResult: (Boolean) -> Unit) {
        val device = cameraDevice
        val session = captureSession
        val reader = nightReader
        if (device == null || session == null || reader == null || nightActive.get()) {
            takePhoto(onResult); return
        }
        if (!armShot(onResult)) return
        try {
            nightActive.set(true)
            nightCount = 0
            nightTarget = NIGHT_FRAMES
            nightStacker = NightStacker(nightSize.width, nightSize.height)

            // Exposición a bloquear. Copiar tal cual la del visor era el gran fallo del
            // modo noche: el visor va acotado por el rango de FPS, así que cada fotograma
            // salía a 1/30 s con el ISO por las nubes y apilar siete copias de una foto
            // ruidosa y oscura no la ilumina. Aquí se REPARTE al revés que en una foto
            // normal: se conserva la luz medida (tiempo x ISO) pero cargándola en el
            // TIEMPO y bajando el ISO todo lo que se pueda. Misma exposición, mucho menos
            // grano; del temblor ya se encarga el apilado.
            val iso: Int
            val expNs: Long
            if (manualExposure) {
                iso = manualIso; expNs = manualExpNs
            } else {
                val luz = lastAeExpNs.toDouble() * lastAeIso
                val techo = minOf(NIGHT_MAX_EXP_NS, expMaxNs)
                var e = luz / isoMin.coerceAtLeast(50) // el tiempo que pediría el ISO base
                if (e > techo) e = techo.toDouble()
                if (e < lastAeExpNs) e = lastAeExpNs.toDouble() // nunca menos que el visor
                expNs = e.toLong().coerceIn(expMinNs.coerceAtLeast(1L), expMaxNs)
                iso = Math.round(luz / expNs).toInt().coerceIn(isoMin, isoMax)
            }
            Log.i("CamMacro", "noche: ${expNs / 1000}us ISO$iso (visor ${lastAeExpNs / 1000}us ISO$lastAeIso)")

            val requests = ArrayList<CaptureRequest>(NIGHT_FRAMES)
            for (n in 0 until NIGHT_FRAMES) {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                if (manualWb && awbOffSupported) {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(wbKelvin))
                } else {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                }
                // AF estable durante la ráfaga (no reenfocar entre frames). CLAVE: hay que
                // fijar la distancia REAL a la que estaba enfocado el visor; antes, en modo
                // automático, se dejaba la del template (0 = infinito en muchos HAL) y los
                // 7 frames salían desenfocados: ninguna alineación puede rescatar eso.
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (minFocusDistance > 0f) {
                    b.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        if (manualFocus) manualDiopters else lastFocusDistance
                    )
                }
                // Congela también el balance de blancos para que los frames sean fusionables.
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (oisAvailable) b.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                applyZoom(b)
                requests.add(b.build())
            }
            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {}, backgroundHandler)
            val wd = Runnable { abortNight() }
            nightWatchdog = wd
            // 8 s fijos se quedaron cortos en cuanto el apilado pasó a resolución completa:
            // por fotograma son ~30 M de operaciones de alineación más el bucle de
            // acumulación, así que el vigilante mataba la foto justo antes de terminarla.
            // Ahora el plazo crece con el número de fotogramas.
            uiHandler.postDelayed(
                wd, NIGHT_WATCHDOG_BASE_MS + NIGHT_WATCHDOG_PER_FRAME_MS * NIGHT_FRAMES
            )
        } catch (e: Exception) {
            nightActive.set(false)
            nightStacker?.release()
            nightStacker = null
            finishShot(false)
        }
    }

    private val onNightImage = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        var apilado = false
        try {
            image = reader.acquireNextImage()
            if (nightActive.get() && image != null) {
                nightStacker?.addFrame(image)
                apilado = true
            }
        } catch (e: Exception) {
            Log.e("CamMacro", "onNightImage: ${e.message}")
        } finally {
            image?.close()
        }
        // El contador solo avanza si el fotograma ENTRÓ de verdad en el apilado. Antes se
        // incrementaba aunque acquireNextImage devolviera null o lanzara, así que la ráfaga
        // se daba por terminada con menos fotogramas de los pedidos —menos reducción de
        // ruido— y sin decir nada. De los que se pierdan ya se encarga el vigilante.
        if (apilado && nightActive.get()) {
            val n = ++nightCount
            activity.runOnUiThread { onNightProgress?.invoke(n, nightTarget) }
            if (n >= nightTarget) finishNightStack()
        }
    }

    private fun finishNightStack() {
        // compareAndSet: es la ÚNICA forma de garantizar que el final de la ráfaga lo cierra
        // uno solo. Con el "if (!nightCapturing) return; nightCapturing = false" de antes, el
        // vigilante (hilo de UI) y este listener (hilo del apilado) podían pasar los dos y el
        // usuario recibía el aviso de error Y la foto buena para el mismo disparo.
        if (!nightActive.compareAndSet(true, false)) return
        nightWatchdog?.let { uiHandler.removeCallbacks(it) }
        nightWatchdog = null
        val stacker = nightStacker
        nightStacker = null
        val ok = try {
            val nv21 = stacker?.result()
            if (nv21 != null) {
                val yuv = YuvImage(nv21, ImageFormat.NV21, nightSize.width, nightSize.height, null)
                val bos = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, nightSize.width, nightSize.height), 95, bos)
                var bytes = bos.toByteArray()
                val rot = currentJpegOrientation()
                if (rot != 0) bytes = rotateJpeg(bytes, rot) ?: bytes
                val ok = saveImage(bytes)
                // YuvImage no escribe EXIF: la foto de noche salía SIN metadatos.
                if (ok) writeNightExif(lastSavedUri)
                ok
            } else false
        } catch (e: Exception) {
            Log.e("CamMacro", "finishNightStack: ${e.message}")
            false
        }
        stacker?.release()
        finishShot(ok)
    }

    private fun abortNight() {
        if (!nightActive.compareAndSet(true, false)) return
        nightWatchdog?.let { uiHandler.removeCallbacks(it) }
        nightWatchdog = null
        val stacker = nightStacker
        nightStacker = null
        // El release va EN EL HILO DEL APILADO. Aquí se llega desde el vigilante (hilo de
        // UI) y liberar los acumuladores mientras addFrame sigue escribiendo en ellos es
        // exactamente cómo se revienta el proceso entero.
        val h = stackHandler
        if (h != null) h.post { stacker?.release() } else stacker?.release()
        finishShot(false)
    }

    /**
     * Hilo exclusivo del apilado. NO puede ser el de la cámara: mientras corren los ~130
     * millones de iteraciones de siete fotogramas a 12,6 MP, ese hilo no entrega ningún
     * callback y el visor se queda congelado (y las capturas siguientes, en cola).
     */
    private fun ensureStackHandler(): Handler {
        val actual = stackHandler
        if (actual != null) return actual
        val t = HandlerThread("NightStack").also { it.start() }
        stackThread = t
        val nuevo = Handler(t.looper)
        stackHandler = nuevo
        return nuevo
    }

    private fun stopStackThread() {
        // quitSafely y SIN join: el hilo termina lo que tenga en cola por su cuenta. Un
        // join aquí sería otro bloqueo del hilo de UI en onPause, justo lo que causa ANRs.
        stackThread?.quitSafely()
        stackThread = null
        stackHandler = null
    }

    /** Rota un JPEG horneando la rotación en píxeles (YuvImage no escribe orientación EXIF). */
    private fun rotateJpeg(bytes: ByteArray, degrees: Int): ByteArray? {
        return try {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val bos = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            if (rotated != src) rotated.recycle()
            src.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** Filtro de color aplicado a la foto (null = sin filtro). */
    fun setCaptureColorMatrix(cm: ColorMatrix?) {
        captureMatrix = cm
    }

    /** Aplica una ColorMatrix al JPEG (decodifica, pinta con filtro, recodifica). */
    private fun applyColorFilter(bytes: ByteArray, cm: ColorMatrix): ByteArray? {
        return try {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)
            src.recycle()
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            Log.e("CamMacro", "applyColorFilter: ${e.message}")
            null
        }
    }

    /**
     * Modo Full: endereza el JPEG según EXIF y lo recorta (centrado) a la proporción de la
     * pantalla, para que la foto coincida EXACTO con la vista previa a pantalla completa.
     */
    private fun cropFullJpeg(bytes: ByteArray): ByteArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val orient = try {
                android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                    .getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
            } catch (e: Exception) {
                android.media.ExifInterface.ORIENTATION_NORMAL
            }
            val deg = when (orient) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (deg != 0) {
                val m = Matrix().apply { postRotate(deg.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r != bmp) bmp.recycle()
                bmp = r
            }
            val dm = activity.resources.displayMetrics
            val sw = minOf(dm.widthPixels, dm.heightPixels).toFloat()
            val sh = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
            val target = sw / sh // proporción vertical de la pantalla (ancho/alto)
            val w = bmp.width
            val h = bmp.height
            val cur = w.toFloat() / h
            val cropped = if (cur > target) {
                val nw = (h * target).toInt().coerceIn(1, w)
                Bitmap.createBitmap(bmp, (w - nw) / 2, 0, nw, h)
            } else {
                val nh = (w / target).toInt().coerceIn(1, h)
                Bitmap.createBitmap(bmp, 0, (h - nh) / 2, w, nh)
            }
            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            if (cropped != bmp) cropped.recycle()
            bmp.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            Log.e("CamMacro", "cropFullJpeg: ${e.message}")
            null
        }
    }

    /** ID de la primera lente frontal (selfie), o null. */
    fun frontLensId(): String? = frontLensIds().firstOrNull()

    /** Todas las lentes frontales (incluye la de la pantalla interna del plegable). */
    fun frontLensIds(): List<String> {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            manager.cameraIdList.filter {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun applyAndUpdate() {
        onCameraThread {
            val b = previewRequestBuilder ?: return@onCameraThread
            applyControls(b)
            updatePreview()
        }
    }

    /** Aplica zoom, AE-lock y el modo de enfoque actual al builder. */
    /**
     * Recupera el detalle fino. ColorOS aplica por defecto NOISE_REDUCTION HIGH_QUALITY,
     * que emborrona texturas (césped, tela, pelo) y deja la foto "plastificada": un análisis
     * a nivel de píxel midió ~1.5-2 MP de detalle real dentro de un archivo de 12.6 MP.
     * Con ISO bajo pedimos MINIMAL (máximo detalle); con ISO alto, FAST (equilibrio).
     */
    private fun applyDetailModes(b: CaptureRequest.Builder, iso: Int) {
        // Escalera según el ISO REAL DE ESTA FOTO (no el del visor): con luz buena
        // priorizamos detalle; con ISO alto hace falta denoise o la foto se ve llena de
        // grano al ampliarla. El umbral de MINIMAL sube de 800 a 1600 porque a ISO 800 este
        // sensor todavía tiene grano fino y el HAL respondía emborronando texturas
        // (césped, tela, pelo): el famoso efecto acuarela medido por el jurado.
        val nr = when {
            iso < NR_MINIMAL_MAX_ISO && nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ->
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            iso < NR_FAST_MAX_ISO && nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) ->
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) ->
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) ->
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        nr?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        if (edgeAvailable.contains(CameraMetadata.EDGE_MODE_FAST)) {
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        }
        // Aberración cromática: esta lente es un gran angular de 2,3 mm, justo el diseño que
        // más franja púrpura deja en los bordes de alto contraste. El HAL declara los tres
        // modos [OFF, FAST, HIGH_QUALITY] y no se le estaba pidiendo ninguno, así que
        // quedaba a lo que decidiera ColorOS. En la foto vale la pena pagar HIGH_QUALITY.
        val ab = when {
            aberrationAvailable.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY) ->
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            aberrationAvailable.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST) ->
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
            else -> null
        }
        ab?.let { b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
    }

    /**
     * Comprueba en el CaptureResult que el HAL haya hecho caso. ColorOS puede ignorar
     * NOISE_REDUCTION_MODE y EDGE_MODE y aplicar su HIGH_QUALITY de siempre: es la
     * explicación candidata del efecto acuarela (~3 MP de detalle real dentro de un archivo
     * de 12,58 MP). Sin registrarlo, cualquier ajuste de detalle es una apuesta a ciegas;
     * así queda en el logcat y se puede leer por ADB tras cada foto.
     */
    private fun auditDetailModes(request: CaptureRequest, result: TotalCaptureResult) {
        val nrPedido = request.get(CaptureRequest.NOISE_REDUCTION_MODE)
        val nrReal = result.get(CaptureResult.NOISE_REDUCTION_MODE)
        val edgePedido = request.get(CaptureRequest.EDGE_MODE)
        val edgeReal = result.get(CaptureResult.EDGE_MODE)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        Log.i(
            "CamMacro",
            "foto: ISO$iso ${expNs / 1000}us · NR pedido=$nrPedido real=$nrReal · " +
                "EDGE pedido=$edgePedido real=$edgeReal"
        )
        if (nrPedido != null && nrReal != null && nrPedido != nrReal) {
            Log.w("CamMacro", "el HAL IGNORÓ NOISE_REDUCTION_MODE: pedido $nrPedido, aplicado $nrReal")
        }
        if (edgePedido != null && edgeReal != null && edgePedido != edgeReal) {
            Log.w("CamMacro", "el HAL IGNORÓ EDGE_MODE: pedido $edgePedido, aplicado $edgeReal")
        }
    }

    /**
     * Velocidad mínima de obturación en la FOTO: 0 = automático.
     * Igual que setManualFocusDistance, hoy no lo llama nadie pero NO es código muerto: es
     * el motor del chip "ACCIÓN" (1/60, 1/125, 1/250, 1/500) que la interfaz debe exponer.
     */
    fun setShutterFloorNs(ns: Long) { shutterFloorNs = ns }
    val shutterFloor: Long get() = shutterFloorNs

    /**
     * Congela el movimiento: si el AE quiere una exposición más lenta que el piso,
     * fija manualmente una exposición corta y sube el ISO para compensar. Sin esto,
     * en interiores la foto sale a 1/15-1/30 s y cualquier movimiento la emborrona.
     * Devuelve el ISO que ha fijado, o 0 si no tocó la exposición (lo necesita
     * applyDetailModes: el denoise hay que elegirlo con el ISO de LA FOTO, no con el del
     * visor, que puede diferir en cuatro pasos).
     */
    private fun applyShutterFloor(b: CaptureRequest.Builder): Int {
        if (shutterFloorNs <= 0L || manualExposure || !manualSensorSupported) return 0
        if (flashMode == 1 || flashMode == 2) return 0 // con flash manda applyFlashExposure
        if (lastAeExpNs <= 0L || lastAeIso <= 0) return 0
        val floor = shutterFloorNs.coerceAtLeast(if (expMinNs > 0) expMinNs else 1L)
        if (lastAeExpNs <= floor) return 0 // el AE ya es suficientemente rápido

        val ceiling = minOf(isoMax, isoCeilingForFloor)
        // REGLA DE ORO: este piso NUNCA puede entregar una foto más oscura que el visor.
        // Si el AE ya está pidiendo tanto ISO como el que estamos dispuestos a dar, es de
        // noche: no hay movimiento que congelar, solo luz que perder. Se deja al AE del
        // HAL, que en la foto (sin rango de FPS) puede llegar a 1/4 s o más.
        // Sin esta salida, en un cuarto a oscuras se disparaba a 1/30 s con el ISO
        // recortado de 6400 a 3200: un stop menos que el visor y la foto salía negra.
        if (lastAeIso >= ceiling) return 0

        // Luz que midió el AE (tiempo x sensibilidad). Es lo que hay que conservar exacto.
        val luz = lastAeExpNs.toDouble() * lastAeIso
        var iso = Math.round(luz / floor).toInt()
        var targetExp = floor
        if (iso > ceiling) {
            // No cabe en el techo de ISO: se alarga el tiempo lo justo para conservar
            // TODA la luz. Preferimos algo de trepidación antes que grano... o negro.
            iso = ceiling
            targetExp = Math.round(luz / iso)
            if (targetExp >= lastAeExpNs) return 0 // no ganaríamos nada: fuera
        }
        val isoFinal = iso.coerceIn(isoMin, isoMax)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, isoFinal)
        b.set(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            targetExp.coerceIn(expMinNs.coerceAtLeast(1L), expMaxNs)
        )
        return isoFinal
    }

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean = false) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExpNs)
        } else {
            // El modo de flash debe ir TAMBIÉN en la petición repetida (el visor), no solo
            // en la foto: si el HAL no lo conoce de antemano no prepara el flash y la
            // captura sale SIN destello (verificado por EXIF: se pedía flash y no encendía).
            b.set(
                CaptureRequest.CONTROL_AE_MODE,
                when {
                    !flashAvailable || flashMode == 0 || flashMode == 3 ->
                        CaptureRequest.CONTROL_AE_MODE_ON
                    flashMode == 1 -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    else -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                }
            )
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
            // El rango de FPS es para el VISOR. En la foto lo omitimos: si no, ata la
            // exposición al ritmo del preview en vez de dejar que el AE elija bien.
            if (!still) aeFpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        if (manualWb && awbOffSupported) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(wbKelvin))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }
        // Estabilización: OIS siempre; EIS solo en video (en foto recorta y no aporta).
        // OIS y EIS a la vez pueden pelearse: si hay EIS en video, dejamos que mande EIS.
        val useEis = videoSessionActive && eisAvailable
        if (oisAvailable && !useEis) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (useEis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (flashAvailable) {
            // OJO: en flash AUTO/ON no se debe tocar FLASH_MODE. Un FLASH_MODE explícito
            // manda sobre el HAL, así que el FLASH_MODE_OFF que había aquí anulaba el
            // CONTROL_AE_MODE_ON_(AUTO|ALWAYS)_FLASH y el flash NUNCA encendía (la linterna
            // sí funcionaba porque fija FLASH_MODE_TORCH). Verificado por EXIF.
            // El VISOR nunca destella: TORCH solo en linterna, OFF en el resto.
            // El destello de la foto se ordena explícitamente en captureStillNow
            // (FLASH_MODE_SINGLE), porque el builder se reutiliza y "no fijar" la clave
            // dejaría pegado el OFF anterior.
            b.set(
                CaptureRequest.FLASH_MODE,
                if (flashMode == 3) CameraMetadata.FLASH_MODE_TORCH
                else CameraMetadata.FLASH_MODE_OFF
            )
        }
        when {
            manualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualDiopters)
            }
            afLocked && afAvailable ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            // En video el modo correcto es CONTINUOUS_VIDEO (enfoque suave, sin "cazar" foco).
            videoSessionActive && afVideoSupported ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            afContinuousSupported ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            afAvailable ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            else ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        applyZoom(b)
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
        } catch (e: Exception) {
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // Cachear la exposición auto medida para bloquearla en el modo noche.
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExpNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
            if (!firstFrameNotified) {
                firstFrameNotified = true
                activity.runOnUiThread { onFirstFrame?.invoke() }
            }
            // ¿Hay una foto esperando a que el AE (y el flash) terminen la pre-captura?
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            if (ae != null) lastAeState = ae
            // Solo valen los resultados POSTERIORES al disparador (ver aeTriggerFrame).
            if (aeWaitAction != null && result.frameNumber >= aeTriggerFrame) {
                // MÁQUINA DE DOS FASES. Justo después de AE_PRECAPTURE_TRIGGER_START el HAL
                // sigue reportando el CONVERGED viejo durante 1-2 fotogramas antes de entrar
                // en PRECAPTURE. La condición anterior aceptaba ese CONVERGED (¡y hasta un
                // estado nulo!) en el primer resultado, así que se capturaba con la exposición
                // vieja y con el flash sin cargar: el origen de toda la saga de bugs de flash.
                // Ahora hay que VER primero PRECAPTURE; si el HAL nunca lo reporta, manda el
                // timeout de AE_PRECAPTURE_MAX_MS, no un estado heredado.
                if (ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) aeSawPrecapture = true
                val listo = aeSawPrecapture && (
                    ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                        ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                    )
                if (listo) {
                    val action = aeWaitAction
                    aeWaitAction = null
                    activity.runOnUiThread { action?.invoke() }
                }
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            // El estado CRUDO manda en la espera; el mapeado es solo para pintar la UI.
            // Colapsar PASSIVE_FOCUSED en FOCUSED está bien para el anillo del visor, pero
            // usarlo para decidir cuándo disparar era la causa viva de las fotos blandas:
            // tras AF_TRIGGER_START el HAL tarda 1-3 fotogramas en pasar a ACTIVE_SCAN, así
            // que el primer resultado posterior al disparador todavía trae el estado PASIVO
            // anterior y se capturaba sin haber enfocado. Los estados _LOCKED solo aparecen
            // cuando un barrido ACTIVO ha terminado de verdad.
            if (af == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN) afSawActiveScan = true
            val mapped = when (af) {
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN,
                CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> FocusState.SCANNING
                CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> FocusState.FOCUSED
                CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> FocusState.NOT_FOCUSED
                else -> FocusState.INACTIVE
            }
            if (mapped != lastFocusState) {
                lastFocusState = mapped
                activity.runOnUiThread { onFocusState?.invoke(mapped) }
            }
            // ¿Hay un disparo esperando a que el enfoque converja? Dispara ya.
            val afSettled = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            if (afWaitAction != null && result.frameNumber >= afTriggerFrame && afSettled) {
                val action = afWaitAction
                afWaitAction = null
                activity.runOnUiThread { action?.invoke() }
            }
        }
    }

    private fun meteringRect(nx: Float, ny: Float): Rect? {
        val arr = activeArray ?: return null
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        // Con CONTROL_ZOOM_RATIO las regiones 3A se expresan sobre el array activo que YA
        // representa el encuadre con zoom aplicado. Volver a dividir por zoomRatio aplicaba
        // el zoom DOS veces: a 4x el toque se comprimía al centro y la región de enfoque
        // quedaba minúscula (enfoque lento e impreciso al hacer zoom).
        val useRatio = zoomRatioSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val cropW: Float
        val cropH: Float
        val cropLeft: Float
        val cropTop: Float
        if (useRatio) {
            cropW = arr.width().toFloat()
            cropH = arr.height().toFloat()
            cropLeft = arr.left.toFloat()
            cropTop = arr.top.toFloat()
        } else {
            cropW = arr.width() / zoomRatio
            cropH = arr.height() / zoomRatio
            cropLeft = arr.exactCenterX() - cropW / 2f
            cropTop = arr.exactCenterY() - cropH / 2f
        }
        val sx: Float
        val sy: Float
        when (sensorOrientation) {
            90 -> { sx = ny; sy = 1f - nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            270 -> { sx = 1f - ny; sy = nx }
            else -> { sx = nx; sy = ny }
        }
        val cx = cropLeft + sx * cropW
        val cy = cropTop + sy * cropH
        val half = minOf(cropW, cropH) * 0.07f
        var l = (cx - half).toInt()
        var t = (cy - half).toInt()
        var r = (cx + half).toInt()
        var b = (cy + half).toInt()
        l = l.coerceIn(arr.left, arr.right - 2)
        t = t.coerceIn(arr.top, arr.bottom - 2)
        r = r.coerceIn(l + 1, arr.right)
        b = b.coerceIn(t + 1, arr.bottom)
        return Rect(l, t, r, b)
    }

    // ---------------------------------------------------------------- Interno

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        val ok: Boolean
        try {
            image = reader.acquireNextImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            ok = saveImage(bytes)
        } catch (e: Exception) {
            Log.e("CamMacro", "onImageAvailable: ${e.message}")
            try { image?.close() } catch (c: Exception) {}
            // finishShot y no solo el callback: si saveImage revienta hay que soltar el AF
            // igual, o todas las fotos siguientes salen clavadas a ESTA distancia.
            finishShot(false)
            return@OnImageAvailableListener
        }
        try { image?.close() } catch (e: Exception) {}
        finishShot(ok)
    }

    val hasRaw: Boolean get() = rawSupported

    private val onRawAvailable = ImageReader.OnImageAvailableListener { reader ->
        try {
            // No depender del orden: guardamos la imagen y emparejamos con la metadata.
            // Cerrando SIEMPRE la anterior: sobrescribirla sin cerrar dejaba el reader (de
            // solo 2 buffers) sin sitio y el RAW moría hasta reabrir la cámara.
            synchronized(rawLock) {
                try { pendingRawImage?.close() } catch (e: Exception) {}
                pendingRawImage = reader.acquireNextImage()
            }
            tryFlushDng()
        } catch (e: Exception) {
            Log.e("CamMacro", "onRawAvailable: ${e.message}")
        }
    }

    /**
     * Escribe el DNG solo cuando la Imagen RAW y su TotalCaptureResult están AMBOS presentes.
     * Se llama desde onRawAvailable y desde onCaptureCompleted (ambos en backgroundHandler,
     * por lo que están serializados: no hay carrera de datos).
     */
    private fun tryFlushDng() {
        val chars = camChars ?: return
        val par = synchronized(rawLock) {
            val img = pendingRawImage
            val res = pendingRawResult
            if (img != null && res != null) {
                pendingRawImage = null
                pendingRawResult = null
                Pair(img, res)
            } else null
        } ?: return
        val ok = saveDng(par.first, par.second, chars)
        try { par.first.close() } catch (e: Exception) {}
        activity.runOnUiThread { onRawSaved?.invoke(ok) }
    }

    private fun saveDng(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics): Boolean {
        return try {
            val name = "MACRO_${System.currentTimeMillis()}.dng"
            val resolver = activity.contentResolver
            val dng = DngCreator(chars, result)
            dng.setOrientation(exifOrientation())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    dng.close()
                    Log.e("CamMacro", "saveDng: insert devolvió null")
                    return false
                }
                resolver.openOutputStream(uri)?.use { dng.writeImage(it, image) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val dir = File(pics, "CamaraMacro").apply { if (!exists()) mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { dng.writeImage(it, image) }
                MediaScannerConnection.scanFile(
                    activity, arrayOf(file.absolutePath), arrayOf("image/x-adobe-dng"), null
                )
            }
            dng.close()
            true
        } catch (e: Exception) {
            Log.e("CamMacro", "saveDng falló: ${e.message}")
            false
        }
    }

    private fun exifOrientation(): Int {
        return when (currentJpegOrientation()) {
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * TODA la apertura se hace en el hilo de la cámara. Antes se llamaba desde el hilo de
     * UI (open, listener del TextureView) mientras postRebuildSession y onConfigureFailed
     * ejecutaban setUpOutputs desde el de fondo: dos ejecuciones solapadas podían cerrar dos
     * veces el mismo ImageReader o dejar huérfano el que acababa de crear la otra.
     */
    private fun openCamera() {
        val h = backgroundHandler
        if (h == null) openCameraNow() else h.post { openCameraNow() }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraNow() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val gen = ++cameraGen
        firstFrameNotified = false
        try {
            setUpOutputs(manager)
            configureTransform(textureView.width, textureView.height)
            startWatchdog(gen)
            // El StateCallback se registra en el hilo de UI A PROPÓSITO. Con el hilo de
            // fondo, si close() lo paraba mientras la apertura seguía en vuelo, el onOpened
            // NO se entregaba nunca y el CameraDevice se quedaba abierto sin que nadie lo
            // cerrara: en ColorOS la lente queda retenida por nuestro propio proceso y la
            // apertura siguiente muere con MAX_CAMERAS_IN_USE. El hilo de UI nunca muere.
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != cameraGen) { try { camera.close() } catch (e: Exception) {}; return }
                    cameraDevice = camera
                    val bg = backgroundHandler
                    if (bg == null) {
                        // El motor ya no existe (close() en vuelo): cerrar y no dejar rastro.
                        cameraDevice = null
                        try { camera.close() } catch (e: Exception) {}
                        return
                    }
                    bg.post { if (gen == cameraGen) startPreview() }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen != cameraGen) return
                    // En ColorOS esta es LA ruta cuando otra app se lleva la cámara. Antes
                    // se dejaba la sesión muerta y la captura colgada: el obturador quedaba
                    // inservible para siempre y sin avisar.
                    cameraDevice = null
                    previewRequestBuilder = null
                    try { captureSession?.close() } catch (e: Exception) {}
                    captureSession = null
                    abortPendingCapture()
                    activity.runOnUiThread { onError?.invoke("Otra app tomó la cámara") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen != cameraGen) return
                    cameraDevice = null
                    previewRequestBuilder = null
                    try { captureSession?.close() } catch (e: Exception) {}
                    captureSession = null
                    abortPendingCapture() // no dejar el obturador colgado
                    fail(describeCameraError(error))
                }
            }, uiHandler)
        } catch (e: Exception) {
            fail("No se pudo abrir esta lente: ${e.message}")
        }
    }

    /**
     * Un "error 4" no le dice nada a nadie, y las causas piden acciones distintas: esperar,
     * cerrar la otra app o cambiar de lente. El usuario solo veía un aviso genérico.
     */
    private fun describeCameraError(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
            "Otra app está usando esta lente. Ciérrala y vuelve a intentarlo."
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            "El teléfono ya tiene abiertas todas las cámaras que admite. Espera un momento."
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
            "La cámara está desactivada por una política del dispositivo."
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
            "Esta lente no respondió (puede ser la dañada). Prueba otra."
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
            "El servicio de cámara del sistema falló. Cierra y vuelve a abrir la app."
        else -> "Esta lente no se pudo abrir (error $error). Prueba otra."
    }

    /**
     * @Synchronized porque se llama desde openCamera y desde postRebuildSession /
     * onConfigureFailed, que viven en hilos distintos. Dos ejecuciones solapadas cerraban
     * dos veces el mismo ImageReader, dejaban huérfano el que acababa de crear la otra, o
     * registraban el listener sobre un reader que ya no estaba en la sesión: fotos que no
     * llegaban nunca y disparo liberado solo por el vigilante.
     */
    @Synchronized
    private fun setUpOutputs(manager: CameraManager) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw RuntimeException("Lente sin configuración de stream")

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        facingFront =
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        afContinuousSupported = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        afVideoSupported = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        afAvailable = afModes.any {
            it == CaptureRequest.CONTROL_AF_MODE_AUTO || it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        // CONTROL_ZOOM_RATIO_RANGE es API 30; en versiones previas usamos SCALER_CROP_REGION.
        val zr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        if (zr != null) {
            zoomRatioSupported = true
            maxZoom = zr.upper
        } else {
            zoomRatioSupported = false
            maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        if (maxZoom < 1f) maxZoom = 1f

        val isoR = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoR != null) { isoMin = isoR.lower; isoMax = isoR.upper }
        val expR = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (expR != null) { expMinNs = expR.lower; expMaxNs = expR.upper }
        val evR = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (evR != null) { evMin = evR.lower; evMax = evR.upper }
        evStepRational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        // FPS del PREVIEW. Fijarlo en [30,30] parecía lo más estable, pero ATA EL AE: con
        // 30 fps garantizados la exposición no puede pasar de 1/30 s, así que en un cuarto
        // a oscuras el AE se queda sin recorrido, sube el ISO al tope y lastAeExpNs miente
        // (dice 1/30 cuando la escena pedía 1/4). De ahí salían el visor sucio y la foto
        // negra. Elegimos el rango de tope 30 con la cota INFERIOR más baja ([10,30] en
        // este sensor): con luz sigue a 30 fps y a oscuras el AE puede bajar a 1/10 s.
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        aeFpsRange = fpsRanges?.filter { it.upper == 30 }?.minByOrNull { it.lower }
            ?: fpsRanges?.filter { it.upper <= 30 }?.maxByOrNull { it.upper }
            ?: fpsRanges?.minByOrNull { it.lower }
        val manualCaps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensorSupported =
            manualCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        oisAvailable = ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        // Focal real y equivalente 35 mm de la lente activa (para mostrarla en la UI).
        activeFocalMm = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        activeEquivMm = if (physSize != null && activeFocalMm > 0f) {
            val diag = kotlin.math.sqrt(
                (physSize.width * physSize.width + physSize.height * physSize.height).toDouble()
            )
            if (diag > 0) Math.round(activeFocalMm * 43.27 / diag).toInt() else 0
        } else 0
        nrAvailable = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        ) ?: IntArray(0)
        edgeAvailable = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        aberrationAvailable = characteristics.get(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
        ) ?: IntArray(0)
        // El zoom máximo de ESTA lente también entra en la tabla: si el usuario abre
        // directamente una lente que no está en la cadena, tailDigitalZoom la encuentra.
        lensMaxZoom[cameraId] = maxZoom.coerceAtLeast(1f)
        val eis = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        eisAvailable = eis.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        awbOffSupported = awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        // Tamaño según relación de aspecto y resolución elegidas; el preview adopta este aspecto.
        val largest = pickJpegSize(jpegSizes)

        val recSizes = map.getOutputSizes(MediaRecorder::class.java)
        availableVideoSizes = recSizes?.toList() ?: emptyList()
        videoSize = recSizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: recSizes?.filter { it.width <= 1920 }?.maxByOrNull { it.width.toLong() * it.height }
            ?: recSizes?.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
        // ¿Ultra HDR disponible a este tamaño? Si sí y está activado, el stream de la foto
        // es JPEG_R en vez de JPEG: mismo número de streams, mucho más rango dinámico.
        hdrSupported = false
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val jr = map.getOutputSizes(ImageFormat.JPEG_R)
                hdrSupported = jr != null && jr.any { it.width == largest.width && it.height == largest.height }
            } catch (e: Exception) {
                hdrSupported = false
            }
        }
        // DESEO contra CAPACIDAD. hdrSupported no se sabe hasta este punto (hace falta la
        // lente abierta), pero la Activity restaura sus ajustes en onCreate: setHdrEnabled
        // devolvía SIEMPRE false y el Ultra HDR se apagaba solo en cada arranque mientras la
        // preferencia guardada seguía diciendo que sí. Aquí se resuelve y se avisa a la UI
        // del valor REAL.
        val hdrAntes = hdrEnabled
        hdrEnabled = hdrRequested && hdrSupported
        if (hdrRequested && !hdrSupported) activity.runOnUiThread { onHdrUnavailable?.invoke() }
        if (hdrAntes != hdrEnabled) activity.runOnUiThread { onCaptureModesChanged?.invoke() }
        val stillFormat = if (hdrEnabled) ImageFormat.JPEG_R else ImageFormat.JPEG
        // Cerrar el anterior. Sin esto, cada intento fallido de JPEG_R y cada reconstrucción
        // de sesión filtraba un ImageReader a resolución máxima: decenas de MB de memoria
        // nativa que nadie recuperaba (rawReader, nightReader y qrReader sí se cerraban).
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = ImageReader.newInstance(largest.width, largest.height, stillFormat, 2).apply {
            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        }
        Log.i("CamMacro", "still=${if (stillFormat == ImageFormat.JPEG) "JPEG" else "JPEG_R/UltraHDR"} hdrSupported=$hdrSupported")

        camChars = characteristics
        val rawCaps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        rawSupported = rawCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        try { rawReader?.close() } catch (e: Exception) {}
        rawReader = null
        if (rawSupported) {
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val largestRaw = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
            if (largestRaw != null) {
                rawReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener(onRawAvailable, backgroundHandler)
                }
            } else {
                rawSupported = false
            }
        }

        // Modo noche: stream YUV de tamaño moderado, solo cuando está activo.
        try { nightReader?.close() } catch (e: Exception) {}
        nightReader = null
        if (nightEnabled) {
            // El tope fijo de 4 MP que había aquí degradaba la foto de noche a 3.7 MP frente
            // a los 12.6 MP del modo normal: el modo EMPEORABA la imagen. Ahora se apila a la
            // máxima resolución que quepa de verdad en el heap (acumuladores ~4.25 B/px con
            // Short/Byte, más los buffers YUV en vuelo y el NV21 de salida).
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf(Size(1920, 1080))
            val nightCands = sizesForAspect(yuvSizes).sortedByDescending { it.width.toLong() * it.height }
            val budget = Runtime.getRuntime().maxMemory() / 3 // margen para el resto de la app
            nightSize = nightCands.firstOrNull { it.width.toLong() * it.height * 11L <= budget }
                ?: nightCands.lastOrNull()
                ?: previewSize
            Log.i("CamMacro", "nightSize=$nightSize budget=${budget / 1048576}MB")
            // El listener entrega los fotogramas en el HILO DEL APILADO, no en el de la
            // cámara: apilar 7 fotogramas de 12,6 MP allí dejaba al HAL sin quien atendiera
            // sus callbacks y congelaba el visor entero durante segundos.
            nightReader = ImageReader.newInstance(
                nightSize.width, nightSize.height, ImageFormat.YUV_420_888, NIGHT_FRAMES + 1
            ).apply { setOnImageAvailableListener(onNightImage, ensureStackHandler()) }
        }

        // QR: stream YUV continuo de baja resolución solo cuando está activo.
        try { qrReader?.close() } catch (e: Exception) {}
        qrReader = null
        if (qrEnabled) {
            // close() deja qrScanner en null, pero qrEnabled sobrevive: sin recrear el
            // scanner aquí, al volver de pausar la app el lector de QR quedaba MUERTO
            // en silencio para siempre (onQrImage salía por scanner == null).
            if (qrScanner == null) qrScanner = BarcodeScanning.getClient()
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val qrSize = yuvSizes?.filter { it.width <= 1280 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes?.minByOrNull { it.width.toLong() * it.height }
                ?: Size(1280, 720)
            qrReader = ImageReader.newInstance(qrSize.width, qrSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener(onQrImage, backgroundHandler)
            }
        }

        @Suppress("DEPRECATION")
        val displayRotation = activity.windowManager.defaultDisplay.rotation
        val swapped = areDimensionsSwapped(displayRotation)

        val displaySize = Point()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getSize(displaySize)

        val rotatedViewWidth = if (swapped) textureView.height else textureView.width
        val rotatedViewHeight = if (swapped) textureView.width else textureView.height
        val maxPreviewWidth = (if (swapped) displaySize.y else displaySize.x).coerceAtMost(MAX_PREVIEW_WIDTH)
        val maxPreviewHeight = (if (swapped) displaySize.x else displaySize.y).coerceAtMost(MAX_PREVIEW_HEIGHT)

        val previewChoices = map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
        previewSize = chooseOptimalSize(
            previewChoices,
            if (rotatedViewWidth > 0) rotatedViewWidth else maxPreviewWidth,
            if (rotatedViewHeight > 0) rotatedViewHeight else maxPreviewHeight,
            maxPreviewWidth,
            maxPreviewHeight,
            largest
        )

        activity.runOnUiThread {
            // En la pantalla grande del plegable el visor llena la pantalla; en la alargada
            // se muestra completo para que lo que ves sea lo que sale.
            textureView.coverMode = (aspect == AspectRatio.FULL) ||
                activity.resources.getBoolean(R.bool.preview_fills_screen)
            val orientation = activity.resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
        }
    }

    private fun startPreview(reintento: Int = 0) {
        try {
            val texture = textureView.surfaceTexture
            val device = cameraDevice
            val reader = imageReader
            if (texture == null || device == null || reader == null) {
                // Antes se salía EN SILENCIO. Si entre encolar la reconstrucción y ejecutarla
                // había habido un switchToLens, un onDisconnected o un close(), la sesión
                // quedaba destruida y nadie la volvía a levantar: pantalla negra, obturador
                // inútil y ni un solo mensaje de error. Ahora o se reintenta o se avisa.
                if (device == null) {
                    Log.i("CamMacro", "startPreview sin cámara abierta: nada que reconstruir")
                    return
                }
                if (reintento >= PREVIEW_RETRIES) {
                    fail("La vista previa no pudo arrancar. Cierra y vuelve a abrir la app.")
                    return
                }
                Log.w("CamMacro", "startPreview sin superficie; reintento ${reintento + 1}")
                val gen = cameraGen
                uiHandler.postDelayed({
                    if (gen != cameraGen) return@postDelayed
                    val h = backgroundHandler
                    if (h == null) startPreview(reintento + 1) else h.post { startPreview(reintento + 1) }
                }, PREVIEW_RETRY_MS)
                return
            }
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            this.previewRequestBuilder = previewRequestBuilder
            previewRequestBuilder.addTarget(surface)
            if (qrEnabled) qrReader?.let { previewRequestBuilder.addTarget(it.surface) }
            firstFrameNotified = false

            val outputs = mutableListOf(surface, reader.surface)
            if (rawEnabled) rawReader?.let { outputs.add(it.surface) }
            if (nightEnabled) nightReader?.let { outputs.add(it.surface) }
            if (qrEnabled) qrReader?.let { outputs.add(it.surface) }
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            // Sin este close, la sesión recién configurada quedaba huérfana y
                            // VIVA, reteniendo los buffers de la lente que acabamos de dejar.
                            try { session.close() } catch (e: Exception) {}
                            return
                        }
                        captureSession = session
                        if (pendingResidual >= 0f) {
                            zoomRatio = pendingResidual.coerceIn(1f, maxZoom)
                            pendingResidual = -1f
                        }
                        switching = false
                        try {
                            applyControls(previewRequestBuilder)
                            session.setRepeatingRequest(
                                previewRequestBuilder.build(),
                                previewCallback,
                                backgroundHandler
                            )
                            cancelWatchdog()
                            activity.runOnUiThread { onReady?.invoke() }
                        } catch (e: Exception) {
                            fail("No se pudo iniciar la vista previa: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        // La sesión fallida hay que cerrarla: no lo hacía ni esta ruta ni la
                        // del fallback de RAW, y cada intento dejaba una sesión muerta viva.
                        try { session.close() } catch (e: Exception) {}
                        // Si falló con RAW (3 streams), degradar a 2 streams en vez de matar la cámara.
                        if (rawEnabled && !rawFallbackTried) {
                            rawFallbackTried = true
                            rawEnabled = false
                            try { rawReader?.close() } catch (e: Exception) {}
                            rawReader = null
                            Log.e("CamMacro", "RAW no soportado en 3 streams; cayendo a JPEG")
                            activity.runOnUiThread { onRawUnavailable?.invoke() }
                            startPreview()
                            return
                        }
                        // Si falló con Ultra HDR (JPEG_R), volver a JPEG normal en vez de
                        // dejar la cámara muerta: hay que RECREAR el ImageReader porque
                        // cambia el formato del stream.
                        if (hdrEnabled && !hdrFallbackTried) {
                            hdrFallbackTried = true
                            hdrEnabled = false
                            // También el DESEO: si no, setUpOutputs volvería a poner JPEG_R
                            // (hdrEnabled = hdrRequested && hdrSupported) y el fallback
                            // reconfiguraría exactamente la sesión que acaba de fallar.
                            hdrRequested = false
                            Log.e("CamMacro", "Ultra HDR no configurable; cayendo a JPEG")
                            try {
                                val mgr = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                setUpOutputs(mgr)
                            } catch (e: Exception) {
                                Log.e("CamMacro", "fallback HDR: ${e.message}")
                            }
                            activity.runOnUiThread { onHdrUnavailable?.invoke() }
                            startPreview()
                            return
                        }
                        fail("No se pudo configurar esta lente. Prueba otra.")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            fail("Error al iniciar la vista previa: ${e.message}")
        }
    }

    /**
     * Escribe los EXIF de la foto de noche. YuvImage.compressToJpeg no conserva ninguno,
     * así que el archivo salía sin ISO, exposición, focal ni apertura.
     */
    private fun writeNightExif(uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            activity.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ex = android.media.ExifInterface(pfd.fileDescriptor)
                val iso = if (manualExposure) manualIso else lastAeIso
                val expNs = if (manualExposure) manualExpNs else lastAeExpNs
                ex.setAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
                ex.setAttribute(
                    android.media.ExifInterface.TAG_EXPOSURE_TIME,
                    (expNs / 1_000_000_000.0).toString()
                )
                if (activeFocalMm > 0f) ex.setAttribute(
                    android.media.ExifInterface.TAG_FOCAL_LENGTH,
                    "${(activeFocalMm * 100).toInt()}/100"
                )
                camChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull()?.let {
                        ex.setAttribute(
                            android.media.ExifInterface.TAG_F_NUMBER,
                            "${(it * 100).toInt()}/100"
                        )
                    }
                ex.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "Camara · modo noche")
                ex.setAttribute(
                    android.media.ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "Apilado multi-frame de $NIGHT_FRAMES fotogramas (lente ID$cameraId)"
                )
                ex.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e("CamMacro", "writeNightExif: ${e.message}")
        }
    }

    private fun saveImage(rawBytes: ByteArray): Boolean {
        // En modo Full recortamos a la proporción de la pantalla (foto = lo que se ve).
        // En Ultra HDR NO se puede recortar ni filtrar: se perdería el mapa de ganancia
        // embebido y la foto dejaría de ser HDR.
        val ultraHdr = hdrEnabled && hdrSupported
        var bytes = if (!ultraHdr && aspect == AspectRatio.FULL) cropFullJpeg(rawBytes) ?: rawBytes else rawBytes
        if (!ultraHdr) captureMatrix?.let { bytes = applyColorFilter(bytes, it) ?: bytes }
        // Miniatura inmediata: no esperamos a que MediaStore indexe el archivo.
        onPhotoThumb?.let { cb ->
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                var thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                // Aplicar la rotación del EXIF: si no, la miniatura salía girada y luego
                // "se acomodaba" al recargarse desde la galería.
                if (thumb != null) {
                    val deg = when (
                        try {
                            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                                .getAttributeInt(
                                    android.media.ExifInterface.TAG_ORIENTATION,
                                    android.media.ExifInterface.ORIENTATION_NORMAL
                                )
                        } catch (e: Exception) {
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        }
                    ) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                    if (deg != 0f) {
                        val m = Matrix().apply { postRotate(deg) }
                        val r = Bitmap.createBitmap(thumb, 0, 0, thumb.width, thumb.height, m, true)
                        if (r != thumb) thumb.recycle()
                        thumb = r
                    }
                    val out = thumb
                    activity.runOnUiThread { cb(out) }
                }
            } catch (e: Exception) {
            }
        }
        // Captura pedida por otra app: se la entregamos a ella en vez de a la galería.
        jpegSink?.let { return it(bytes) }
        val name = "MACRO_${System.currentTimeMillis()}.jpg"
        return try {
            val resolver = activity.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ahora = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    // DCIM/Camera: es la carpeta que Google Photos respalda e indexa por
                    // defecto. En Pictures/ las fotos quedaban fuera del respaldo automatico.
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    // Sin DATE_TAKEN, Google Photos no las ordena ni las sube bien.
                    put(MediaStore.Images.Media.DATE_TAKEN, ahora)
                    put(MediaStore.Images.Media.DATE_ADDED, ahora / 1000)
                    put(MediaStore.Images.Media.DATE_MODIFIED, ahora / 1000)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                lastSavedUri = uri
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val pictures =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val dir = File(pictures, "CamaraMacro").apply { if (!exists()) mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
                MediaScannerConnection.scanFile(
                    activity, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
                )
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun currentJpegOrientation(): Int {
        val rounded = (lastOrientationDegrees + 45) / 90 * 90
        val sign = if (facingFront) -1 else 1
        return (sensorOrientation + sign * rounded + 360) % 360
    }

    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        return when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 ->
                sensorOrientation == 90 || sensorOrientation == 270
            Surface.ROTATION_90, Surface.ROTATION_270 ->
                sensorOrientation == 0 || sensorOrientation == 180
            else -> false
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = maxOf(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        activity.runOnUiThread { textureView.setTransform(matrix) }
    }

    // ---------------------------------------------------------------- Watchdog / hilos

    private fun startWatchdog(gen: Int) {
        cancelWatchdog()
        watchdog = Runnable {
            // Solo falla si seguimos en la misma apertura (no tras un flip/switch).
            if (gen == cameraGen) fail("Esta lente no respondió (puede ser la dañada). Prueba otra.")
        }
        uiHandler.postDelayed(watchdog!!, OPEN_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { uiHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun fail(msg: String) {
        // La limpieza va SIEMPRE: antes se salía por 'if (failed) return' antes de limpiar,
        // así que tras el primer fallo cualquier fallo posterior no soltaba el obturador
        // ni cancelaba nada, y la app quedaba muerta en silencio.
        val yaAvisado = failed
        failed = true
        switching = false
        cancelWatchdog()
        abortPendingCapture()
        // Y SE CIERRA LA LENTE. Justo el caso para el que existe el vigilante ("esta lente
        // no respondió, puede ser la dañada") dejaba el CameraDevice ABIERTO y retenido por
        // el proceso, que es exactamente lo que cuelga el HAL de ColorOS. Además se invalida
        // la generación para que un onOpened tardío no la vuelva a asignar.
        cameraGen++
        previewRequestBuilder = null
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        if (!yaAvisado) activity.runOnUiThread { onError?.invoke(msg) } // no repetir el aviso
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            // Con tope de tiempo: si una lente cuelga el HAL, la interfaz no se congela.
            backgroundThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        viewWidth: Int,
        viewHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        aspectRatio: Size
    ): Size {
        if (choices.isEmpty()) return Size(1920, 1080)
        val target = aspectRatio.width.toFloat() / aspectRatio.height
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()
        for (option in choices) {
            // Antes se exigía coincidencia ENTERA exacta de la proporción. Si ningún tamaño
            // de vista previa encajaba, se caía al más grande sin más, que suele ser 4:3:
            // por eso el visor salía en 4:3 estando la foto en 16:9, y aparecía una franja
            // negra enorme. Ahora se admite tolerancia y el respaldo respeta la proporción.
            if (option.width <= maxWidth && option.height <= maxHeight &&
                kotlin.math.abs(option.width.toFloat() / option.height - target) < 0.04f
            ) {
                if (option.width >= viewWidth && option.height >= viewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }
        return when {
            bigEnough.isNotEmpty() -> bigEnough.minByOrNull { it.width.toLong() * it.height }!!
            notBigEnough.isNotEmpty() -> notBigEnough.maxByOrNull { it.width.toLong() * it.height }!!
            // Respaldo: el que MÁS se parezca a la proporción de la foto, no el más grande.
            else -> choices
                .filter { it.width <= maxWidth && it.height <= maxHeight }
                .minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - target) }
                ?: choices.minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - target) }
                ?: choices[0]
        }
    }

    // ---------------------------------------------------------------- Video

    val isRecording: Boolean get() = recording

    // --- Ajustes de video ---
    val supports4kVideo: Boolean get() = availableVideoSizes.any { it.height >= 2100 }
    fun setVideoTargetHeight(h: Int) { videoTargetH = h }
    fun setVideoFps(f: Int) { videoFps = f }
    fun setVideoHevc(on: Boolean) { videoHevc = on }
    fun setTimeLapse(on: Boolean) { timeLapse = on }
    val isTimeLapse: Boolean get() = timeLapse

    /** Elige el tamaño de grabación 16:9 más cercano a la altura objetivo. */
    private fun pickVideoSize(): Size {
        if (availableVideoSizes.isEmpty()) return Size(1920, 1080)
        val wide = availableVideoSizes.filter {
            kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.06f
        }
        val pool = if (wide.isNotEmpty()) wide else availableVideoSizes
        return pool.minByOrNull { kotlin.math.abs(it.height - videoTargetH) }
            ?: pool.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    fun startVideo(withAudio: Boolean): Boolean {
        val device = cameraDevice ?: return false
        if (recording) return false
        try {
            videoSize = pickVideoSize()
            val texture = textureView.surfaceTexture ?: return false
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            // En time-lapse no se graba audio.
            val recorder = createRecorder(withAudio && !timeLapse)
            if (recorder == null) {
                // createRecorder ya pudo abrir el archivo antes de reventar en prepare():
                // sin esto queda una fila de MediaStore pendiente (vídeo fantasma de 0 bytes).
                discardVideoOutput()
                return false
            }
            mediaRecorder = recorder
            val recorderSurface = recorder.surface

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface)
            builder.addTarget(recorderSurface)
            previewRequestBuilder = builder
            // Activa EIS + enfoque continuo de video ANTES de aplicar los controles.
            videoSessionActive = true
            applyControls(builder)
            // 60 fps: pide el rango de FPS al sensor (si la lente lo soporta).
            if (videoFps >= 60) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
            }

            try { captureSession?.close() } catch (e: Exception) {}
            captureSession = null

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            try { session.close() } catch (e: Exception) {}
                            videoStartFailed("La cámara se cerró antes de empezar a grabar.")
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
                            recorder.start()
                            recording = true
                            activity.runOnUiThread { onRecordingChanged?.invoke(true) }
                        } catch (e: Exception) {
                            videoStartFailed("No se pudo iniciar el vídeo: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        try { session.close() } catch (e: Exception) {}
                        videoStartFailed("No se pudo configurar el vídeo.")
                    }
                },
                backgroundHandler
            )
            return true
        } catch (e: Exception) {
            videoStartFailed("Error al iniciar el vídeo: ${e.message}")
            return false
        }
    }

    /**
     * Un fallo al arrancar la grabación dejaba el visor MUERTO: la sesión de foto ya se
     * había cerrado, 'recording' seguía en false (así que stopVideo salía de inmediato y no
     * había manera de recuperarse), el MediaRecorder quedaba preparado reteniendo micrófono
     * y codificador para siempre, el descriptor de archivo abierto y la fila de MediaStore
     * con IS_PENDING=1: un vídeo fantasma. Aquí se deshace todo y se devuelve la vista
     * previa ANTES de contarle el problema al usuario.
     */
    private fun videoStartFailed(msg: String) {
        recording = false
        videoSessionActive = false
        try { mediaRecorder?.reset() } catch (e: Exception) {}
        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        discardVideoOutput()
        activity.runOnUiThread { onRecordingChanged?.invoke(false) }
        startPreview()
        activity.runOnUiThread { onError?.invoke(msg) }
    }

    /** Cierra y BORRA el archivo de un vídeo que nunca llegó a grabarse. */
    private fun discardVideoOutput() {
        try { videoPfd?.close() } catch (e: Exception) {}
        try { videoFos?.close() } catch (e: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoUri?.let { activity.contentResolver.delete(it, null, null) }
            } else {
                videoFile?.delete()
            }
        } catch (e: Exception) {
        }
        if (lastSavedUri != null && lastSavedUri == videoUri) lastSavedUri = null
        videoPfd = null
        videoFos = null
        videoFile = null
        videoUri = null
    }

    fun stopVideo() {
        if (!recording) return
        recording = false
        try { captureSession?.stopRepeating() } catch (e: Exception) {}
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
        }
        mediaRecorder = null
        videoSessionActive = false // vuelve a OIS + enfoque de foto
        finalizeVideo()
        activity.runOnUiThread { onRecordingChanged?.invoke(false) }
        startPreview()
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(withAudio: Boolean): MediaRecorder? {
        return try {
            val recorder = MediaRecorder()
            val out = openVideoOutput() ?: return null
            videoUri = out.second
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(out.first)
            val bitrate = when {
                videoSize.height >= 2000 -> 42_000_000
                videoSize.height >= 1080 -> if (videoFps >= 60) 24_000_000 else 17_000_000
                else -> 9_000_000
            }
            recorder.setVideoEncodingBitRate(bitrate)
            recorder.setVideoFrameRate(videoFps)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoEncoder(
                if (videoHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264
            )
            if (withAudio) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Time-lapse: captura ~2 fps y reproduce a videoFps (acelerado).
            if (timeLapse) recorder.setCaptureRate(2.0)
            recorder.setOrientationHint(currentJpegOrientation())
            recorder.prepare()
            recorder
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun openVideoOutput(): Pair<java.io.FileDescriptor, Uri>? {
        return try {
            val name = "VID_${System.currentTimeMillis()}.mp4"
            val resolver = activity.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ahoraV = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Video.Media.DATE_TAKEN, ahoraV)
                    put(MediaStore.Video.Media.DATE_ADDED, ahoraV / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, ahoraV / 1000)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                lastSavedUri = uri
                val pfd = resolver.openFileDescriptor(uri, "w") ?: return null
                videoPfd = pfd
                Pair(pfd.fileDescriptor, uri)
            } else {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val dir = File(movies, "CamaraMacro").apply { if (!exists()) mkdirs() }
                val file = File(dir, name)
                videoFile = file
                val fos = FileOutputStream(file)
                videoFos = fos
                Pair(fos.fd, Uri.fromFile(file))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun finalizeVideo() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoPfd?.close()
                videoUri?.let {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    activity.contentResolver.update(it, v, null, null)
                }
            } else {
                videoFos?.close()
                videoFile?.let {
                    MediaScannerConnection.scanFile(
                        activity, arrayOf(it.absolutePath), arrayOf("video/mp4"), null
                    )
                }
            }
        } catch (e: Exception) {
        }
        videoPfd = null
        videoFos = null
        videoFile = null
    }

    companion object {
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val OPEN_TIMEOUT_MS = 5000L
        /** Cuánto se mantiene el foco fijado tras tocar, antes de volver a continuo. */
        private const val TAP_FOCUS_HOLD_MS = 5000L
        /**
         * Techo de espera del enfoque antes de disparar. Sube de 400 a 600 ms porque ahora
         * se espera un barrido ACTIVO de verdad (estado _LOCKED) y no el estado pasivo
         * heredado: un barrido completo en este sensor ronda los 300-600 ms, así que con 400
         * el timeout saltaba casi siempre y volvíamos a la foto blanda de antes. Cuando el
         * AF continuo ya está convergido no se espera nada, así que el obturador rápido de
         * la ruta habitual no se toca.
         */
        private const val AF_WAIT_MAX_MS = 600L
        /** Techo de espera de la pre-captura del AE (el flash necesita medir antes de disparar). */
        private const val AE_PRECAPTURE_MAX_MS = 900L
        /** Si el HAL no entrega la foto en este tiempo, se libera el obturador igualmente. */
        private const val CAPTURE_TIMEOUT_MS = 4000L
        /** Umbrales de ISO para elegir la reducción de ruido (ver applyDetailModes). */
        private const val NR_MINIMAL_MAX_ISO = 1600
        private const val NR_FAST_MAX_ISO = 3200
        /** Techo de ISO y obturador de sincronización cuando dispara el flash. */
        private const val FLASH_MAX_ISO = 1600
        private const val FLASH_SYNC_EXP_NS = 16_666_667L // 1/60 s
        /** Vigilante del apilado nocturno: base + margen por fotograma. */
        private const val NIGHT_WATCHDOG_BASE_MS = 4000L
        private const val NIGHT_WATCHDOG_PER_FRAME_MS = 2000L
        /** Reintentos de arranque del visor cuando todavía no hay superficie. */
        private const val PREVIEW_RETRIES = 3
        private const val PREVIEW_RETRY_MS = 150L
        /** Modos que añaden un stream: como mucho uno a la vez (ver keepOnlyExtra). */
        private const val EXTRA_RAW = 1
        private const val EXTRA_HDR = 2
        private const val EXTRA_NIGHT = 3
        private const val EXTRA_QR = 4
        private const val NIGHT_FRAMES = 7

        /** Tope de exposición POR FOTOGRAMA en modo noche: 1/8 s, lo que aguanta el OIS a pulso. */
        private const val NIGHT_MAX_EXP_NS = 125_000_000L
    }
}
