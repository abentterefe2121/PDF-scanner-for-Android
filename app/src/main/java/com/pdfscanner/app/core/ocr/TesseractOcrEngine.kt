package com.pdfscanner.app.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline OCR — Tesseract 5 LSTM with ResultIterator word bounding boxes.
 * Uses pure `amh` when paired with ML Kit (eliminates mixed-script number/superscript corruption),
 * and falls back to `amh+eng` when offline-only.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {

    companion object {
        private const val TAG = "TesseractOcr"
    }

    inner class Session internal constructor(
        private val api: TessBaseAPI,
        val langSpec: String
    ) : Closeable {

        fun recognizeWords(
            bitmap: Bitmap,
            psm: Int = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        ): List<OcrWord> {
            return try {
                api.setPageSegMode(psm)
                api.setImage(bitmap)
                api.getUTF8Text() // populates ResultIterator
                val iter = api.resultIterator ?: return emptyList()
                val words = mutableListOf<OcrWord>()
                try {
                    iter.begin()
                    do {
                        val raw = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val text = raw?.let { TextFusion.cleanToken(it) } ?: ""
                        if (text.isNotEmpty()) {
                            val rect = iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            val conf = (iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f)
                                .coerceIn(0f, 1f)
                            words.add(
                                OcrWord(
                                    text = text,
                                    box = OcrBox(rect.left, rect.top, rect.right, rect.bottom),
                                    confidence = conf,
                                    isEthiopic = TextFusion.containsEthiopic(text)
                                )
                            )
                        }
                    } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    iter.delete()
                }
                words
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract word recognition failed", e)
                emptyList()
            }
        }

        fun recognizeRegionWords(bitmap: Bitmap, box: OcrBox): List<OcrWord> {
            return try {
                val w = (box.width - 4).coerceAtLeast(8)
                val h = (box.height - 4).coerceAtLeast(8)
                if (box.left + 2 + w > bitmap.width || box.top + 2 + h > bitmap.height) return emptyList()
                val crop = Bitmap.createBitmap(bitmap, box.left + 2, box.top + 2, w, h)
                val rawWords = recognizeWords(crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                crop.recycle()
                rawWords.map { rw ->
                    rw.copy(
                        box = OcrBox(
                            rw.box.left + box.left + 2,
                            rw.box.top + box.top + 2,
                            rw.box.right + box.left + 2,
                            rw.box.bottom + box.top + 2
                        )
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        override fun close() {
            try { api.recycle() } catch (_: Exception) {}
        }
    }

    private val tessDataDir: File
        get() = File(context.filesDir, "tessdata")

    fun arePacksDownloaded(): Boolean {
        val amh = File(tessDataDir, "amh.traineddata")
        val eng = File(tessDataDir, "eng.traineddata")
        return amh.exists() && amh.length() > 100_000 && eng.exists() && eng.length() > 100_000
    }

    private fun availableLangSpec(amharicOnlyWhenPossible: Boolean): String? {
        val amhOk = File(tessDataDir, "amh.traineddata").let { it.exists() && it.length() > 100_000 }
        val engOk = File(tessDataDir, "eng.traineddata").let { it.exists() && it.length() > 100_000 }
        return when {
            amharicOnlyWhenPossible && amhOk -> "amh"
            amhOk && engOk -> "amh+eng"
            amhOk -> "amh"
            engOk -> "eng"
            else -> null
        }
    }

    override fun isAvailable(): Boolean = availableLangSpec(false) != null

    fun openSession(amharicOnlyWhenPossible: Boolean = true): Session? {
        val langSpec = availableLangSpec(amharicOnlyWhenPossible) ?: return null
        return try {
            val api = TessBaseAPI()
            val ok = api.init(context.filesDir.absolutePath, langSpec)
            if (!ok) {
                try { api.recycle() } catch (_: Exception) {}
                return null
            }
            api.setVariable("preserve_interword_spaces", "1")
            Session(api, langSpec)
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract init failed", e)
            null
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val session = openSession(amharicOnlyWhenPossible = false)
            ?: return OcrResult("", 0f, "amh+eng", false, bitmap.width, bitmap.height)
        return session.use { s ->
            val words = s.recognizeWords(bitmap)
            val layout = TableExtractor.analyze(words)
            val avgConf = if (words.isNotEmpty()) words.map { it.confidence }.average().toFloat() else 0f
            OcrResult(
                text = layout.orderedText,
                confidence = avgConf,
                language = s.langSpec,
                isOnline = false,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                words = words,
                tables = layout.tables,
                csvText = layout.csvText
            )
        }
    }
}
