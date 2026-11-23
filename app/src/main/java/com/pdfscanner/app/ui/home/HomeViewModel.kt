package com.pdfscanner.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.db.DocumentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: DocumentDao
) : ViewModel() {

    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    private val _selectedFolder = MutableStateFlow("All")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

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

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteById(doc.id)
            try { File(doc.pdfPath).delete() } catch (_: Exception) {}
            load()
        }
    }
}
