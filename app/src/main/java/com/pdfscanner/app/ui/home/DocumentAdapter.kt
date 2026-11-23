package com.pdfscanner.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.core.pdf.PdfViewer
import com.pdfscanner.app.databinding.ItemDocumentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val onClick: (DocumentEntity) -> Unit,
    private val onMore: (DocumentEntity, View) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.VH>() {

    private var items: List<DocumentEntity> = emptyList()
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.Main)

    fun submit(list: List<DocumentEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(doc: DocumentEntity) {
            binding.textTitle.text = doc.title
            binding.textMeta.text = "${doc.folder} • ${dateFmt.format(Date(doc.createdAt))}"
            binding.textPageBadge.text = "${doc.pageCount}p"
            binding.imageThumb.setImageDrawable(null)
            binding.imageThumb.tag = doc.pdfPath
            thumbJob?.cancel()
            thumbJob = scope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    PdfViewer.renderPage(File(doc.pdfPath), 0, 360, 480)
                }
                if (binding.imageThumb.tag == doc.pdfPath && bmp != null) {
                    binding.imageThumb.setImageBitmap(bmp)
                }
            }
            binding.root.setOnClickListener { onClick(doc) }
            binding.buttonMore.setOnClickListener { onMore(doc, it) }
        }
    }
}
