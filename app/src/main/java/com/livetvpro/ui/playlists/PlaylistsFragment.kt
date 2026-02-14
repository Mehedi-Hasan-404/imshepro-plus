package com.livetvpro.ui.playlists

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.Playlist
import com.livetvpro.databinding.FragmentPlaylistsBinding
import com.livetvpro.ui.adapters.PlaylistAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistsViewModel by viewModels()
    private lateinit var playlistAdapter: PlaylistAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                showAddPlaylistDialog(isFile = true, fileUri = uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            toolbarTitle?.text = "Playlists"
        } catch (e: Exception) {
            // Toolbar not available
        }

        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                // Navigate to CategoryChannelsFragment with playlist ID and name
                // This will reuse the existing CategoryChannelsFragment to show playlist channels
                findNavController().navigate(
                    R.id.action_playlists_to_category,
                    bundleOf(
                        "categoryId" to playlist.id,
                        "categoryName" to playlist.title
                    )
                )
            },
            onEditClick = { playlist ->
                showEditPlaylistDialog(playlist)
            },
            onDeleteClick = { playlist ->
                showDeleteConfirmationDialog(playlist)
            }
        )

        binding.recyclerViewPlaylists.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playlistAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddPlaylist.setOnClickListener {
            showAddOptionsDialog()
        }
    }

    private fun observeViewModel() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter.submitList(playlists)
            updateEmptyState(playlists.isEmpty())
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewPlaylists.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showAddOptionsDialog() {
        val options = arrayOf("Add URL", "Choose File")
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> showAddPlaylistDialog(isFile = false)
                    1 -> openFilePicker()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-mpegURL", 
                "audio/x-mpegurl", 
                "application/vnd.apple.mpegurl", 
                "*/*"
            ))
        }
        filePickerLauncher.launch(intent)
    }

    private fun showAddPlaylistDialog(isFile: Boolean, fileUri: Uri? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_playlist, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.input_title)
        val urlInput = dialogView.findViewById<EditText>(R.id.input_url)

        if (isFile) {
            urlInput.isEnabled = false
            urlInput.setText(fileUri?.toString() ?: "")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Playlist")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val title = titleInput.text.toString().trim()
                val url = urlInput.text.toString().trim()

                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), "Title is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!isFile && url.isEmpty()) {
                    Toast.makeText(requireContext(), "URL is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (isFile && fileUri != null) {
                    // Take persistable URI permission
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            fileUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Permission may already be granted
                    }
                    viewModel.addPlaylist(title, "", true, fileUri.toString())
                } else {
                    viewModel.addPlaylist(title, url, false, "")
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPlaylistDialog(playlist: Playlist) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_playlist, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.input_title)
        val urlInput = dialogView.findViewById<EditText>(R.id.input_url)

        titleInput.setText(playlist.title)
        
        if (playlist.isFile) {
            urlInput.setText(playlist.filePath)
            urlInput.isEnabled = false
        } else {
            urlInput.setText(playlist.url)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Update Playlist Details")
            .setView(dialogView)
            .setPositiveButton("Update") { dialog, _ ->
                val newTitle = titleInput.text.toString().trim()
                val newUrl = urlInput.text.toString().trim()

                if (newTitle.isEmpty()) {
                    Toast.makeText(requireContext(), "Title is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!playlist.isFile && newUrl.isEmpty()) {
                    Toast.makeText(requireContext(), "URL is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updatedPlaylist = playlist.copy(
                    title = newTitle,
                    url = if (!playlist.isFile) newUrl else playlist.url
                )

                viewModel.updatePlaylist(updatedPlaylist)
                dialog.dismiss()
            }
            .setNeutralButton("Delete") { dialog, _ ->
                dialog.dismiss()
                showDeleteConfirmationDialog(playlist)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(playlist: Playlist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete \"${playlist.title}\"?")
            .setPositiveButton("Delete") { dialog, _ ->
                viewModel.deletePlaylist(playlist)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
