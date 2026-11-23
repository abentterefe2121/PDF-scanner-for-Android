package com.pdfscanner.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pdfscanner.app.R
import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: DocumentAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_home, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return if (menuItem.itemId == R.id.action_settings) {
                    findNavController().navigate(R.id.action_home_to_settings)
                    true
                } else false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        adapter = DocumentAdapter(
            onClick = { doc ->
                val args = Bundle().apply { putLong("documentId", doc.id) }
                findNavController().navigate(R.id.action_home_to_viewer, args)
            },
            onMore = { doc, anchor -> showDocMenu(doc, anchor) }
        )
        binding.recyclerDocuments.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerDocuments.adapter = adapter

        val openScanner = View.OnClickListener {
            findNavController().navigate(R.id.action_home_to_scanner)
        }
        binding.fabScan.setOnClickListener(openScanner)
        binding.buttonEmptyScan.setOnClickListener(openScanner)

        binding.chipFolderAll.setOnClickListener { viewModel.setFolder("All") }
        binding.chipFolderInbox.setOnClickListener { viewModel.setFolder("Inbox") }
        binding.chipFolderIds.setOnClickListener { viewModel.setFolder("IDs") }
        binding.chipFolderReceipts.setOnClickListener { viewModel.setFolder("Receipts") }
        binding.chipFolderSchool.setOnClickListener { viewModel.setFolder("School") }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString().orEmpty())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.documents.collect { docs ->
                    adapter.submit(docs)
                    binding.cardEmpty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerDocuments.visibility = if (docs.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showDocMenu(doc: DocumentEntity, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.share))
        popup.menu.add(0, 2, 1, getString(R.string.rename))
        popup.menu.add(0, 3, 2, getString(R.string.move_folder))
        popup.menu.add(0, 4, 3, getString(R.string.duplicate))
        popup.menu.add(0, 5, 4, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> sharePdf(File(doc.pdfPath))
                2 -> showRenameDialog(doc)
                3 -> showFolderDialog(doc)
                4 -> viewModel.duplicateDocument(doc)
                5 -> showDeleteDialog(doc)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(doc: DocumentEntity) {
        val input = EditText(requireContext()).apply { setText(doc.title) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.renameDocument(doc.id, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFolderDialog(doc: DocumentEntity) {
        val folders = arrayOf("Inbox", "IDs", "Receipts", "School")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.move_folder)
            .setItems(folders) { _, which ->
                viewModel.moveDocument(doc.id, folders[which])
            }
            .show()
    }

    private fun showDeleteDialog(doc: DocumentEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteDocument(doc)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
