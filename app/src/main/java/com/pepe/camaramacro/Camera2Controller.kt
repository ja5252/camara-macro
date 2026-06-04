package com.pepe.camaramacro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
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

/**
 * Abre una lente concreta por su ID, muestra la vista previa en un AutoFitTextureView
 * y permite tomar fotos guardándolas en la galería.
 *
 * Diseñado para EVITAR la lente principal dañada: nunca dispara un enfoque que pueda
 * colgarse, y tiene un "watchdog" que avisa si una lente no responde.
 */
class Camera2Controller(
    private val activity: Activity,
    private val textureView: AutoFitTextureView
) {

    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var cameraId: String = "0"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private var previewSize: Size = Size(1920, 1080)
    private var sensorOrientation = 0
    private var facingFront = false
    private var afContinuousSupported = false

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

    // ---------------------------------------------------------------- API pública

    fun open(camId: String) {
        failed = false
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
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            req.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (afContinuousSupported) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else CaptureRequest.CONTROL_AF_MODE_OFF
            )
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
        var maxPreviewWidth = (if (swapped) displaySize.y else displaySize.x).coerceAtMost(MAX_PREVIEW_WIDTH)
        var maxPreviewHeight = (if (swapped) displaySize.x else displaySize.y).coerceAtMost(MAX_PREVIEW_HEIGHT)

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
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON
                            )
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                if (afContinuousSupported) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                else CaptureRequest.CONTROL_AF_MODE_OFF
                            )
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
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
