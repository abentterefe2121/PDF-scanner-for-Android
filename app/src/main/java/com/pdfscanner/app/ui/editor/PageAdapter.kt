package com.pdfscanner.app.ui.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.databinding.ItemPageBinding
import java.io.File

class PageAdapter(
    private val onCrop: (Int) -> Unit,
    private val onRotate: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = ScanSession.paths.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(position)
    }

    inner class VH(private val binding: ItemPageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int) {
            val path = ScanSession.paths.getOrNull(position) ?: return
            binding.imagePage.load(File(path)) {
                size(720, 960)
                crossfade(true)
            }
            binding.textPageNum.text = (position + 1).toString()
            binding.buttonCrop.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onCrop(pos)
            }
            binding.buttonRotate.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onRotate(pos)
            }
            binding.buttonDelete.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(pos)
            }
        }
    }
}
