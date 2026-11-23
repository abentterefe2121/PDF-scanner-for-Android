package com.pdfscanner.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.vision.OpenCvHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Document Intelligence OCR Pipeline:
 * 1. Illumination-normalize image via OpenCV (`prepareForOcr`) + detect physical table grid (`detectTableGrid`).
 * 2. Run Tesseract (`amh`, ResultIterator word boxes) + ML Kit (`Text.Element` word boxes).
 * 3. Re-OCR any missed bordered table cells at cell level.
 * 4. Fuse per bounding box via `TextFusion` (stops numbers/superscripts/English from turning into Amharic).
 * 5. Reconstruct true row-by-row reading order & structured 2D tables (`TableExtractor`).
 */
@Singleton
class HybridOcrEngine @Inject constructor(
    private val tesseract: TesseractOcrEngine,
    private val mlKit: MlKitOcrEngine,
    private val openCv: OpenCvHelper
) : OcrEngine {

    companion object { private const val TAG = "HybridOcr" }

    override fun isAvailable(): Boolean = tesseract.isAvailable() || mlKit.isAvailable()

    fun openTesseractSession(): TesseractOcrEngine.Session? =
        tesseract.openSession(amharicOnlyWhenPossible = true)

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        recognize(bitmap, null)

    suspend fun recognize(bitmap: Bitmap, session: TesseractOcrEngine.Session?): OcrResult {
        val cleanBmp = openCv.prepareForOcr(bitmap)
        val ownClean = cleanBmp !== bitmap
        try {
            val grid = openCv.detectTableGrid(bitmap)

            val mlKitWords = try {
                mlKit.recognizeWords(cleanBmp)
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit failed", e)
                emptyList()
            }

            val tessWords = try {
                val active = session ?: tesseract.openSession(amharicOnlyWhenPossible = mlKitWords.isNotEmpty())
                if (active != null) {
                    val words = active.recognizeWords(cleanBmp).toMutableList()
                    // For physical bordered tables, re-OCR any empty cell explicitly via cell crop
                    if (grid != null && grid.isValidTable) {
                        fillMissedGridCells(cleanBmp, grid, words, mlKitWords, active)
                    }
                    if (session == null) active.close()
                    words
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tesseract failed", e)
                emptyList()
            }

            val fusedWords = TextFusion.fuse(tessWords, mlKitWords)
            val layout = TableExtractor.analyze(fusedWords, grid)
            val avgConf = if (fusedWords.isNotEmpty()) {
                fusedWords.map { it.confidence }.average().toFloat()
            } else 0f

            val hasEthiopic = fusedWords.any { it.isEthiopic }
            val hasLatin = fusedWords.any { !it.isEthiopic }
            val lang = when {
                hasEthiopic && hasLatin -> "amh+eng"
                hasEthiopic -> "amh"
                else -> "eng"
            }

            return OcrResult(
                text = layout.orderedText,
                confidence = avgConf,
                language = lang,
                isOnline = mlKitWords.isNotEmpty(),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                words = fusedWords,
                tables = layout.tables,
                csvText = layout.csvText
            )
        } finally {
            if (ownClean) BitmapUtils.safeRecycle(cleanBmp)
        }
    }

    private fun fillMissedGridCells(
        cleanBmp: Bitmap,
        grid: TableExtractor.GridLines,
        tessWords: MutableList<OcrWord>,
        mlKitWords: List<OcrWord>,
        session: TesseractOcrEngine.Session
    ) {
        val maxCellsToCheck = 48
        var checked = 0
        for (r in 0 until grid.rowYs.size - 1) {
            for (c in 0 until grid.colXs.size - 1) {
                if (checked >= maxCellsToCheck) return
                val cellBox = OcrBox(grid.colXs[c], grid.rowYs[r], grid.colXs[c + 1], grid.rowYs[r + 1])
                if (cellBox.width < 18 || cellBox.height < 14) continue
                val hasAny = tessWords.any {
                    it.box.centerX in cellBox.left.toFloat()..cellBox.right.toFloat() &&
                        it.box.centerY in cellBox.top.toFloat()..cellBox.bottom.toFloat()
                } || mlKitWords.any {
                    it.box.centerX in cellBox.left.toFloat()..cellBox.right.toFloat() &&
                        it.box.centerY in cellBox.top.toFloat()..cellBox.bottom.toFloat()
                }
                if (!hasAny) {
                    checked++
                    tessWords.addAll(session.recognizeRegionWords(cleanBmp, cellBox))
                }
            }
        }
    }
}
