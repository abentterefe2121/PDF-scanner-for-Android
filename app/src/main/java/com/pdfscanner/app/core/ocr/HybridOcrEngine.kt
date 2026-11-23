package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid OCR — Offline + Online (user choice: "offline and internet").
 *
 * Strategy:
 *  1. Run Tesseract (amh+eng) — offline, Amharic-capable. Primary for Ethiopia.
 *  2. If internet available, also run ML Kit (Latin) as boost for English parts.
 *  3. Pick the result with more text / higher confidence.
 *  4. If both empty, return empty (UI shows "No text found — try better lighting").
 *
 * Future (V2 Premium): Cloud Vision API as online boost for low-quality Amharic scans.
 * Requires API key + internet; kept out of V1 to stay offline-first and free.
 */
@Singleton
class HybridOcrEngine @Inject constructor(
    private val tesseract: TesseractOcrEngine,
    private val mlKit: MlKitOcrEngine
) : OcrEngine {

    companion object { private const val TAG = "HybridOcr" }

    override fun isAvailable(): Boolean = tesseract.isAvailable() || mlKit.isAvailable()

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val offline = try {
            tesseract.recognize(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Offline OCR failed, falling back to ML Kit", e)
            OcrResult("", 0f, "amh+eng", false)
        }

        // If offline found Amharic text, prefer it (ML Kit cannot read Ethiopic).
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

        // Pick longer / more confident result. Merge if both have text.
        return when {
            offline.text.isBlank() && online.text.isNotBlank() -> online
            online.text.isBlank() && offline.text.isNotBlank() -> offline
            offline.text.isNotBlank() && online.text.isNotBlank() -> {
                // Prefer offline for Amharic, online for pure Latin.
                if (hasEthiopic) offline else if (online.text.length > offline.text.length) online else offline
            }
            else -> offline
        }
    }
}
