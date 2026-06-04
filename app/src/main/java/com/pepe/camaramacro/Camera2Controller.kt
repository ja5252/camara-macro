package com.pepe.camaramacro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
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
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream

/** Estado del enfoque comunicado a la UI (mapea CONTROL_AF_STATE). */
enum class FocusState { SCANNING, FOCUSED, NOT_FOCUSED, INACTIVE }

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

    private var cameraId: String = "0"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private var previewSize: Size = Size(1920, 1080)
    private var sensorOrientation = 0
    private var facingFront = false
    private var afContinuousSupported = false

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

    // Controles PRO (exposición / WB)
    private var isoMin = 100
    private var isoMax = 100
    private var expMinNs = 0L
    private var expMaxNs = 0L
    private var evMin = 0
    private var evMax = 0
    private var manualExposure = false
    private var manualIso = 100
    private var manualExpNs = 8_000_000L
    private var evSteps = 0
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    private var aeLocked = false
    private var afLocked = false
    private var manualFocus = false
    private var manualDiopters = 0f
    private var lastFocusState: FocusState? = null

    // Video
    private var videoSize = Size(1920, 1080)
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

    fun takePhoto(onResult: (Boolean) -> Unit) {
        val device = cameraDevice
        val session = captureSession
        val reader = imageReader
        if (device == null || session == null || reader == null) {
            onResult(false); return
        }
        try {
            pendingResult = onResult
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(reader.surface)
            applyControls(req)
            req.set(CaptureRequest.JPEG_ORIENTATION, currentJpegOrientation())
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
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

    fun close() {
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
        cancelWatchdog()
        orientationListener.disable()
        stopBackgroundThread()
    }

    // ---------------------------------------------------------------- Zoom

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (zoomRatioSupported) {
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

    /** Construye la cadena de lentes para zoom: traseras que funcionan (excluye la dañada y sus duplicados). */
    private fun buildZoomChain() {
        zoomChain.clear()
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { manager.cameraIdList } catch (e: Exception) { return }
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
                backs.add(Pair(id, focal))
            } catch (e: Exception) {
            }
        }
        backs.sortBy { it.second }
        if (backs.isEmpty()) return
        val minF = backs.first().second
        backs.forEach { zoomChain.add(Pair(it.first, it.second / minF)) }
        Log.i("CamMacro", "buildZoomChain ids=${ids.toList()} mainFocal=$mainFocal chain=$zoomChain")
    }

    private fun switchToLens(targetIndex: Int) {
        if (switching) return
        switching = true
        chainIndex = targetIndex
        cameraId = zoomChain[targetIndex].first
        failed = false
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
        afLocked = afAvailable // tras un toque, mantenemos el enfoque fijado
        lastFocusState = null
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mr)
            if (afAvailable) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mr)
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START
                )
                session.capture(previewRequestBuilder.build(), previewCallback, backgroundHandler)
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
            } else {
                // Sin AF: solo fijamos exposición/medición en el punto.
                activity.runOnUiThread { onFocusState?.invoke(FocusState.FOCUSED) }
            }
            updatePreview()
        } catch (e: Exception) {
        }
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
        applyAndUpdate()
    }

    private fun applyAndUpdate() {
        if (::previewRequestBuilder.isInitialized) {
            applyControls(previewRequestBuilder)
            updatePreview()
        }
    }

    /** Aplica zoom, AE-lock y el modo de enfoque actual al builder. */
    private fun applyControls(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExpNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        when {
            manualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualDiopters)
            }
            afLocked && afAvailable ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
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
        }
    }

    private fun meteringRect(nx: Float, ny: Float): Rect? {
        val arr = activeArray ?: return null
        val cropW = arr.width() / zoomRatio
        val cropH = arr.height() / zoomRatio
        val cropLeft = arr.exactCenterX() - cropW / 2f
        val cropTop = arr.exactCenterY() - cropH / 2f
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
        val cb = pendingResult; pendingResult = null
        activity.runOnUiThread { cb?.invoke(ok) }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            setUpOutputs(manager)
            configureTransform(textureView.width, textureView.height)
            startWatchdog()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
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
        afAvailable = afModes.any {
            it == CaptureRequest.CONTROL_AF_MODE_AUTO || it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val zr = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
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

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        val largest = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

        val recSizes = map.getOutputSizes(MediaRecorder::class.java)
        videoSize = recSizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: recSizes?.filter { it.width <= 1920 }?.maxByOrNull { it.width.toLong() * it.height }
            ?: recSizes?.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
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

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface, reader.surface),
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
                        fail("No se pudo configurar esta lente. Prueba otra.")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            fail("Error al iniciar la vista previa: ${e.message}")
        }
    }

    private fun saveImage(bytes: ByteArray): Boolean {
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

    private fun startWatchdog() {
        cancelWatchdog()
        watchdog = Runnable {
            fail("Esta lente no respondió (puede ser la dañada). Prueba otra.")
        }
        uiHandler.postDelayed(watchdog!!, OPEN_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { uiHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun fail(msg: String) {
        if (failed) return
        failed = true
        switching = false
        cancelWatchdog()
        activity.runOnUiThread { onError?.invoke(msg) }
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

    fun startVideo(withAudio: Boolean): Boolean {
        val device = cameraDevice ?: return false
        if (recording) return false
        try {
            val texture = textureView.surfaceTexture ?: return false
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            val recorder = createRecorder(withAudio) ?: return false
            mediaRecorder = recorder
            val recorderSurface = recorder.surface

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface)
            builder.addTarget(recorderSurface)
            previewRequestBuilder = builder
            applyControls(builder)

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
            recorder.setVideoEncodingBitRate(12_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
    }
}
