package com.pdfscanner.app.ui.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfscanner.app.core.vision.OpenCvHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val openCv: OpenCvHelper
) : ViewModel() {

    private val _captured = MutableStateFlow<Bitmap?>(null)
    val captured: StateFlow<Bitmap?> = _captured.asStateFlow()

    private val _filter = MutableStateFlow(OpenCvHelper.Filter.MAGIC)
    val filter: StateFlow<OpenCvHelper.Filter> = _filter.asStateFlow()

    fun onFrame(frame: Bitmap) {
        // Throttle: only process every Nth frame (handled by CameraManager KEEP_ONLY_LATEST).
        // Phase 1: detect quad + sharpness, update overlay, auto-capture when stable.
        viewModelScope.launch {
            try {
                openCv.detectDocumentQuad(frame)
            } catch (_: Exception) {
                // Ignore — next frame will retry.
            }
        }
    }

    fun setFilter(f: OpenCvHelper.Filter) {
        _filter.value = f
    }

    fun onCaptured(bitmap: Bitmap) {
        _captured.value = bitmap
    }
}
