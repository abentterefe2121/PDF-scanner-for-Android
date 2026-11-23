package com.pdfscanner.app.ui.scanner

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfscanner.app.core.common.SettingsPrefs
import com.pdfscanner.app.core.vision.OpenCvHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    val openCv: OpenCvHelper,
    private val prefs: SettingsPrefs
) : ViewModel() {

    data class QuadResult(
        val points: Array<PointF>,
        val frameWidth: Int,
        val frameHeight: Int
    )

    private val _quad = MutableStateFlow<QuadResult?>(null)
    val quad: StateFlow<QuadResult?> = _quad.asStateFlow()

    private val _isStable = MutableStateFlow(false)
    val isStable: StateFlow<Boolean> = _isStable.asStateFlow()

    private val _filter = MutableStateFlow(OpenCvHelper.Filter.MAGIC)
    val filter: StateFlow<OpenCvHelper.Filter> = _filter.asStateFlow()

    private val _autoCapture = MutableStateFlow(true)
    val autoCapture: StateFlow<Boolean> = _autoCapture.asStateFlow()

    private val _batchMode = MutableStateFlow(false)
    val batchMode: StateFlow<Boolean> = _batchMode.asStateFlow()

    private val _captureEvent = Channel<Unit>(Channel.BUFFERED)
    val captureEvent = _captureEvent.receiveAsFlow()

    private var stableFrames = 0
    @Volatile private var lastProcessMs = 0L
    private var lastQuad: Array<PointF>? = null

    init {
        viewModelScope.launch {
            _filter.value = prefs.defaultFilter.first()
            _autoCapture.value = prefs.autoCapture.first()
            _batchMode.value = prefs.batchMode.first()
        }
    }

    fun toggleAutoCapture() {
        val next = !_autoCapture.value
        _autoCapture.value = next
        viewModelScope.launch { prefs.setAutoCapture(next) }
    }

    fun toggleBatchMode() {
        val next = !_batchMode.value
        _batchMode.value = next
        viewModelScope.launch { prefs.setBatchMode(next) }
    }

    fun onFrame(frame: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastProcessMs < 300) {
            try { frame.recycle() } catch (_: Exception) {}
            return
        }
        lastProcessMs = now
        val w = frame.width
        val h = frame.height
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val detected = openCv.detectDocumentQuad(frame)
                val sharp = openCv.sharpnessScore(frame)
                withContext(Dispatchers.Main) {
                    _quad.value = detected?.let { QuadResult(it, w, h) }
                    val isSharp = sharp > 80.0
                    if (detected != null && isSharp) {
                        val movedLittle = lastQuad == null || quadMovedLittle(lastQuad!!, detected)
                        if (movedLittle) stableFrames++ else stableFrames = 0
                    } else {
                        stableFrames = 0
                    }
                    lastQuad = detected
                    val stable = stableFrames >= 4
                    _isStable.value = stable
                    if (stable && _autoCapture.value) {
                        stableFrames = 0
                        _captureEvent.trySend(Unit)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { frame.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun quadMovedLittle(a: Array<PointF>, b: Array<PointF>): Boolean {
        if (a.size != 4 || b.size != 4) return false
        var total = 0f
        for (i in 0 until 4) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return (total / 4f) < 12f
    }
}
