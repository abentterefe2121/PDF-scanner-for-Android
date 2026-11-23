package com.pdfscanner.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
            updateTorchUi(next)
        }
        binding.buttonAutoToggle.setOnClickListener { viewModel.toggleAutoCapture() }
        binding.buttonBatchToggle.setOnClickListener { viewModel.toggleBatchMode() }
        binding.buttonDoneBatch.setOnClickListener {
            if (ScanSession.pages.isNotEmpty()) {
                findNavController().navigate(R.id.action_scanner_to_editor)
            }
        }
        updateBatchBadge()
        updateTorchUi(false)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.quad.collect { qr ->
                        lastQuadResult = qr
                        updateOverlay(qr)
                    }
                }
                launch {
                    viewModel.isStable.collect { stable ->
                        binding.edgeOverlay.isStable = stable
                        if (!isCapturing) {
                            binding.textHint.text = if (stable) {
                                getString(R.string.hold_steady)
                            } else {
                                getString(R.string.align_document)
                            }
                        }
                    }
                }
                launch {
                    viewModel.autoCapture.collect { on ->
                        binding.buttonAutoToggle.text = getString(
                            if (on) R.string.auto_capture_on else R.string.auto_capture_off
                        )
                    }
                }
                launch {
                    viewModel.batchMode.collect { on ->
                        binding.buttonBatchToggle.text = getString(
                            if (on) R.string.batch_on else R.string.batch_off
                        )
                    }
                }
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

    private fun updateTorchUi(on: Boolean) {
        val color = if (on) ContextCompat.getColor(requireContext(), R.color.primary) else Color.WHITE
        binding.buttonFlash.setColorFilter(color)
    }

    private fun updateBatchBadge() {
        val count = ScanSession.pages.size
        if (count > 0) {
            binding.buttonDoneBatch.visibility = View.VISIBLE
            binding.buttonDoneBatch.text = getString(R.string.done_pages, count)
        } else {
            binding.buttonDoneBatch.visibility = View.INVISIBLE
        }
    }

    /**
     * Maps analysis-frame quad coordinates to PreviewView (FILL_CENTER aspect-fill)
     * so the live green quad aligns accurately on 20:9 tall phone displays.
     */
    private fun updateOverlay(qr: ScannerViewModel.QuadResult?) {
        val overlay = binding.edgeOverlay
        if (qr == null || overlay.width == 0 || overlay.height == 0 || qr.frameWidth <= 0 || qr.frameHeight <= 0) {
            overlay.quad = null
            return
        }
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        val fw = qr.frameWidth.toFloat()
        val fh = qr.frameHeight.toFloat()
        val scale = maxOf(vw / fw, vh / fh)
        val dx = (vw - fw * scale) / 2f
        val dy = (vh - fh * scale) / 2f
        overlay.quad = qr.points.map { PointF(it.x * scale + dx, it.y * scale + dy) }.toTypedArray()
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
            var processed: Bitmap? = null
            try {
                raw = cameraManager.takePicture()
                processed = viewModel.openCv.processPage(
                    raw,
                    viewModel.filter.value,
                    qr?.points,
                    qr?.frameWidth ?: 640,
                    qr?.frameHeight ?: 480
                )
                val dir = File(requireContext().cacheDir, "scans")
                val rawPath = ScanSession.saveBitmap(dir, "raw", raw, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    if (_binding == null) return@withContext
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
                        updateBatchBadge()
                        binding.textHint.text = getString(R.string.align_document)
                        if (!viewModel.batchMode.value) {
                            findNavController().navigate(R.id.action_scanner_to_editor)
                        }
                    } else {
                        binding.textHint.text = getString(R.string.align_document)
                        Toast.makeText(requireContext(), getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    if (_binding != null) {
                        binding.textHint.text = getString(R.string.align_document)
                        Toast.makeText(requireContext(), "${getString(R.string.capture_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                BitmapUtils.safeRecycle(raw)
                BitmapUtils.safeRecycle(processed)
            }
        }
    }

    private fun importFromGallery(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Default) {
            var bmp: Bitmap? = null
            var processed: Bitmap? = null
            try {
                bmp = BitmapUtils.decodeSampledUri(
                    requireContext(),
                    uri,
                    deviceTier.maxProcessingEdge
                ) ?: return@launch
                val detected = viewModel.openCv.detectDocumentQuad(bmp)
                processed = viewModel.openCv.processPage(
                    bmp,
                    viewModel.filter.value,
                    detected,
                    bmp.width,
                    bmp.height
                )
                val dir = File(requireContext().cacheDir, "scans")
                val rawPath = ScanSession.saveBitmap(dir, "raw", bmp, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (rawPath != null && procPath != null) {
                        ScanSession.addPage(
                            ScanSession.PageEntry(
                                rawPath = rawPath,
                                processedPath = procPath,
                                quad = detected,
                                quadWidth = bmp.width,
                                quadHeight = bmp.height
                            )
                        )
                        updateBatchBadge()
                        if (!viewModel.batchMode.value) {
                            findNavController().navigate(R.id.action_scanner_to_editor)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                BitmapUtils.safeRecycle(bmp)
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

    override fun onResume() {
        super.onResume()
        updateBatchBadge()
    }

    override fun onDestroyView() {
        cameraManager.stop()
        super.onDestroyView()
        _binding = null
    }
}
