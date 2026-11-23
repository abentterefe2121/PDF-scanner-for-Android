package com.pdfscanner.app.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searchable PDF generator using framework PdfDocument (no heavy iText/PdfBox).
 * - Scales & JPEG-compresses page bitmaps to target <300 KB/page for fast Telegram/WhatsApp sharing.
 * - Embeds invisible OCR text layer using bundled NotoSansEthiopic-Regular.ttf.
 * - Supports merging multiple PDFs into a single document.
 */
@Singleton
class PdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceTier: DeviceTier
) {
    companion object { private const val TAG = "PdfGenerator" }

    data class Page(val bitmap: Bitmap, val ocrText: String = "")

    fun generate(pages: List<Page>, outputFile: File, title: String = "Scanned Document"): File? {
        if (pages.isEmpty()) return null
        val document = PdfDocument()
        val ethiopicTypeface = loadEthiopicTypeface()
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            for (page in pages) {
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, document.pages.size + 1).create()
                val pdfPage = document.startPage(pageInfo)
                val canvas: Canvas = pdfPage.canvas

                val compressed = compressForPdf(page.bitmap)
                val drawBmp = compressed ?: page.bitmap

                val margin = 18f
                val availW = pageInfo.pageWidth - margin * 2
                val availH = pageInfo.pageHeight - margin * 2
                val bmpW = maxOf(drawBmp.width, 1).toFloat()
                val bmpH = maxOf(drawBmp.height, 1).toFloat()
                val scale = minOf(availW / bmpW, availH / bmpH)
                val drawW = bmpW * scale
                val drawH = bmpH * scale
                val left = (pageInfo.pageWidth - drawW) / 2f
                val top = (pageInfo.pageHeight - drawH) / 2f
                val destRect = RectF(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(drawBmp, null, destRect, imagePaint)

                if (compressed != null && compressed !== page.bitmap) {
                    BitmapUtils.safeRecycle(compressed)
                }

                if (page.ocrText.isNotBlank()) {
                    val paint = Paint().apply {
                        typeface = ethiopicTypeface
                        textSize = 8f
                        alpha = 0
                    }
                    val lines = page.ocrText.lines().take(60)
                    var y = top + 12f
                    for (line in lines) {
                        if (y > top + drawH) break
                        if (line.isNotBlank()) {
                            canvas.drawText(line.take(120), left + 4f, y, paint)
                        }
                        y += 12f
                    }
                }

                document.finishPage(pdfPage)
            }

            Log.i(TAG, "Generating PDF titled: $title (tier=${deviceTier.tier})")
            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }
            Log.i(TAG, "PDF generated: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "PDF generation failed", e)
            return null
        } finally {
            document.close()
        }
    }

    /**
     * Merges multiple existing PDFs into [outputFile]. Returns total page count, or 0 on failure.
     */
    fun mergePdfs(sources: List<File>, outputFile: File): Int {
        if (sources.isEmpty()) return 0
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var totalPages = 0
        try {
            for (src in sources) {
                if (!src.exists()) continue
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val maxW = minOf(deviceTier.maxProcessingEdge, 1400)
                        val s = minOf(maxW.toFloat() / maxOf(page.width, 1), maxW.toFloat() / maxOf(page.height, 1))
                        val rw = maxOf((page.width * s).toInt(), 1)
                        val rh = maxOf((page.height * s).toInt(), 1)
                        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        totalPages++
                        val info = PdfDocument.PageInfo.Builder(595, 842, totalPages).create()
                        val outPage = document.startPage(info)
                        val margin = 12f
                        val scale = minOf(
                            (info.pageWidth - margin * 2) / rw,
                            (info.pageHeight - margin * 2) / rh
                        )
                        val dw = rw * scale
                        val dh = rh * scale
                        val left = (info.pageWidth - dw) / 2f
                        val top = (info.pageHeight - dh) / 2f
                        outPage.canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
                        document.finishPage(outPage)
                        BitmapUtils.safeRecycle(bmp)
                    }
                } finally {
                    try { renderer?.close() } catch (_: Exception) {}
                    try { pfd?.close() } catch (_: Exception) {}
                }
            }
            if (totalPages > 0) {
                FileOutputStream(outputFile).use { out -> document.writeTo(out) }
                return totalPages
            }
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "Merge PDF failed", e)
            return 0
        } finally {
            document.close()
        }
    }

    /**
     * Scales to ~150 DPI A4 max (1400px) and re-encodes via JPEG so framework PdfDocument
     * stores compact compressed streams (~150-300 KB/page) instead of huge uncompressed bitmaps.
     */
    private fun compressForPdf(src: Bitmap): Bitmap? {
        return try {
            val targetMax = minOf(deviceTier.maxProcessingEdge, 1400)
            val scaled = BitmapUtils.scaleToMaxEdge(src, targetMax)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, deviceTier.jpegQuality, baos)
            if (scaled !== src) BitmapUtils.safeRecycle(scaled)
            val bytes = baos.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEthiopicTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/NotoSansEthiopic-Regular.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }
}
