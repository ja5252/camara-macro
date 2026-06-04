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
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
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

    private var aeLocked = false
    private var afLocked = false
    private var manualFocus = false
    private var manualDiopters = 0f
    private var lastFocusState: FocusState? = null

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

    /** Rango de zoom (mín, máx). Mín siempre 1.0. */
    val zoomRange: Pair<Float, Float> get() = Pair(1f, maxZoom)

    /** Compatibilidad: máximo de zoom. */
    val maxZoomRatio: Float get() = maxZoom

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

    /** Fija el nivel de zoom y devuelve el valor aplicado (acotado a [1, max]). */
    fun setZoom(ratio: Float): Float {
        zoomRatio = ratio.coerceIn(1f, maxZoom)
        if (::previewRequestBuilder.isInitialized) {
            applyZoom(previewRequestBuilder)
            updatePreview()
        }
        return zoomRatio
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

    /** Aplica zoom, AE-lock y el modo de enfoque actual al builder. */
    private fun applyControls(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
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

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        val largest = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)
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

    companion object {
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val OPEN_TIMEOUT_MS = 5000L
    }
}
