package com.pdfscanner.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pdfscanner.app.R
import com.pdfscanner.app.core.camera.CameraManager
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.FragmentScannerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScannerViewModel by viewModels()

    @Inject lateinit var cameraManager: CameraManager
    @Inject lateinit var deviceTier: DeviceTier

    private var isCapturing = false
    private var lastQuadResult: ScannerViewModel.QuadResult? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionFallback()
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) importFromGallery(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        OpenCvHelper.ensureLoaded()

        binding.buttonCapture.setOnClickListener { capture() }
        binding.buttonGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        binding.buttonFlash.setOnClickListener {
            val next = !cameraManager.isTorchOn
            cameraManager.setTorch(next)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.quad.collect { qr ->
                        lastQuadResult = qr
                        updateOverlay(qr)
                    }
                }
                launch { viewModel.isStable.collect { binding.edgeOverlay.isStable = it } }
                launch {
                    viewModel.captureEvent.collect {
                        vibrate()
                        capture()
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateOverlay(qr: ScannerViewModel.QuadResult?) {
        val overlay = binding.edgeOverlay
        if (qr == null || overlay.width == 0 || qr.frameWidth <= 0 || qr.frameHeight <= 0) {
            overlay.quad = null
            return
        }
        val scaleX = overlay.width.toFloat() / qr.frameWidth.toFloat()
        val scaleY = overlay.height.toFloat() / qr.frameHeight.toFloat()
        overlay.quad = qr.points.map { PointF(it.x * scaleX, it.y * scaleY) }.toTypedArray()
    }

    private fun startCamera() {
        try {
            cameraManager.onFrame = { frame -> viewModel.onFrame(frame) }
            cameraManager.start(viewLifecycleOwner, binding.previewView) {
                if (_binding != null) {
                    binding.textHint.text = getString(R.string.align_document)
                }
            }
        } catch (_: Exception) {
            showPermissionFallback()
        }
    }

    private fun showPermissionFallback() {
        binding.textHint.text = getString(R.string.camera_unavailable)
        binding.previewView.visibility = View.GONE
    }

    private fun capture() {
        if (isCapturing) return
        isCapturing = true
        binding.textHint.text = getString(R.string.processing)
        val qr = lastQuadResult
        lifecycleScope.launch(Dispatchers.Default) {
            var raw: Bitmap? = null
            var scaledRaw: Bitmap? = null
            var processed: Bitmap? = null
            try {
                raw = cameraManager.takePicture()
                scaledRaw = BitmapUtils.scaleToMaxEdge(raw, deviceTier.maxProcessingEdge)
                if (scaledRaw !== raw) {
                    BitmapUtils.safeRecycle(raw)
                    raw = null
                }
                processed = viewModel.openCv.processPage(
                    scaledRaw,
                    viewModel.filter.value,
                    qr?.points,
                    qr?.frameWidth ?: 640,
                    qr?.frameHeight ?: 480
                )
                val dir = File(requireContext().cacheDir, "scans")
                val rawPath = ScanSession.saveBitmap(dir, "raw", scaledRaw, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    if (rawPath != null && procPath != null) {
                        ScanSession.addPage(
                            ScanSession.PageEntry(
                                rawPath = rawPath,
                                processedPath = procPath,
                                quad = qr?.points,
                                quadWidth = qr?.frameWidth ?: 0,
                                quadHeight = qr?.frameHeight ?: 0
                            )
                        )
                        binding.textHint.text = getString(R.string.align_document)
                        findNavController().navigate(R.id.action_scanner_to_editor)
                    } else {
                        binding.textHint.text = getString(R.string.align_document)
                        Toast.makeText(requireContext(), getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    binding.textHint.text = getString(R.string.align_document)
                    Toast.makeText(requireContext(), "${getString(R.string.capture_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                BitmapUtils.safeRecycle(raw)
                BitmapUtils.safeRecycle(scaledRaw)
                BitmapUtils.safeRecycle(processed)
            }
        }
    }

    private fun importFromGallery(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Default) {
            var bmp: Bitmap? = null
            var scaled: Bitmap? = null
            var processed: Bitmap? = null
            try {
                bmp = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: return@launch
                scaled = BitmapUtils.scaleToMaxEdge(bmp, deviceTier.maxProcessingEdge)
                if (scaled !== bmp) {
                    BitmapUtils.safeRecycle(bmp)
                    bmp = null
                }
                val detected = viewModel.openCv.detectDocumentQuad(scaled)
                processed = viewModel.openCv.processPage(
                    scaled,
                    viewModel.filter.value,
                    detected,
                    scaled.width,
                    scaled.height
                )
                val dir = File(requireContext().cacheDir, "scans")
                val rawPath = ScanSession.saveBitmap(dir, "raw", scaled, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
                withContext(Dispatchers.Main) {
                    if (rawPath != null && procPath != null) {
                        ScanSession.addPage(
                            ScanSession.PageEntry(
                                rawPath = rawPath,
                                processedPath = procPath,
                                quad = detected,
                                quadWidth = scaled.width,
                                quadHeight = scaled.height
                            )
                        )
                        findNavController().navigate(R.id.action_scanner_to_editor)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                BitmapUtils.safeRecycle(bmp)
                BitmapUtils.safeRecycle(scaled)
                BitmapUtils.safeRecycle(processed)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        cameraManager.stop()
        super.onDestroyView()
        _binding = null
    }
}
