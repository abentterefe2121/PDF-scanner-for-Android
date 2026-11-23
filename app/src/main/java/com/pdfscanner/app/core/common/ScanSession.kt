package com.pdfscanner.app.core.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the current scan session (list of page entries).
 * File-based (not in-memory Bitmaps) to avoid OOM on 2GB devices.
 * Keeps rawPath + processedPath so filter changes are non-destructive.
 */
object ScanSession {

    data class PageEntry(
        val rawPath: String,
        val processedPath: String,
        val quad: Array<PointF>? = null,
        val quadWidth: Int = 0,
        val quadHeight: Int = 0
    )

    private val _pages = mutableListOf<PageEntry>()
    val pages: List<PageEntry> get() = _pages.toList()
    val paths: List<String> get() = _pages.map { it.processedPath }

    fun clear() {
        for (p in _pages) {
            try { File(p.rawPath).delete() } catch (_: Exception) {}
            try { File(p.processedPath).delete() } catch (_: Exception) {}
        }
        _pages.clear()
    }

    fun addPage(entry: PageEntry) {
        _pages.add(entry)
    }

    fun updateProcessed(index: Int, newProcessedPath: String, newRawPath: String? = null) {
        if (index !in _pages.indices) return
        val old = _pages[index]
        if (old.processedPath != newProcessedPath) {
            try { File(old.processedPath).delete() } catch (_: Exception) {}
        }
        if (newRawPath != null && old.rawPath != newRawPath) {
            try { File(old.rawPath).delete() } catch (_: Exception) {}
        }
        _pages[index] = old.copy(
            rawPath = newRawPath ?: old.rawPath,
            processedPath = newProcessedPath
        )
    }

    fun removeAt(index: Int) {
        if (index in _pages.indices) {
            val old = _pages.removeAt(index)
            try { File(old.rawPath).delete() } catch (_: Exception) {}
            try { File(old.processedPath).delete() } catch (_: Exception) {}
        }
    }

    fun move(from: Int, to: Int) {
        if (from in _pages.indices && to in _pages.indices && from != to) {
            val item = _pages.removeAt(from)
            _pages.add(to, item)
        }
    }

    fun loadBitmap(index: Int): Bitmap? {
        if (index !in _pages.indices) return null
        return try {
            BitmapFactory.decodeFile(_pages[index].processedPath)
        } catch (_: Exception) { null }
    }

    fun loadRawBitmap(index: Int): Bitmap? {
        if (index !in _pages.indices) return null
        return try {
            BitmapFactory.decodeFile(_pages[index].rawPath)
        } catch (_: Exception) { null }
    }

    fun saveBitmap(dir: File, prefix: String, bitmap: Bitmap, quality: Int = 85): String? {
        return try {
            dir.mkdirs()
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}_${_pages.size}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            file.absolutePath
        } catch (_: Exception) { null }
    }
}
