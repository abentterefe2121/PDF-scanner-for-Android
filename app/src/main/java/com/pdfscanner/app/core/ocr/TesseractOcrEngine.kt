package com.pdfscanner.app.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract4android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline OCR — Tesseract 5 LSTM with Amharic + English.
 *
 * Why Tesseract for Amharic:
 * - ML Kit does NOT support Ethiopic script.
 * - Tesseract ships `amh.traineddata` (Ethiopic) + `eng.traineddata`.
 * - Works fully offline (no internet, no Play Services) — critical for Ethiopia.
 * - Dual-language `amh+eng` handles mixed Amharic-English documents.
 *
 * Language packs (~4-6MB each) are downloaded on demand to
 * `filesDir/tessdata/` and are NOT bundled in the APK (keeps APK <15MB).
 *
 * Phase 0: stub that returns empty if packs not downloaded yet.
 * Phase 3: full init, PSM 6, Ethiopic-tuned preprocessing.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {

    companion object {
        private const val TAG = "TesseractOcr"
        const val LANG_AMHARIC = "amh"
        const val LANG_ENGLISH = "eng"
    }

    private val tessDataDir: File
        get() = File(context.filesDir, "tessdata")

    fun arePacksDownloaded(): Boolean {
        val amh = File(tessDataDir, "amh.traineddata")
        val eng = File(tessDataDir, "eng.traineddata")
        return amh.exists() && eng.exists()
    }

    override fun isAvailable(): Boolean = arePacksDownloaded()

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        if (!arePacksDownloaded()) {
            Log.w(TAG, "Tessdata not downloaded yet — returning empty (offline OCR pending download)")
            return OcrResult(text = "", confidence = 0f, language = "amh+eng", isOnline = false)
        }
        return try {
            val api = TessBaseAPI()
            // Init with dual language for mixed documents.
            val ok = api.init(tessDataDir.absolutePath, "amh+eng")
            if (!ok) {
                return OcrResult("", 0f, "amh+eng", false)
            }
            // PSM_AUTO = assume uniform block of text (good for documents).
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setVariable("preserve_interword_spaces", "1")
            api.setImage(bitmap)
            val raw = api.utf8Text ?: ""
            api.recycle()
            val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
            OcrResult(
                text = normalized,
                confidence = if (normalized.isNotEmpty()) 0.85f else 0f,
                language = "amh+eng",
                isOnline = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract failed", e)
            OcrResult("", 0f, "amh+eng", false)
        }
    }
}
