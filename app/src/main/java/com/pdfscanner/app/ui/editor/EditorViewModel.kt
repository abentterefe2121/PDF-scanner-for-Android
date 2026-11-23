package com.pdfscanner.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.core.common.SettingsPrefs
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.core.ocr.HybridOcrEngine
import com.pdfscanner.app.core.ocr.OcrPackDownloader
import com.pdfscanner.app.core.pdf.PdfGenerator
import com.pdfscanner.app.core.vision.OpenCvHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openCv: OpenCvHelper,
    private val ocr: HybridOcrEngine,
    private val packDownloader: OcrPackDownloader,
    private val pdfGenerator: PdfGenerator,
    private val dao: DocumentDao,
    private val deviceTier: DeviceTier,
    private val prefs: SettingsPrefs
) : ViewModel() {

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _pagesVersion = MutableStateFlow(0)
    val pagesVersion: StateFlow<Int> = _pagesVersion.asStateFlow()

    private val _done = MutableStateFlow<Long?>(null)
    val done: StateFlow<Long?> = _done.asStateFlow()

    private val _filter = MutableStateFlow(OpenCvHelper.Filter.MAGIC)
    val filter: StateFlow<OpenCvHelper.Filter> = _filter.asStateFlow()

    init {
        viewModelScope.launch {
            _filter.value = prefs.defaultFilter.first()
        }
    }

    fun setFilter(f: OpenCvHelper.Filter) {
        if (_filter.value == f) return
        _filter.value = f
        viewModelScope.launch(Dispatchers.Default) {
            _isBusy.value = true
            _status.value = "Applying ${f.name.lowercase()} filter…"
            try {
                val dir = File(context.cacheDir, "scans")
                val entries = ScanSession.pages
                for (i in entries.indices) {
                    val entry = entries[i]
                    val raw = ScanSession.loadRawBitmap(i) ?: continue
                    val processed = openCv.processPage(
                        raw, f, entry.quad, entry.quadWidth, entry.quadHeight
                    )
                    val newPath = ScanSession.saveBitmap(dir, "page", processed, deviceTier.jpegQuality)
                    if (newPath != null) {
                        ScanSession.updateProcessed(i, newPath)
                    }
                    BitmapUtils.safeRecycle(raw)
                    BitmapUtils.safeRecycle(processed)
                }
                _pagesVersion.value += 1
                _status.value = ""
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun rotatePage(index: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val raw = ScanSession.loadRawBitmap(index)
            val proc = ScanSession.loadBitmap(index)
            val dir = File(context.cacheDir, "scans")
            val matrix = Matrix().apply { postRotate(90f) }

            val newRawPath = raw?.let { r ->
                val rot = Bitmap.createBitmap(r, 0, 0, r.width, r.height, matrix, true)
                val p = ScanSession.saveBitmap(dir, "raw", rot, 92)
                BitmapUtils.safeRecycle(r)
                BitmapUtils.safeRecycle(rot)
                p
            }
            val newProcPath = proc?.let { pBmp ->
                val rot = Bitmap.createBitmap(pBmp, 0, 0, pBmp.width, pBmp.height, matrix, true)
                val p = ScanSession.saveBitmap(dir, "page", rot, deviceTier.jpegQuality)
                BitmapUtils.safeRecycle(pBmp)
                BitmapUtils.safeRecycle(rot)
                p
            }
            if (newProcPath != null) {
                ScanSession.updateProcessed(index, newProcPath, newRawPath, clearQuad = true)
                _pagesVersion.value += 1
            }
        }
    }

    fun movePage(from: Int, to: Int) {
        ScanSession.move(from, to)
    }

    fun deletePage(index: Int) {
        ScanSession.removeAt(index)
        _pagesVersion.value += 1
    }

    fun defaultDocumentTitle(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "Scan $stamp"
    }

    /** Generate searchable PDF + OCR + save to Room. Runs on Default/IO. */
    fun createPdf(customTitle: String? = null, folder: String = "Inbox") {
        if (ScanSession.paths.isEmpty() || _isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            val pages = mutableListOf<PdfGenerator.Page>()
            try {
                _status.value = "Preparing Amharic + English OCR…"
                packDownloader.ensurePacks { msg -> _status.value = msg }

                val allText = StringBuilder()
                val paths = ScanSession.paths
                for ((i, path) in paths.withIndex()) {
                    _status.value = "OCR page ${i + 1}/${paths.size}…"
                    val bmp = withContext(Dispatchers.Default) {
                        BitmapUtils.decodeSampledFile(path, deviceTier.maxProcessingEdge)
                    } ?: continue
                    val result = withContext(Dispatchers.Default) { ocr.recognize(bmp) }
                    if (result.text.isNotBlank()) {
                        allText.append(result.text).append("\n\n")
                    }
                    pages.add(PdfGenerator.Page(bmp, result.text))
                }

                _status.value = "Building searchable PDF…"
                val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val title = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDocumentTitle()
                val pdfFile = File(pdfDir, "scan_$stamp.pdf")
                val generated = withContext(Dispatchers.Default) {
                    pdfGenerator.generate(pages, pdfFile, title)
                }

                if (generated != null) {
                    val id = withContext(Dispatchers.IO) {
                        dao.insert(
                            DocumentEntity(
                                title = title,
                                pdfPath = generated.absolutePath,
                                pageCount = pages.size,
                                ocrText = allText.toString().take(50_000),
                                folder = folder
                            )
                        )
                    }
                    ScanSession.clear()
                    _status.value = "Done"
                    _done.value = id
                } else {
                    _status.value = "PDF failed — try again"
                }
            } catch (e: Exception) {
                _status.value = "Error: ${e.message}"
            } finally {
                for (p in pages) BitmapUtils.safeRecycle(p.bitmap)
                _isBusy.value = false
            }
        }
    }
}
