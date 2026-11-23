package com.pdfscanner.app.ui.editor

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.core.common.SettingsPrefs
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.FragmentCropBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class CropFragment : Fragment() {

    private var _binding: FragmentCropBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var openCv: OpenCvHelper
    @Inject lateinit var deviceTier: DeviceTier
    @Inject lateinit var prefs: SettingsPrefs

    private var pageIndex: Int = 0
    private var rawBitmap: Bitmap? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageIndex = arguments?.getInt("pageIndex", 0) ?: 0
        val entry = ScanSession.pages.getOrNull(pageIndex)
        if (entry == null) {
            findNavController().navigateUp()
            return
        }

        val bmp = ScanSession.loadRawBitmap(pageIndex)
        if (bmp == null) {
            findNavController().navigateUp()
            return
        }
        rawBitmap = bmp
        binding.imageCrop.setImageBitmap(bmp)

        binding.cropOverlay.post {
            binding.cropOverlay.setSourceBitmap(bmp)
            val existing = entry.quad
            if (existing != null && existing.size == 4 && entry.quadWidth > 0 && entry.quadHeight > 0) {
                val norm = existing.map {
                    PointF(it.x / entry.quadWidth.toFloat(), it.y / entry.quadHeight.toFloat())
                }.toTypedArray()
                binding.cropOverlay.setNormalizedCorners(norm)
            } else {
                binding.cropOverlay.resetCorners()
            }
        }

        binding.buttonResetCrop.setOnClickListener {
            binding.cropOverlay.resetCorners()
        }

        binding.buttonApplyCrop.setOnClickListener {
            applyCrop(bmp)
        }
    }

    private fun applyCrop(bmp: Bitmap) {
        binding.buttonApplyCrop.isEnabled = false
        val norm = binding.cropOverlay.getNormalizedCorners()
        val pixelQuad = norm.map {
            PointF(it.x * bmp.width.toFloat(), it.y * bmp.height.toFloat())
        }.toTypedArray()
        val filterName = arguments?.getString("filter")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val filter = filterName?.let { runCatching { OpenCvHelper.Filter.valueOf(it) }.getOrNull() }
                ?: prefs.defaultFilter.first()
            val processed = openCv.processPage(bmp, filter, pixelQuad, bmp.width, bmp.height)
            val dir = File(requireContext().cacheDir, "scans")
            val newPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
            BitmapUtils.safeRecycle(processed)
            withContext(Dispatchers.Main) {
                if (newPath != null) {
                    ScanSession.updateCrop(pageIndex, pixelQuad, bmp.width, bmp.height, newPath)
                    findNavController().navigateUp()
                } else {
                    binding.buttonApplyCrop.isEnabled = true
                    Toast.makeText(requireContext(), "Crop failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        rawBitmap?.let { BitmapUtils.safeRecycle(it) }
        rawBitmap = null
        super.onDestroyView()
        _binding = null
    }
}
