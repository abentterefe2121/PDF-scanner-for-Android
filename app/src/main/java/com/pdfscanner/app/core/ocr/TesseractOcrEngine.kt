package com.pdfscanner.app.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline OCR — Tesseract 5 LSTM with Amharic + English.
 * Supports single-init Session for fast multi-page PDF OCR without re-init overhead per page.
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

    inner class Session internal constructor(
        private val api: TessBaseAPI,
        val langSpec: String
    ) : Closeable {
        fun recognize(bitmap: Bitmap): OcrResult {
            return try {
                api.setImage(bitmap)
                val raw = api.getUTF8Text() ?: ""
                val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
                OcrResult(
                    text = normalized,
                    confidence = if (normalized.isNotEmpty()) 0.85f else 0f,
                    language = langSpec,
                    isOnline = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract session failed", e)
                OcrResult("", 0f, langSpec, false)
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

    private fun availableLangSpec(): String? {
        val amhOk = File(tessDataDir, "amh.traineddata").let { it.exists() && it.length() > 100_000 }
        val engOk = File(tessDataDir, "eng.traineddata").let { it.exists() && it.length() > 100_000 }
        return when {
            amhOk && engOk -> "amh+eng"
            amhOk -> "amh"
            engOk -> "eng"
            else -> null
        }
    }

    override fun isAvailable(): Boolean = availableLangSpec() != null

    fun openSession(): Session? {
        val langSpec = availableLangSpec() ?: return null
        return try {
            val api = TessBaseAPI()
            val ok = api.init(context.filesDir.absolutePath, langSpec)
            if (!ok) {
                try { api.recycle() } catch (_: Exception) {}
                return null
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setVariable("preserve_interword_spaces", "1")
            Session(api, langSpec)
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract init failed", e)
            null
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val session = openSession() ?: run {
            Log.w(TAG, "Tessdata not downloaded yet — returning empty")
            return OcrResult(text = "", confidence = 0f, language = "amh+eng", isOnline = false)
        }
        return session.use { it.recognize(bitmap) }
    }
}
