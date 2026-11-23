package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latin / Numbers / Symbols OCR — ML Kit Text Recognition with word bounding boxes.
 * Extracts element-level bounding boxes so TextFusion can protect English words,
 * numbers, dates, and superscripts from being corrupted into Amharic characters.
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    companion object { private const val TAG = "MlKitOcr" }

    override fun isAvailable(): Boolean = true

    suspend fun recognizeWords(bitmap: Bitmap): List<OcrWord> {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            recognizer.close()
            val words = mutableListOf<OcrWord>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val rect = element.boundingBox ?: continue
                        val text = TextFusion.cleanToken(element.text)
                        if (text.isNotEmpty()) {
                            words.add(
                                OcrWord(
                                    text = text,
                                    box = OcrBox(rect.left, rect.top, rect.right, rect.bottom),
                                    confidence = 0.90f,
                                    isEthiopic = false
                                )
                            )
                        }
                    }
                }
            }
            words
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit word OCR failed", e)
            emptyList()
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val words = recognizeWords(bitmap)
        val layout = TableExtractor.analyze(words)
        return OcrResult(
            text = layout.orderedText,
            confidence = if (words.isNotEmpty()) 0.90f else 0f,
            language = "eng",
            isOnline = true,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            words = words,
            tables = layout.tables,
            csvText = layout.csvText
        )
    }
}
