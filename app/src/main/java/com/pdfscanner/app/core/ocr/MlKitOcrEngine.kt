package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptionsInterface
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online / Latin OCR — ML Kit Text Recognition.
 *
 * Role in Hybrid:
 * - Excellent for pure English/Latin documents (faster + more accurate than Tesseract for Latin).
 * - Does NOT read Amharic (Ethiopic) — so it is a FALLBACK/boost, never primary for Amharic.
 * - Requires Play Services + internet for model download, then works offline for Latin.
 *
 * HybridOcrEngine tries Tesseract (Amharic) first, ML Kit second, picks higher confidence.
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    companion object { private const val TAG = "MlKitOcr" }

    override fun isAvailable(): Boolean = true // ML Kit handles its own model download

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptionsInterface.LATIN)
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text.trim()
            recognizer.close()
            OcrResult(
                text = text,
                confidence = if (text.isNotEmpty()) 0.9f else 0f,
                language = "eng",
                isOnline = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR failed", e)
            OcrResult("", 0f, "eng", true)
        }
    }
}
