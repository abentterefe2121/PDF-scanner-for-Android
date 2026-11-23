package com.pdfscanner.app.core.ocr

import android.content.Context
import android.util.Log
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
 * Source: tesseract-ocr/tessdata_fast on GitHub raw CDN (LSTM, fast on low-end).
 * Follows HTTP redirects cleanly across hosts.
 */
@Singleton
class OcrPackDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OcrPackDownloader"
        private const val BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
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
                    downloadWithRedirects(BASE + "amh.traineddata", amh)
                }
                if (!eng.exists() || eng.length() < 100_000) {
                    onProgress("Downloading English OCR…")
                    downloadWithRedirects(BASE + "eng.traineddata", eng)
                }
                amh.exists() && eng.exists()
            } catch (e: Exception) {
                Log.w(TAG, "Pack download failed: ${e.message}")
                onProgress("OCR download failed — check internet")
                false
            }
        }

    private fun downloadWithRedirects(startUrl: String, dest: File) {
        val tmp = File(dest.parent, dest.name + ".tmp")
        var current = startUrl
        var conn: HttpURLConnection? = null
        try {
            for (hop in 0 until 5) {
                conn?.disconnect()
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "PDFScanner-Android/1.0")
                    connect()
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location ($code)")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmp.length() < 100_000) {
                    tmp.delete()
                    throw IllegalStateException("Download too small (${tmp.length()} bytes)")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return
            }
            throw IllegalStateException("Too many redirects")
        } finally {
            conn?.disconnect()
        }
    }
}
