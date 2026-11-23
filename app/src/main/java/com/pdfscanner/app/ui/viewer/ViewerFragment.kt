package com.pdfscanner.app.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pdfscanner.app.R
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.db.DocumentEntity
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
    private var currentDoc: DocumentEntity? = null
    private var showingTable = false

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
                val doc = withContext(Dispatchers.IO) { dao.getById(docId) }
                if (doc == null) {
                    Toast.makeText(requireContext(), "Document not found", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                    return@repeatOnLifecycle
                }
                currentDoc = doc
                (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = doc.title
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

                val hasTable = doc.tableCsv.isNotBlank()
                binding.buttonToggleTable.visibility = if (hasTable) View.VISIBLE else View.GONE
                binding.buttonCopyCsv.visibility = if (hasTable) View.VISIBLE else View.GONE
                if (hasTable) {
                    populateTable(doc.tableCsv)
                    setTableVisible(true)
                }

                binding.buttonToggleTable.setOnClickListener {
                    setTableVisible(!showingTable)
                }

                binding.buttonCopyCsv.setOnClickListener {
                    copyToClipboard("CSV Table", doc.tableCsv, getString(R.string.copied_csv))
                }

                binding.buttonCopyText.setOnClickListener {
                    if (doc.ocrText.isNotBlank()) {
                        copyToClipboard("OCR Text", doc.ocrText, getString(R.string.copied))
                    }
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
                binding.buttonShare.setOnClickListener { sharePdf(file) }
                binding.buttonMoreViewer.setOnClickListener { showMoreMenu(doc, file, it) }
            }
        }
    }

    private fun setTableVisible(show: Boolean) {
        showingTable = show
        binding.scrollTable.visibility = if (show) View.VISIBLE else View.GONE
        binding.textOcr.visibility = if (show) View.GONE else View.VISIBLE
        binding.buttonToggleTable.text = getString(if (show) R.string.view_text else R.string.view_table)
    }

    private fun populateTable(csv: String) {
        val table = binding.tableOcr
        table.removeAllViews()
        val ctx = requireContext()
        val borderColor = ContextCompat.getColor(ctx, R.color.outline)
        val textColor = ContextCompat.getColor(ctx, R.color.on_surface)
        val headerBg = ContextCompat.getColor(ctx, R.color.surface_variant)
        val padH = (10 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()

        val lines = csv.lines().filter { it.isNotBlank() }
        for ((rowIdx, line) in lines.withIndex()) {
            val row = TableRow(ctx)
            val cells = parseCsvRow(line)
            for (cell in cells) {
                val tv = TextView(ctx).apply {
                    text = cell
                    setTextColor(textColor)
                    textSize = 12f
                    if (rowIdx == 0) setTypeface(null, Typeface.BOLD)
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        if (rowIdx == 0) setColor(headerBg)
                        setStroke(1, borderColor)
                    }
                }
                row.addView(tv)
            }
            table.addView(row)
        }
    }

    private fun parseCsvRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i++
                } else if (c == '"') {
                    inQuotes = false
                } else {
                    cur.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        cells.add(cur.toString())
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
            }
            i++
        }
        cells.add(cur.toString())
        return cells
    }

    private fun copyToClipboard(label: String, content: String, toastMsg: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun showMoreMenu(doc: DocumentEntity, file: File, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.rename))
        popup.menu.add(0, 2, 1, getString(R.string.move_folder))
        if (doc.tableCsv.isNotBlank()) {
            popup.menu.add(0, 4, 2, getString(R.string.export_csv))
        }
        popup.menu.add(0, 3, 3, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val input = EditText(requireContext()).apply { setText(currentDoc?.title ?: doc.title) }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.rename)
                        .setView(input)
                        .setPositiveButton(R.string.save) { _, _ ->
                            val newTitle = input.text.toString().trim()
                            if (newTitle.isNotEmpty()) {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    dao.rename(doc.id, newTitle)
                                    withContext(Dispatchers.Main) {
                                        currentDoc = currentDoc?.copy(title = newTitle)
                                        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = newTitle
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                2 -> {
                    val folders = arrayOf("Inbox", "IDs", "Receipts", "School")
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.move_folder)
                        .setItems(folders) { _, which ->
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                dao.moveToFolder(doc.id, folders[which])
                            }
                        }
                        .show()
                }
                4 -> shareCsv(doc)
                3 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.delete)
                        .setMessage(R.string.confirm_delete)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                dao.deleteById(doc.id)
                                try { file.delete() } catch (_: Exception) {}
                                withContext(Dispatchers.Main) {
                                    findNavController().navigateUp()
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun shareCsv(doc: DocumentEntity) {
        try {
            val csvFile = File(requireContext().cacheDir, "${doc.title.replace(Regex("[^A-Za-z0-9_-]"), "_")}.csv")
            csvFile.writeText(doc.tableCsv)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                csvFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_csv)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
