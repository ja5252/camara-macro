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

    private var cameraId: String = "0"
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    // Token de generación de apertura: descarta callbacks de una lente anterior
    // (p.ej. tras flip/switch) para que no muestren error en la lente nueva.
    private var cameraGen = 0

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
    private var nrAvailable: IntArray = IntArray(0)
    private var edgeAvailable: IntArray = IntArray(0)
    /** Acción pendiente a ejecutar cuando el AF converja antes de disparar. */
    private var afWaitAction: (() -> Unit)? = null
    private var afWaitTimeout: Runnable? = null
    private var lastAeState = -1
    private var lastSavedUri: Uri? = null
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
        val maxDisp = zoomChain.last().second * 4f * f
        listOf(1f, 2f, 5f).forEach { d ->
            val yaHay = byDisplay.keys.any { kotlin.math.abs(it - d) < 0.18f }
            if (!yaHay && d > minDisp + 0.05f && d < maxDisp) {
                byDisplay[d] = Triple(d / f, fmtZoom(d), false) // zoom digital
            }
        }
        return byDisplay.values.toList()
    }
    private var aeWaitAction: (() -> Unit)? = null
    private var aeWaitTimeout: Runnable? = null
    // Nº de fotograma en el que se envió el disparador. Sin esto, el visor (que sigue
    // entregando resultados a 30 fps) colaba un resultado ANTERIOR al disparo y la espera
    // de enfoque se resolvía al instante: el arreglo del enfoque no llegaba a ejecutarse.
    private var captureWatchdog: Runnable? = null
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
    private var nightStacker: NightStacker? = null
    private var nightCapturing = false
    private var nightCount = 0
    private var nightTarget = 0
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
    var onQrDetected: ((String) -> Unit)? = null

    private var aeLocked = false
    private var afLocked = false
    private var manualFocus = false
    private var manualDiopters = 0f
    private var lastFocusState: FocusState? = null

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

    private var pendingResult: ((Boolean) -> Unit)? = null
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

    /** Rango de zoom (mín, máx) considerando toda la cadena de lentes. */
    val zoomRange: Pair<Float, Float>
        get() = Pair(1f, if (autoLens && zoomChain.isNotEmpty()) zoomChain.last().second * 4f else maxZoom)

    /** Máximo de zoom global. */
    val maxZoomRatio: Float
        get() = if (autoLens && zoomChain.isNotEmpty()) zoomChain.last().second * 4f else maxZoom

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
     */
    fun takePhoto(onResult: (Boolean) -> Unit) {
        val needsAf = afAvailable && !manualFocus && !afLocked &&
            lastFocusState != FocusState.FOCUSED &&
            captureSession != null && ::previewRequestBuilder.isInitialized
        if (needsAf) triggerAfThenCapture(onResult) else proceedAfterAf(onResult)
    }

    /** Tras el enfoque: si hay flash auto/on hace falta la pre-captura para que encienda. */
    private fun proceedAfterAf(onResult: (Boolean) -> Unit) {
        val wantFlash = flashAvailable && (flashMode == 1 || flashMode == 2) && !manualExposure
        if (wantFlash && captureSession != null && ::previewRequestBuilder.isInitialized) {
            triggerPrecaptureThenCapture(onResult)
        } else {
            captureStillNow(onResult)
        }
    }

    /**
     * Secuencia de pre-captura del AE: sin ella el HAL no mide ni carga el flash y la foto
     * sale a oscuras aunque se pida flash obligatorio (comprobado: ISO 14681 y sin destello).
     */
    private fun triggerPrecaptureThenCapture(onResult: (Boolean) -> Unit) {
        val session = captureSession
        if (session == null) { captureStillNow(onResult); return }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = {
            if (fired.compareAndSet(false, true)) {
                aeWaitAction = null
                aeWaitTimeout?.let { uiHandler.removeCallbacks(it) }
                aeWaitTimeout = null
                captureStillNow(onResult)
            }
        }
        aeWaitAction = go
        aeTriggerFrame = Long.MAX_VALUE
        val timeout = Runnable { go() }
        aeWaitTimeout = timeout
        uiHandler.postDelayed(timeout, AE_PRECAPTURE_MAX_MS)
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            session.capture(
                previewRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        s: CameraCaptureSession, r: CaptureRequest, ts: Long, frame: Long
                    ) {
                        aeTriggerFrame = frame
                    }
                },
                backgroundHandler
            )
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
            )
        } catch (e: Exception) {
            go() // pase lo que pase, la foto se toma
        }
    }

    private fun triggerAfThenCapture(onResult: (Boolean) -> Unit) {
        val session = captureSession
        if (session == null) { captureStillNow(onResult); return }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = {
            if (fired.compareAndSet(false, true)) {
                afWaitAction = null
                afWaitTimeout?.let { uiHandler.removeCallbacks(it) }
                afWaitTimeout = null
                proceedAfterAf(onResult) // el flash necesita su propia pre-captura
            }
        }
        afWaitAction = go
        afTriggerFrame = Long.MAX_VALUE // hasta saber el fotograma, no aceptamos nada
        val timeout = Runnable { go() }
        afWaitTimeout = timeout
        uiHandler.postDelayed(timeout, AF_WAIT_MAX_MS)
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            session.capture(
                previewRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        s: CameraCaptureSession, r: CaptureRequest, ts: Long, frame: Long
                    ) {
                        afTriggerFrame = frame // a partir de aquí sí valen los resultados
                    }
                },
                backgroundHandler
            )
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE
            )
        } catch (e: Exception) {
            go() // ante cualquier fallo, dispara igual: nunca dejar el obturador muerto
        }
    }

    private fun captureStillNow(onResult: (Boolean) -> Unit) {
        val device = cameraDevice
        val session = captureSession
        val reader = imageReader
        if (device == null || session == null || reader == null) {
            onResult(false); return
        }
        try {
            pendingResult = onResult
            // Vigilante: si el HAL no entrega la imagen, el obturador se liberaría igual.
            captureWatchdog?.let { uiHandler.removeCallbacks(it) }
            val cw = Runnable {
                val cb = pendingResult
                if (cb != null) {
                    pendingResult = null
                    Log.e("CamMacro", "captura sin respuesta: liberando el obturador")
                    activity.runOnUiThread { cb.invoke(false) }
                }
            }
            captureWatchdog = cw
            uiHandler.postDelayed(cw, CAPTURE_TIMEOUT_MS)
            val wantRaw = rawEnabled && rawSupported && rawReader != null
            if (wantRaw) {
                // Descarta cualquier mitad colgante de un disparo previo.
                try { pendingRawImage?.close() } catch (e: Exception) {}
                pendingRawImage = null
                pendingRawResult = null
            }
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(reader.surface)
            if (wantRaw) rawReader?.let { req.addTarget(it.surface) }
            applyControls(req, still = true)
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
                    }
                }
            }
            // DESPUÉS del flash (si no, el bloque de flash pisaría el AE_MODE_OFF).
            applyShutterFloor(req)
            applyDetailModes(req)
            req.set(CaptureRequest.JPEG_ORIENTATION, currentJpegOrientation())
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Solo emparejar metadata si ESTE disparo pidió RAW.
                    if (wantRaw) {
                        pendingRawResult = result
                        tryFlushDng()
                    }
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    val cb = pendingResult; pendingResult = null
                    activity.runOnUiThread { cb?.invoke(false) }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            pendingResult = null
            onResult(false)
        }
    }

    /**
     * Cancela una captura pendiente liberando su callback (evita que el obturador se quede
     * "pegado" si la sesión se cierra/reconstruye con una foto en vuelo).
     */
    private fun abortPendingCapture() {
        val cb = pendingResult
        pendingResult = null
        captureWatchdog?.let { uiHandler.removeCallbacks(it) }
        captureWatchdog = null
        clearAfAeWaits() // si no, un temporizador huérfano dispara una foto fantasma
        try { pendingRawImage?.close() } catch (e: Exception) {}
        pendingRawImage = null
        pendingRawResult = null
        if (cb != null) activity.runOnUiThread { cb.invoke(false) }
    }

    /** Cancela cualquier espera de enfoque/exposición pendiente. */
    private fun clearAfAeWaits() {
        afWaitAction = null
        aeWaitAction = null
        afTriggerFrame = Long.MAX_VALUE
        aeTriggerFrame = Long.MAX_VALUE
        afWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        afWaitTimeout = null
        aeWaitTimeout?.let { uiHandler.removeCallbacks(it) }
        aeWaitTimeout = null
    }

    fun close() {
        abortPendingCapture()
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
        try { pendingRawImage?.close() } catch (e: Exception) {}
        pendingRawImage = null
        pendingRawResult = null
        nightCapturing = false
        nightWatchdog?.let { uiHandler.removeCallbacks(it) }
        nightWatchdog = null
        nightStacker?.release()
        nightStacker = null
        try { nightReader?.close() } catch (e: Exception) {}
        nightReader = null
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
            if (::previewRequestBuilder.isInitialized) {
                applyZoom(previewRequestBuilder)
                updatePreview()
            }
            return globalZoom
        }
        val gmax = zoomChain.last().second * 4f
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
            if (::previewRequestBuilder.isInitialized) {
                applyZoom(previewRequestBuilder)
                updatePreview()
            }
        }
        return globalZoom
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
        if (::previewRequestBuilder.isInitialized && captureSession != null) refreshZoomChain()
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
        // Congela el último fotograma para tapar el negro del cambio de lente.
        activity.runOnUiThread { onLensSwitching?.invoke() }
        chainIndex = targetIndex
        cameraId = zoomChain[targetIndex].first
        failed = false
        abortPendingCapture()
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
        if (!::previewRequestBuilder.isInitialized || viewW == 0 || viewH == 0) return
        val rect = meteringRect(x / viewW, y / viewH) ?: return
        val mr = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
        manualFocus = false
        lastFocusState = null
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
                if (::previewRequestBuilder.isInitialized) {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                    applyControls(previewRequestBuilder)
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
        if (::previewRequestBuilder.isInitialized) {
            applyControls(previewRequestBuilder)
            updatePreview()
        }
    }

    /** Enfoque manual por distancia (dioptrías). Ignora si la lente no lo soporta. */
    fun setManualFocusDistance(diopters: Float) {
        if (minFocusDistance <= 0f) return
        manualFocus = true
        afLocked = false
        manualDiopters = diopters.coerceIn(0f, minFocusDistance)
        if (::previewRequestBuilder.isInitialized) {
            applyControls(previewRequestBuilder)
            updatePreview()
        }
    }

    /** Vuelve al enfoque automático continuo y quita los bloqueos. */
    fun setAutoFocus() {
        manualFocus = false
        afLocked = false
        aeLocked = false
        lastFocusState = null
        if (::previewRequestBuilder.isInitialized) {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            applyControls(previewRequestBuilder)
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
    fun setRawEnabled(enabled: Boolean): Boolean {
        val target = enabled && rawSupported
        if (target == rawEnabled) return rawEnabled
        rawEnabled = target
        if (target) {
            rawFallbackTried = false // permite el fallback seguro otra vez
            nightEnabled = false     // RAW, noche y QR son excluyentes (máx 3 streams)
            if (qrEnabled) setQrEnabledInternal(false)
        }
        postRebuildSession()
        return rawEnabled
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
            h.post {
                abortPendingCapture()
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
        if (enabled) { rawEnabled = false; if (qrEnabled) setQrEnabledInternal(false) }
        nightEnabled = enabled
        postRebuildSession()
        return nightEnabled
    }

    // ---------------------------------------------------------------- QR / códigos

    val hasQr: Boolean get() = true

    fun setQrEnabled(enabled: Boolean): Boolean {
        if (enabled == qrEnabled) return qrEnabled
        if (enabled) { rawEnabled = false; nightEnabled = false }
        setQrEnabledInternal(enabled)
        postRebuildSession()
        return qrEnabled
    }

    private fun setQrEnabledInternal(enabled: Boolean) {
        qrEnabled = enabled
        if (enabled) {
            if (qrScanner == null) qrScanner = BarcodeScanning.getClient()
        } else {
            try { qrScanner?.close() } catch (e: Exception) {}
            qrScanner = null
            qrBusy = false
        }
    }

    private val onQrImage = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return@OnImageAvailableListener
        val scanner = qrScanner
        if (qrBusy || scanner == null) { image.close(); return@OnImageAvailableListener }
        qrBusy = true
        try {
            val input = InputImage.fromMediaImage(image, sensorOrientation)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val v = barcodes.firstOrNull()?.rawValue
                    if (!v.isNullOrEmpty()) activity.runOnUiThread { onQrDetected?.invoke(v) }
                }
                .addOnCompleteListener {
                    image.close()
                    qrBusy = false
                }
        } catch (e: Exception) {
            image.close()
            qrBusy = false
        }
    }

    /**
     * Captura una ráfaga de N frames con exposición bloqueada y los apila (denoise nocturno).
     * Si no es posible, cae a una foto JPEG normal.
     */
    fun takeNightPhoto(onResult: (Boolean) -> Unit) {
        val device = cameraDevice
        val session = captureSession
        val reader = nightReader
        if (device == null || session == null || reader == null || nightCapturing) {
            takePhoto(onResult); return
        }
        try {
            pendingResult = onResult
            nightCapturing = true
            nightCount = 0
            nightTarget = NIGHT_FRAMES
            nightStacker = NightStacker(nightSize.width, nightSize.height)

            // Exposición a bloquear: manual si está activa, si no la última auto medida.
            val iso = if (manualExposure) manualIso else lastAeIso
            val expNs = if (manualExposure) manualExpNs
                else lastAeExpNs.coerceAtMost(125_000_000L) // tope 1/8s por frame contra movimiento

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
            nightWatchdog = Runnable { abortNight() }
            uiHandler.postDelayed(nightWatchdog!!, 8000)
        } catch (e: Exception) {
            nightCapturing = false
            nightStacker = null
            val cb = pendingResult; pendingResult = null
            activity.runOnUiThread { cb?.invoke(false) }
        }
    }

    private val onNightImage = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        try {
            image = reader.acquireNextImage()
            if (nightCapturing && image != null) nightStacker?.addFrame(image)
        } catch (e: Exception) {
            Log.e("CamMacro", "onNightImage: ${e.message}")
        } finally {
            image?.close()
        }
        if (nightCapturing) {
            nightCount++
            if (nightCount >= nightTarget) finishNightStack()
        }
    }

    private fun finishNightStack() {
        if (!nightCapturing) return
        nightCapturing = false
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
        val cb = pendingResult; pendingResult = null
        activity.runOnUiThread { cb?.invoke(ok) }
    }

    private fun abortNight() {
        if (!nightCapturing) return
        nightCapturing = false
        nightWatchdog?.let { uiHandler.removeCallbacks(it) }
        nightWatchdog = null
        try { captureSession?.let { /* no abortCaptures: deja terminar */ } } catch (e: Exception) {}
        nightStacker?.release()
        nightStacker = null
        val cb = pendingResult; pendingResult = null
        activity.runOnUiThread { cb?.invoke(false) }
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
        if (::previewRequestBuilder.isInitialized) {
            applyControls(previewRequestBuilder)
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
    private fun applyDetailModes(b: CaptureRequest.Builder) {
        // Escalera según el ISO real: con luz buena priorizamos detalle; con ISO alto hace
        // falta denoise de verdad o la foto se ve llena de grano al ampliarla.
        val nr = when {
            lastAeIso < 800 && nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ->
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            lastAeIso < 2000 && nrAvailable.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) ->
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
    }

    /** Velocidad mínima de obturación en la FOTO: 0 = automático. */
    fun setShutterFloorNs(ns: Long) { shutterFloorNs = ns }
    val shutterFloor: Long get() = shutterFloorNs

    /**
     * Congela el movimiento: si el AE quiere una exposición más lenta que el piso,
     * fija manualmente una exposición corta y sube el ISO para compensar. Sin esto,
     * en interiores la foto sale a 1/15-1/30 s y cualquier movimiento la emborrona.
     * Devuelve true si tomó el control de la exposición.
     */
    private fun applyShutterFloor(b: CaptureRequest.Builder): Boolean {
        if (shutterFloorNs <= 0L || manualExposure || !manualSensorSupported) return false
        if (flashMode == 1 || flashMode == 2) return false // con flash manda el AE del HAL
        if (lastAeExpNs <= 0L || lastAeIso <= 0) return false
        var targetExp = shutterFloorNs.coerceAtLeast(if (expMinNs > 0) expMinNs else 1L)
        if (lastAeExpNs <= targetExp) return false // el AE ya es suficientemente rápido
        // Conserva la exposición total: al acortar el tiempo, sube el ISO en la misma proporción.
        var iso = Math.round(lastAeIso * (lastAeExpNs.toDouble() / targetExp)).toInt()
        // Preferimos algo de trepidación antes que una foto llena de grano: si congelar
        // el movimiento exige pasar del techo de ISO, se cede exposición en vez de ruido.
        val ceiling = minOf(isoMax, isoCeilingForFloor)
        if (iso > ceiling) {
            iso = ceiling
            targetExp = (lastAeExpNs.toDouble() * lastAeIso / ceiling)
                .toLong().coerceIn(targetExp, lastAeExpNs)
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoMin, isoMax))
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExp)
        return true
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
        if (!::previewRequestBuilder.isInitialized) return
        try {
            session.setRepeatingRequest(previewRequestBuilder.build(), previewCallback, backgroundHandler)
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
            // ¿Hay una foto esperando a que el AE (y el flash) terminen la pre-captura?
            result.get(CaptureResult.CONTROL_AE_STATE)?.let { lastAeState = it }
            // Solo valen los resultados POSTERIORES al disparador (ver afTriggerFrame).
            if (aeWaitAction != null && result.frameNumber >= aeTriggerFrame) {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null ||
                    ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                    ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                ) {
                    val action = aeWaitAction
                    aeWaitAction = null
                    activity.runOnUiThread { action?.invoke() }
                }
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
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
            if (afWaitAction != null && result.frameNumber >= afTriggerFrame &&
                (mapped == FocusState.FOCUSED || mapped == FocusState.NOT_FOCUSED)
            ) {
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
            image?.close()
            val cb = pendingResult; pendingResult = null
            activity.runOnUiThread { cb?.invoke(false) }
            return@OnImageAvailableListener
        }
        image?.close()
        captureWatchdog?.let { uiHandler.removeCallbacks(it) }
        captureWatchdog = null
        val cb = pendingResult; pendingResult = null
        activity.runOnUiThread { cb?.invoke(ok) }
    }

    val hasRaw: Boolean get() = rawSupported

    private val onRawAvailable = ImageReader.OnImageAvailableListener { reader ->
        try {
            // No depender del orden: guardamos la imagen y emparejamos con la metadata.
            pendingRawImage = reader.acquireNextImage()
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
        val image = pendingRawImage
        val result = pendingRawResult
        val chars = camChars
        if (image == null || result == null || chars == null) return
        pendingRawImage = null
        pendingRawResult = null
        val ok = saveDng(image, result, chars)
        try { image.close() } catch (e: Exception) {}
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamaraMacro")
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

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val gen = ++cameraGen
        try {
            setUpOutputs(manager)
            configureTransform(textureView.width, textureView.height)
            startWatchdog(gen)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != cameraGen) { try { camera.close() } catch (e: Exception) {}; return }
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen != cameraGen) return
                    // En ColorOS esta es LA ruta cuando otra app se lleva la cámara. Antes
                    // se dejaba la sesión muerta y la captura colgada: el obturador quedaba
                    // inservible para siempre y sin avisar.
                    cameraDevice = null
                    try { captureSession?.close() } catch (e: Exception) {}
                    captureSession = null
                    abortPendingCapture()
                    activity.runOnUiThread { onError?.invoke("Otra app tomó la cámara") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen != cameraGen) return
                    cameraDevice = null
                    try { captureSession?.close() } catch (e: Exception) {}
                    captureSession = null
                    abortPendingCapture() // no dejar el obturador colgado
                    fail("Esta lente no se pudo abrir (error $error). Prueba otra.")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            fail("No se pudo abrir esta lente: ${e.message}")
        }
    }

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

        // FPS del PREVIEW. Antes se elegía el de cota inferior más alta (típicamente 60,60),
        // que oscurece el visor en interiores y no garantiza nada de la foto. Preferimos
        // 30 fijos: visor estable y un lastAeExpNs bien definido para el piso de obturación.
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        aeFpsRange = fpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: fpsRanges?.filter { it.upper <= 30 }?.maxByOrNull { it.lower }
            ?: fpsRanges?.maxByOrNull { it.lower }
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
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        }

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
            nightReader = ImageReader.newInstance(
                nightSize.width, nightSize.height, ImageFormat.YUV_420_888, NIGHT_FRAMES + 1
            ).apply { setOnImageAvailableListener(onNightImage, backgroundHandler) }
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
            textureView.coverMode = (aspect == AspectRatio.FULL)
            val orientation = activity.resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
        }
    }

    private fun startPreview() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)
            val device = cameraDevice ?: return
            val reader = imageReader ?: return

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)
            if (qrEnabled) qrReader?.let { previewRequestBuilder.addTarget(it.surface) }

            val outputs = mutableListOf(surface, reader.surface)
            if (rawEnabled) rawReader?.let { outputs.add(it.surface) }
            if (nightEnabled) nightReader?.let { outputs.add(it.surface) }
            if (qrEnabled) qrReader?.let { outputs.add(it.surface) }
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
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
        var bytes = if (aspect == AspectRatio.FULL) cropFullJpeg(rawBytes) ?: rawBytes else rawBytes
        captureMatrix?.let { bytes = applyColorFilter(bytes, it) ?: bytes } // filtro de color
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
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamaraMacro")
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
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight &&
                option.height == option.width * h / w
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
            else -> choices.maxByOrNull { it.width.toLong() * it.height } ?: choices[0]
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
            val recorder = createRecorder(withAudio && !timeLapse) ?: return false
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
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
                            recorder.start()
                            recording = true
                            activity.runOnUiThread { onRecordingChanged?.invoke(true) }
                        } catch (e: Exception) {
                            fail("No se pudo iniciar el video: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail("No se pudo configurar el video.")
                    }
                },
                backgroundHandler
            )
            return true
        } catch (e: Exception) {
            videoSessionActive = false
            fail("Error al iniciar video: ${e.message}")
            return false
        }
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
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CamaraMacro")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
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
        /** Techo de espera del enfoque antes de disparar (obturador nunca más lento que esto). */
        private const val AF_WAIT_MAX_MS = 400L
        /** Techo de espera de la pre-captura del AE (el flash necesita medir antes de disparar). */
        private const val AE_PRECAPTURE_MAX_MS = 900L
        /** Si el HAL no entrega la foto en este tiempo, se libera el obturador igualmente. */
        private const val CAPTURE_TIMEOUT_MS = 4000L
        private const val NIGHT_FRAMES = 7
    }
}
