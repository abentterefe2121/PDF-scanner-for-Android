package com.pdfscanner.app.ui.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private enum class ScanMode { DOC, ID_CARD, QR }

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScannerViewModel by viewModels()

    @Inject lateinit var cameraManager: CameraManager
    @Inject lateinit var deviceTier: DeviceTier

    private var isCapturing = false
    private var lastQuadResult: ScannerViewModel.QuadResult? = null
    private var scanMode = ScanMode.DOC
    private var pendingIdFront: Bitmap? = null

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

    @SuppressLint("ClickableViewAccessibility")
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
        binding.buttonGridToggle.setOnClickListener {
            val next = !binding.edgeOverlay.showGrid
            binding.edgeOverlay.showGrid = next
            binding.buttonGridToggle.text = getString(if (next) R.string.grid_on else R.string.grid_off)
        }
        binding.buttonAutoToggle.setOnClickListener { viewModel.toggleAutoCapture() }
        binding.buttonBatchToggle.setOnClickListener { viewModel.toggleBatchMode() }
        binding.buttonDoneBatch.setOnClickListener {
            if (ScanSession.pages.isNotEmpty()) {
                findNavController().navigate(R.id.action_scanner_to_editor)
            }
        }

        binding.modeDoc.setOnClickListener { setScanMode(ScanMode.DOC) }
        binding.modeIdCard.setOnClickListener { setScanMode(ScanMode.ID_CARD) }
        binding.modeQr.setOnClickListener { setScanMode(ScanMode.QR) }

        binding.edgeOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                cameraManager.focusAt(binding.previewView, event.x, event.y)
                binding.edgeOverlay.showFocusRing(event.x, event.y)
            }
            true
        }

        updateBatchBadge()
        updateTorchUi(false)
        setScanMode(ScanMode.DOC)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.quad.collect { qr ->
                        lastQuadResult = qr
                        updateOverlay(if (scanMode == ScanMode.QR) null else qr)
                    }
                }
                launch {
                    viewModel.isStable.collect { stable ->
                        binding.edgeOverlay.isStable = stable
                        if (!isCapturing && scanMode == ScanMode.DOC) {
                            binding.textHint.text = getString(
                                if (stable) R.string.hold_steady else R.string.align_document
                            )
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
                        if (scanMode == ScanMode.DOC) {
                            vibrate()
                            capture()
                        }
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

    private fun setScanMode(mode: ScanMode) {
        scanMode = mode
        BitmapUtils.safeRecycle(pendingIdFront)
        pendingIdFront = null
        val activeColor = ContextCompat.getColor(requireContext(), R.color.edge_ready)
        binding.modeDoc.setTextColor(if (mode == ScanMode.DOC) activeColor else Color.WHITE)
        binding.modeIdCard.setTextColor(if (mode == ScanMode.ID_CARD) activeColor else Color.WHITE)
        binding.modeQr.setTextColor(if (mode == ScanMode.QR) activeColor else Color.WHITE)
        binding.textHint.text = getString(
            when (mode) {
                ScanMode.DOC -> R.string.align_document
                ScanMode.ID_CARD -> R.string.scan_id_front
                ScanMode.QR -> R.string.qr_hint
            }
        )
    }

    private fun animateShutterFlash() {
        binding.viewShutterFlash.alpha = 0.85f
        binding.viewShutterFlash.animate().alpha(0f).setDuration(140).start()
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
                if (_binding != null && scanMode == ScanMode.DOC) {
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
        animateShutterFlash()
        binding.textHint.text = getString(R.string.processing)
        val qr = lastQuadResult
        lifecycleScope.launch(Dispatchers.Default) {
            var raw: Bitmap? = null
            var processed: Bitmap? = null
            try {
                raw = cameraManager.takePicture()
                if (scanMode == ScanMode.QR) {
                    scanQrFromBitmap(raw)
                    return@launch
                }
                processed = viewModel.openCv.processPage(
                    raw,
                    viewModel.filter.value,
                    qr?.points,
                    qr?.frameWidth ?: 640,
                    qr?.frameHeight ?: 480
                )
                handleProcessedCapture(raw, processed, qr?.points, qr?.frameWidth ?: 0, qr?.frameHeight ?: 0)
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
                if (processed !== pendingIdFront) {
                    BitmapUtils.safeRecycle(processed)
                }
            }
        }
    }

    private suspend fun scanQrFromBitmap(bmp: Bitmap) {
        val scanner = BarcodeScanning.getClient()
        val value = try {
            val barcodes = scanner.process(InputImage.fromBitmap(bmp, 0)).await()
            barcodes.firstOrNull()?.rawValue
        } catch (_: Exception) {
            null
        } finally {
            scanner.close()
        }
        withContext(Dispatchers.Main) {
            isCapturing = false
            if (_binding == null) return@withContext
            binding.textHint.text = getString(R.string.qr_hint)
            if (value.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
            } else {
                vibrate()
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.qr_found)
                    .setMessage(value)
                    .setPositiveButton(R.string.copy_text) { _, _ ->
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("QR", value))
                        Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    dialog.setNeutralButton("Open") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    }
                }
                dialog.show()
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
                if (scanMode == ScanMode.QR) {
                    scanQrFromBitmap(bmp)
                    return@launch
                }
                val detected = viewModel.openCv.detectDocumentQuad(bmp)
                processed = viewModel.openCv.processPage(
                    bmp,
                    viewModel.filter.value,
                    detected,
                    bmp.width,
                    bmp.height
                )
                handleProcessedCapture(bmp, processed, detected, bmp.width, bmp.height)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                BitmapUtils.safeRecycle(bmp)
                if (processed !== pendingIdFront) {
                    BitmapUtils.safeRecycle(processed)
                }
            }
        }
    }

    private suspend fun handleProcessedCapture(
        raw: Bitmap,
        processed: Bitmap,
        quad: Array<PointF>?,
        quadW: Int,
        quadH: Int
    ) {
        val dir = File(requireContext().cacheDir, "scans")
        if (scanMode == ScanMode.ID_CARD) {
            val front = pendingIdFront
            if (front == null) {
                pendingIdFront = processed
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    if (_binding != null) {
                        binding.textHint.text = getString(R.string.scan_id_back)
                    }
                }
                return
            } else {
                val combined = viewModel.openCv.compositeIdCardA4(front, processed)
                BitmapUtils.safeRecycle(front)
                pendingIdFront = null
                val rawPath = ScanSession.saveBitmap(dir, "raw", combined, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", combined, deviceTier.jpegQuality)
                BitmapUtils.safeRecycle(combined)
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    if (_binding == null) return@withContext
                    if (rawPath != null && procPath != null) {
                        ScanSession.addPage(ScanSession.PageEntry(rawPath = rawPath, processedPath = procPath))
                        updateBatchBadge()
                        binding.textHint.text = getString(R.string.scan_id_front)
                        if (!viewModel.batchMode.value) {
                            findNavController().navigate(R.id.action_scanner_to_editor)
                        }
                    }
                }
                return
            }
        }

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
                        quad = quad,
                        quadWidth = quadW,
                        quadHeight = quadH
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
        BitmapUtils.safeRecycle(pendingIdFront)
        pendingIdFront = null
        super.onDestroyView()
        _binding = null
    }
}
