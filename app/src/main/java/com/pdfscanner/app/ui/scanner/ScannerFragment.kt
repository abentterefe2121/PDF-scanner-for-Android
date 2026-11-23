package com.pdfscanner.app.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.pdfscanner.app.core.camera.CameraManager
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.FragmentScannerBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScannerViewModel by viewModels()

    @Inject lateinit var cameraManager: CameraManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionFallback()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Lazy-load OpenCV only now (not at app start) — fast cold start.
        OpenCvHelper.ensureLoaded()

        binding.buttonCapture.setOnClickListener {
            // Phase 1: cameraManager.takePicture() -> processPage -> Editor
            binding.textHint.text = "Captured (Phase 1 wiring pending)"
        }
        binding.buttonGallery.setOnClickListener {
            // Phase 1: ActivityResultContracts.GetContent() for image import
        }
        binding.buttonFlash.setOnClickListener {
            // Phase 1: toggle torch
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

    private fun startCamera() {
        try {
            cameraManager.onFrame = { frame ->
                viewModel.onFrame(frame)
            }
            cameraManager.start(viewLifecycleOwner, binding.previewView) {
                binding.textHint.text = "Align document"
            }
        } catch (e: Exception) {
            showPermissionFallback()
        }
    }

    private fun showPermissionFallback() {
        binding.textHint.text = "Camera unavailable — use Gallery import"
        binding.previewView.visibility = View.GONE
    }

    override fun onDestroyView() {
        cameraManager.stop()
        super.onDestroyView()
        _binding = null
    }
}
