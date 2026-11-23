package com.pdfscanner.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pdfscanner.app.R
import com.pdfscanner.app.core.common.DeviceTier
import com.pdfscanner.app.core.common.SettingsPrefs
import com.pdfscanner.app.core.ocr.OcrPackDownloader
import com.pdfscanner.app.core.ocr.TesseractOcrEngine
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var prefs: SettingsPrefs
    @Inject lateinit var deviceTier: DeviceTier
    @Inject lateinit var tesseract: TesseractOcrEngine
    @Inject lateinit var packDownloader: OcrPackDownloader

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textDeviceTier.text = deviceTier.logInfo()
        refreshOcrStatus()

        viewLifecycleOwner.lifecycleScope.launch {
            val filter = prefs.defaultFilter.first()
            val chipId = when (filter) {
                OpenCvHelper.Filter.ORIGINAL -> R.id.chip_set_original
                OpenCvHelper.Filter.MAGIC -> R.id.chip_set_magic
                OpenCvHelper.Filter.LIGHTEN -> R.id.chip_set_lighten
                OpenCvHelper.Filter.BLACK_WHITE -> R.id.chip_set_bw
            }
            binding.chipDefaultFilter.check(chipId)
            binding.switchAutoCapture.isChecked = prefs.autoCapture.first()

            val lang = prefs.appLanguage.first()
            val langChip = when (lang) {
                "en" -> R.id.chip_lang_en
                "am" -> R.id.chip_lang_am
                else -> R.id.chip_lang_system
            }
            binding.chipLanguage.check(langChip)
        }

        binding.chipSetOriginal.setOnClickListener { saveFilter(OpenCvHelper.Filter.ORIGINAL) }
        binding.chipSetMagic.setOnClickListener { saveFilter(OpenCvHelper.Filter.MAGIC) }
        binding.chipSetLighten.setOnClickListener { saveFilter(OpenCvHelper.Filter.LIGHTEN) }
        binding.chipSetBw.setOnClickListener { saveFilter(OpenCvHelper.Filter.BLACK_WHITE) }

        binding.switchAutoCapture.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch { prefs.setAutoCapture(isChecked) }
        }

        binding.chipLangSystem.setOnClickListener { applyLanguage("system") }
        binding.chipLangEn.setOnClickListener { applyLanguage("en") }
        binding.chipLangAm.setOnClickListener { applyLanguage("am") }

        binding.buttonDownloadOcr.setOnClickListener {
            binding.buttonDownloadOcr.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                packDownloader.ensurePacks { msg ->
                    if (_binding != null) binding.textOcrStatus.text = msg
                }
                if (_binding != null) {
                    binding.buttonDownloadOcr.isEnabled = true
                    refreshOcrStatus()
                }
            }
        }
    }

    private fun saveFilter(f: OpenCvHelper.Filter) {
        viewLifecycleOwner.lifecycleScope.launch { prefs.setDefaultFilter(f) }
    }

    private fun applyLanguage(tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            prefs.setAppLanguage(tag)
            val locales = if (tag == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun refreshOcrStatus() {
        val ready = tesseract.arePacksDownloaded()
        binding.textOcrStatus.text = getString(if (ready) R.string.ocr_ready else R.string.ocr_missing)
        binding.buttonDownloadOcr.visibility = if (ready) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
