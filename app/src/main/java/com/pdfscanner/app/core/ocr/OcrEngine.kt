package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap

/**
 * OCR result with text + confidence + language used.
 * Text is Unicode NFC normalized (important for Ethiopic fidel characters).
 */
data class OcrResult(
    val text: String,
    val confidence: Float, // 0..1
    val language: String, // "amh+eng", "eng", "amh"
    val isOnline: Boolean
)

/**
 * OCR contract. Hybrid implementation tries Offline first, Online as boost.
 * See docs/STRATEGY.md §3 — Amharic-first advanced pipeline.
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun isAvailable(): Boolean
}
