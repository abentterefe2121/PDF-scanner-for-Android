package com.pdfscanner.app.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.pdfscanner.app.core.common.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searchable PDF generator using framework PdfDocument (no heavy iText/PdfBox).
 *
 * Why PdfDocument:
 * - Lightweight, zero extra APK size, works on 2GB RAM.
 * - Scales page bitmaps cleanly onto A4 (595x842 pt) with RectF + bilinear filtering.
 * - Embeds an invisible OCR text layer so PDFs are searchable + copyable.
 */
@Singleton
class PdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceTier: DeviceTier
) {
    companion object { private const val TAG = "PdfGenerator" }

    data class Page(val bitmap: Bitmap, val ocrText: String = "")

    /** Generate PDF to [outputFile]. Returns file or null on failure. */
    fun generate(pages: List<Page>, outputFile: File, title: String = "Scanned Document"): File? {
        if (pages.isEmpty()) return null
        val document = PdfDocument()
        val ethiopicTypeface = loadEthiopicTypeface()
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            for (page in pages) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    /* width = */ 595, // A4 @ 72dpi
                    /* height = */ 842,
                    /* pageNumber = */ document.pages.size + 1
                ).create()
                val pdfPage = document.startPage(pageInfo)
                val canvas: Canvas = pdfPage.canvas

                val margin = 18f
                val availW = pageInfo.pageWidth - margin * 2
                val availH = pageInfo.pageHeight - margin * 2
                val bmpW = maxOf(page.bitmap.width, 1).toFloat()
                val bmpH = maxOf(page.bitmap.height, 1).toFloat()
                val scale = minOf(availW / bmpW, availH / bmpH)
                val drawW = bmpW * scale
                val drawH = bmpH * scale
                val left = (pageInfo.pageWidth - drawW) / 2f
                val top = (pageInfo.pageHeight - drawH) / 2f
                val destRect = RectF(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(page.bitmap, null, destRect, imagePaint)

                // Invisible OCR text layer (searchable PDF)
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

    private fun loadEthiopicTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/NotoSansEthiopic-Regular.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }
}
