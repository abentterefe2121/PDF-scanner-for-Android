package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid OCR — Offline + Online.
 *
 * Strategy:
 *  1. Run Tesseract (amh+eng) — offline, Amharic-capable. Primary for Ethiopia.
 *     Supports reusing an already-initialized Tesseract Session across multi-page scans.
 *  2. If Ethiopic text found (>20 chars), return immediately.
 *  3. Otherwise run ML Kit (Latin) as fast boost for English documents.
 */
@Singleton
class HybridOcrEngine @Inject constructor(
    private val tesseract: TesseractOcrEngine,
    private val mlKit: MlKitOcrEngine
) : OcrEngine {

    companion object { private const val TAG = "HybridOcr" }

    override fun isAvailable(): Boolean = tesseract.isAvailable() || mlKit.isAvailable()

    fun openTesseractSession(): TesseractOcrEngine.Session? = tesseract.openSession()

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        recognize(bitmap, null)

    suspend fun recognize(bitmap: Bitmap, session: TesseractOcrEngine.Session?): OcrResult {
        val offline = try {
            session?.recognize(bitmap) ?: tesseract.recognize(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Offline OCR failed, falling back to ML Kit", e)
            OcrResult("", 0f, "amh+eng", false)
        }

        val hasEthiopic = offline.text.any { it.code in 0x1200..0x137F }
        if (hasEthiopic && offline.text.length > 20) {
            return offline
        }

        val online = try {
            mlKit.recognize(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Online OCR failed", e)
            OcrResult("", 0f, "eng", true)
        }

        return when {
            offline.text.isBlank() && online.text.isNotBlank() -> online
            online.text.isBlank() && offline.text.isNotBlank() -> offline
            offline.text.isNotBlank() && online.text.isNotBlank() -> {
                if (hasEthiopic) offline else if (online.text.length > offline.text.length) online else offline
            }
            else -> offline
        }
    }
}
