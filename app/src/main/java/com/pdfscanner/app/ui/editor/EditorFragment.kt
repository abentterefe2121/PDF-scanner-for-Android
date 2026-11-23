package com.pdfscanner.app.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.pdfscanner.app.R
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
            onRotate = { idx -> viewModel.rotatePage(idx) },
            onDelete = { idx -> viewModel.deletePage(idx) }
        )
        binding.recyclerPages.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPages.adapter = adapter

        binding.chipOriginal.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.ORIGINAL) }
        binding.chipMagic.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.MAGIC) }
        binding.chipLighten.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.LIGHTEN) }
        binding.chipBw.setOnClickListener { viewModel.setFilter(OpenCvHelper.Filter.BLACK_WHITE) }

        binding.buttonAddPage.setOnClickListener {
            findNavController().navigate(R.id.action_editor_to_scanner)
        }
        binding.buttonDone.setOnClickListener { viewModel.createPdf() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.status.collect { binding.textStatus.text = it } }
                launch {
                    viewModel.pagesVersion.collect {
                        adapter.notifyDataSetChanged()
                    }
                }
                launch {
                    viewModel.isBusy.collect { busy ->
                        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
                        binding.buttonDone.isEnabled = !busy
                        binding.buttonAddPage.isEnabled = !busy
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
