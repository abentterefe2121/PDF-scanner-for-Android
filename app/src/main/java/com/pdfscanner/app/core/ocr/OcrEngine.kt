package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap

/**
 * Pure Kotlin bounding box in source-image pixel coordinates (unit-test friendly).
 */
data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Int get() = width * height

    fun iou(other: OcrBox): Float {
        val ix1 = maxOf(left, other.left)
        val iy1 = maxOf(top, other.top)
        val ix2 = minOf(right, other.right)
        val iy2 = minOf(bottom, other.bottom)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        val inter = (ix2 - ix1).toFloat() * (iy2 - iy1).toFloat()
        val union = area + other.area - inter
        return if (union > 0f) inter / union else 0f
    }

    fun overlapRatioOfSmaller(other: OcrBox): Float {
        val ix1 = maxOf(left, other.left)
        val iy1 = maxOf(top, other.top)
        val ix2 = minOf(right, other.right)
        val iy2 = minOf(bottom, other.bottom)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        val inter = (ix2 - ix1).toFloat() * (iy2 - iy1).toFloat()
        val minArea = minOf(area, other.area).toFloat()
        return if (minArea > 0f) inter / minArea else 0f
    }
}

data class OcrWord(
    val text: String,
    val box: OcrBox,
    val confidence: Float, // 0..1
    val isEthiopic: Boolean = text.any { it.code in 0x1200..0x137F }
)

data class OcrTable(
    val rows: List<List<String>>
) {
    fun toCsv(): String = rows.joinToString("\n") { row ->
        row.joinToString(",") { cell ->
            val cleaned = cell.replace("\"", "\"\"")
            if (cleaned.contains(',') || cleaned.contains('"') || cleaned.contains('\n')) {
                "\"$cleaned\""
            } else {
                cleaned
            }
        }
    }

    fun toTsv(): String = rows.joinToString("\n") { row ->
        row.joinToString(" | ")
    }
}

/**
 * Structured OCR result with:
 * - true reading-order [text] (NFC normalized)
 * - spatial [words] for exact PDF text-layer placement
 * - structured [tables] + [csvText] when tables/forms are detected
 */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val language: String,
    val isOnline: Boolean,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val words: List<OcrWord> = emptyList(),
    val tables: List<OcrTable> = emptyList(),
    val csvText: String = ""
)

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun isAvailable(): Boolean
}
