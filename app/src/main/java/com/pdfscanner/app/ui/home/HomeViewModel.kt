package com.pdfscanner.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.core.pdf.PdfGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DocumentDao,
    private val pdfGenerator: PdfGenerator
) : ViewModel() {

    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    private val _selectedFolder = MutableStateFlow("All")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private var currentQuery: String = ""

    init { load() }

    fun setFolder(folder: String) {
        _selectedFolder.value = folder
        load()
    }

    fun search(query: String) {
        currentQuery = query.trim()
        load()
    }

    fun toggleSelect(id: Long) {
        val cur = _selectedIds.value.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        _selectedIds.value = cur
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun load() {
        val folder = _selectedFolder.value
        val q = currentQuery
        viewModelScope.launch {
            _documents.value = withContext(Dispatchers.IO) {
                try {
                    if (q.isBlank()) {
                        if (folder == "All") dao.getAll() else dao.getByFolder(folder)
                    } else {
                        val fts = q.split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { "${it.replace("\"", "")}*" }
                        if (fts.isNotBlank()) {
                            runCatching { dao.search(q, fts, folder) }
                                .getOrElse { dao.searchLikeOnly(q, folder) }
                        } else {
                            dao.searchLikeOnly(q, folder)
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    fun renameDocument(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.rename(id, trimmed)
            load()
        }
    }

    fun moveDocument(id: Long, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.moveToFolder(id, folder)
            load()
        }
    }

    fun duplicateDocument(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val src = File(doc.pdfPath)
            if (!src.exists()) return@launch
            val dest = File(src.parentFile, "copy_${System.currentTimeMillis()}_${src.name}")
            src.copyTo(dest, overwrite = true)
            dao.insert(
                DocumentEntity(
                    title = "${doc.title} (Copy)",
                    pdfPath = dest.absolutePath,
                    pageCount = doc.pageCount,
                    ocrText = doc.ocrText,
                    tableCsv = doc.tableCsv,
                    folder = doc.folder
                )
            )
            load()
        }
    }

    fun mergeSelected(onDone: (Int) -> Unit) {
        val ids = _selectedIds.value
        val selectedDocs = _documents.value.filter { ids.contains(it.id) }
        if (selectedDocs.size < 2) return
        viewModelScope.launch {
            val mergedCount = withContext(Dispatchers.Default) {
                val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outFile = File(pdfDir, "merged_$stamp.pdf")
                val totalPages = pdfGenerator.mergePdfs(selectedDocs.map { File(it.pdfPath) }, outFile)
                if (totalPages > 0) {
                    val mergedOcr = selectedDocs.joinToString("\n\n") { it.ocrText }.take(50_000)
                    withContext(Dispatchers.IO) {
                        dao.insert(
                            DocumentEntity(
                                title = "Merged $stamp",
                                pdfPath = outFile.absolutePath,
                                pageCount = totalPages,
                                ocrText = mergedOcr
                            )
                        )
                    }
                    selectedDocs.size
                } else 0
            }
            _selectedIds.value = emptySet()
            load()
            onDone(mergedCount)
        }
    }

    fun deleteSelected() {
        val ids = _selectedIds.value
        val selectedDocs = _documents.value.filter { ids.contains(it.id) }
        viewModelScope.launch(Dispatchers.IO) {
            for (doc in selectedDocs) {
                dao.deleteById(doc.id)
                try { File(doc.pdfPath).delete() } catch (_: Exception) {}
            }
            _selectedIds.value = emptySet()
            load()
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteById(doc.id)
            try { File(doc.pdfPath).delete() } catch (_: Exception) {}
            load()
        }
    }
}
