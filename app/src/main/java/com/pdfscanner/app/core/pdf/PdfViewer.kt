package com.pdfscanner.app.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Renders PDF pages via framework PdfRenderer (lightweight, no extra deps).
 * Used by ViewerFragment and Home thumbnails.
 */
object PdfViewer {

    fun pageCount(pdfFile: File): Int {
        if (!pdfFile.exists()) return 0
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (_: Exception) {
            0
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun renderPage(pdfFile: File, pageIndex: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (!pdfFile.exists()) return null
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            page = renderer.openPage(pageIndex)
            val scale = minOf(
                maxWidth.toFloat() / maxOf(page.width, 1),
                maxHeight.toFloat() / maxOf(page.height, 1)
            )
            val w = maxOf((page.width * scale).toInt(), 1)
            val h = maxOf((page.height * scale).toInt(), 1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (_: Exception) {
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
