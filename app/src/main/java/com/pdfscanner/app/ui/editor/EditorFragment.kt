package com.pdfscanner.app.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pdfscanner.app.R
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.FragmentEditorBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditorFragment : Fragment() {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EditorViewModel by viewModels()
    private lateinit var adapter: PageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PageAdapter(
            onCrop = { idx ->
                val args = Bundle().apply {
                    putInt("pageIndex", idx)
                    putString("filter", viewModel.filter.value.name)
                }
                findNavController().navigate(R.id.action_editor_to_crop, args)
            },
            onRotate = { idx -> viewModel.rotatePage(idx) },
            onDelete = { idx -> viewModel.deletePage(idx) }
        )
        binding.recyclerPages.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPages.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                @Suppress("DEPRECATION")
                val from = vh.adapterPosition
                @Suppress("DEPRECATION")
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                viewModel.movePage(from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.recyclerPages)

        binding.chipOriginal.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.ORIGINAL) }
        binding.chipMagic.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.MAGIC) }
        binding.chipLighten.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.LIGHTEN) }
        binding.chipBw.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.BLACK_WHITE) }

        binding.buttonAddPage.setOnClickListener {
            findNavController().navigate(R.id.action_editor_to_scanner)
        }
        binding.buttonDone.setOnClickListener { showSaveDialog() }
        updateButtons()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filter.collect { f ->
                        val chipId = when (f) {
                            OpenCvHelper.Filter.ORIGINAL -> R.id.chip_original
                            OpenCvHelper.Filter.MAGIC -> R.id.chip_magic
                            OpenCvHelper.Filter.LIGHTEN -> R.id.chip_lighten
                            OpenCvHelper.Filter.BLACK_WHITE -> R.id.chip_bw
                        }
                        if (binding.chipFilters.checkedChipId != chipId) {
                            binding.chipFilters.check(chipId)
                        }
                    }
                }
                launch {
                    viewModel.status.collect { msg ->
                        binding.textStatus.text = msg
                        binding.textProgressMsg.text = msg.ifBlank { getString(R.string.processing) }
                    }
                }
                launch {
                    viewModel.pagesVersion.collect {
                        adapter.notifyDataSetChanged()
                        updateButtons()
                    }
                }
                launch {
                    viewModel.isBusy.collect { busy ->
                        binding.cardProgress.visibility = if (busy) View.VISIBLE else View.GONE
                        updateButtons()
                    }
                }
                launch {
                    viewModel.done.collect { id ->
                        if (id != null) {
                            findNavController().navigate(R.id.action_editor_to_home)
                        }
                    }
                }
            }
        }
    }

    private fun updateButtons() {
        val hasPages = ScanSession.paths.isNotEmpty()
        val busy = viewModel.isBusy.value
        binding.buttonDone.isEnabled = hasPages && !busy
        binding.buttonAddPage.isEnabled = !busy
    }

    private fun showSaveDialog() {
        if (ScanSession.paths.isEmpty()) return
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleLabel = TextView(ctx).apply { text = getString(R.string.document_title) }
        val titleInput = EditText(ctx).apply { setText(viewModel.defaultDocumentTitle()) }
        val folderLabel = TextView(ctx).apply {
            text = getString(R.string.folder)
            setPadding(0, pad / 2, 0, 4)
        }
        val folders = arrayOf("Inbox", "IDs", "Receipts", "School")
        val folderSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, folders)
        }
        container.addView(titleLabel)
        container.addView(titleInput)
        container.addView(folderLabel)
        container.addView(folderSpinner)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.save_document)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = titleInput.text.toString()
                val folder = folders[folderSpinner.selectedItemPosition]
                viewModel.createPdf(title, folder)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        updateButtons()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
