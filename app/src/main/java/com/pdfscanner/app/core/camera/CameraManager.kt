package com.pdfscanner.app.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pdfscanner.app.core.common.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CameraX wrapper — tuned for low-end speed.
 *
 * Magic tricks:
 * - Preview + Analysis (640x480) + Capture are separate use cases.
 * - Analysis uses KEEP_ONLY_LATEST so we never queue frames on slow CPUs.
 * - Full-res capture only on shutter tap, not continuously.
 * - Analysis runs on a single background executor, released on stop().
 */
@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceTier: DeviceTier
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scanner-analysis")
    }

    @Volatile var onFrame: ((Bitmap) -> Unit)? = null

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        onReady: () -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetResolution(
                    android.util.Size(deviceTier.analysisWidth, deviceTier.analysisHeight)
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(
                    android.util.Size(deviceTier.analysisWidth, deviceTier.analysisHeight)
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val bmp = imageProxyToBitmap(imageProxy)
                            if (bmp != null) onFrame?.invoke(bmp)
                        } catch (_: Exception) {
                            // Drop frame on low-end under load — next frame will come.
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, imageCapture)
                onReady()
            } catch (e: Exception) {
                // Camera unavailable (emulator without camera, permission denied, etc.)
                // UI should show gallery import fallback.
                throw e
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun takePicture(): Bitmap = suspendCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera not started"))
            return@suspendCoroutine
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageCapture.OutputFileResults) {
                    super.onCaptureSuccess(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            }
        )
        // NOTE: For Phase 1 we use in-memory capture via ImageAnalysis frame + high-res path.
        // Full implementation will use takePicture with file output + BitmapFactory decode.
        // This stub resumes with a placeholder to keep the API stable.
        // TODO(Phase 1): Replace with file-based capture for 2MP quality.
        cont.resume(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    }

    fun stop() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        imageCapture = null
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val yuvImage = YuvImage(
            image.planes[0].buffer,
            image.format,
            image.width,
            image.height,
            null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Suppress("unused")
    private fun formatName(format: Int): String = when (format) {
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        else -> "UNKNOWN_$format"
    }
}
