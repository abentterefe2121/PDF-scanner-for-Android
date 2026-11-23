package com.pdfscanner.app.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraX wrapper — tuned for low-end speed (CameraX 1.6.1 ResolutionSelector).
 * - Preview + Analysis (RGBA_8888 640x480) + Capture separate use cases.
 * - Supports tap-to-focus & metering via PreviewView.meteringPointFactory.
 * - Capture writes a temp file then sub-samples + EXIF-rotates directly to maxProcessingEdge (no 48MP OOM).
 */
@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceTier: DeviceTier
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scanner-analysis")
    }
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scanner-capture")
    }

    @Volatile var onFrame: ((Bitmap) -> Unit)? = null
    @Volatile var isTorchOn: Boolean = false

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysisSize = Size(deviceTier.analysisWidth, deviceTier.analysisHeight)
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        analysisSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val bmp = imageProxyToRotatedBitmap(imageProxy)
                            if (bmp != null) onFrame?.invoke(bmp)
                        } catch (_: Exception) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, imageCapture)
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            camera?.cameraControl?.startFocusAndMetering(action)
        } catch (_: Exception) {}
    }

    fun setTorch(on: Boolean) {
        isTorchOn = on
        try {
            camera?.cameraControl?.enableTorch(on)
        } catch (_: Exception) {}
    }

    /** Capture a photo, downsample + apply EXIF orientation, return Bitmap. Suspends until done. */
    suspend fun takePicture(): Bitmap = suspendCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera not started"))
            return@suspendCoroutine
        }
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bmp = BitmapUtils.decodeSampledFile(
                            file.absolutePath,
                            deviceTier.maxProcessingEdge
                        )
                        file.delete()
                        if (bmp != null) cont.resume(bmp)
                        else cont.resumeWithException(IllegalStateException("Decode failed"))
                    } catch (e: Exception) {
                        file.delete()
                        cont.resumeWithException(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    fun stop() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageCapture = null
        camera = null
        isTorchOn = false
    }

    private fun imageProxyToRotatedBitmap(imageProxy: ImageProxy): Bitmap? {
        val raw = imageProxy.toBitmap()
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 0) return raw
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}
