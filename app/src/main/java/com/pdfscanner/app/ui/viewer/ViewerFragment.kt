package com.pdfscanner.app.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pdfscanner.app.R
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.pdf.PdfViewer
import com.pdfscanner.app.databinding.FragmentViewerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ViewerFragment : Fragment() {

    private var _binding: FragmentViewerBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var dao: DocumentDao

    private var currentPage = 0
    private var totalPages = 1
    private var pdfFile: File? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val docId = arguments?.getLong("documentId", -1L) ?: -1L
        binding.textOcr.movementMethod = ScrollingMovementMethod()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val docs = withContext(Dispatchers.IO) { dao.getAll() }
                val doc = docs.firstOrNull { it.id == docId }
                if (doc == null) {
                    Toast.makeText(requireContext(), "Document not found", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                    return@repeatOnLifecycle
                }
                val file = File(doc.pdfPath)
                pdfFile = file
                totalPages = maxOf(withContext(Dispatchers.Default) { PdfViewer.pageCount(file) }, 1)
                currentPage = 0
                renderCurrentPage()

                binding.textOcr.text = if (doc.ocrText.isNotBlank()) {
                    doc.ocrText
                } else {
                    getString(R.string.no_text_found)
                }

                binding.buttonPrevPage.setOnClickListener {
                    if (currentPage > 0) {
                        currentPage--
                        renderCurrentPage()
                    }
                }
                binding.buttonNextPage.setOnClickListener {
                    if (currentPage < totalPages - 1) {
                        currentPage++
                        renderCurrentPage()
                    }
                }
                binding.buttonShare.setOnClickListener {
                    sharePdf(file)
                }
                binding.buttonDelete.setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        dao.deleteById(doc.id)
                        try { file.delete() } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }
    }

    private fun renderCurrentPage() {
        val file = pdfFile ?: return
        binding.textPageIndicator.text = "${currentPage + 1} / $totalPages"
        binding.buttonPrevPage.isEnabled = currentPage > 0
        binding.buttonNextPage.isEnabled = currentPage < totalPages - 1
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                PdfViewer.renderPage(file, currentPage, 1000, 1414)
            }
            if (_binding != null && bmp != null) {
                binding.imagePdf.setImageBitmap(bmp)
            }
        }
    }

    private fun sharePdf(pdfFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
