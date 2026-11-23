package com.pdfscanner.app.core.ocr

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads Tesseract language packs (amh + eng) on demand.
 * Packs are ~4-6MB each, stored in filesDir/tessdata/, NOT in APK.
 * Source: tesseract-ocr/tessdata_fast on GitHub (LSTM, fast on low-end).
 * Returns true if both packs present after download.
 */
@Singleton
class OcrPackDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val BASE = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/"
    }

    private val tessDataDir: File
        get() = File(context.filesDir, "tessdata")

    suspend fun ensurePacks(onProgress: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            tessDataDir.mkdirs()
            val amh = File(tessDataDir, "amh.traineddata")
            val eng = File(tessDataDir, "eng.traineddata")
            try {
                if (!amh.exists() || amh.length() < 100_000) {
                    onProgress("Downloading Amharic OCR…")
                    download(URL(BASE + "amh.traineddata"), amh)
                }
                if (!eng.exists() || eng.length() < 100_000) {
                    onProgress("Downloading English OCR…")
                    download(URL(BASE + "eng.traineddata"), eng)
                }
                amh.exists() && eng.exists()
            } catch (e: Exception) {
                onProgress("OCR download failed — check internet")
                false
            }
        }

    private fun download(url: URL, dest: File) {
        val tmp = File(dest.parent, dest.name + ".tmp")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
        if (tmp.length() < 100_000) {
            tmp.delete()
            throw IllegalStateException("Download too small")
        }
        tmp.renameTo(dest)
    }
}
