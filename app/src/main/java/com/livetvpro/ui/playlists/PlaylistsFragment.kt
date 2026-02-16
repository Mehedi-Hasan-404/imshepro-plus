package com.livetvpro.ui.playlists

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    // ========== ADDED: FAB Speed Dial State ==========
    private var isFabExpanded = false
    private var scrimView: View? = null
    private var fabAddFile: FloatingActionButton? = null
    private var fabAddUrl: FloatingActionButton? = null
    // ========== END ADDED ==========

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
        setupAnimatedFab()  // ← Changed from setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
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

    // ========== CHANGED: New animated FAB setup ==========
    private fun setupAnimatedFab() {
        val rootLayout = binding.root as ConstraintLayout
        
        // Create scrim (dark overlay)
        scrimView = View(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            setBackgroundColor(0x80000000.toInt()) // Semi-transparent black
            alpha = 0f
            visibility = View.GONE
            setOnClickListener {
                collapseFab()
            }
        }
        rootLayout.addView(scrimView, 0)
        
        // Create FAB for "Add Playlist File"
        fabAddFile = FloatingActionButton(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = binding.fabAddPlaylist.id
                endToEnd = binding.fabAddPlaylist.id
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin_bottom_file)
            }
            setImageResource(R.drawable.ic_folder)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFB74D.toInt())
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF5D4037.toInt())
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
            visibility = View.GONE
            setOnClickListener {
                collapseFab()
                openFilePicker()
            }
        }
        rootLayout.addView(fabAddFile)
        
        // Create FAB for "Add Playlist URL"
        fabAddUrl = FloatingActionButton(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = binding.fabAddPlaylist.id
                endToEnd = binding.fabAddPlaylist.id
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin_bottom_url)
            }
            setImageResource(R.drawable.ic_link)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFB74D.toInt())
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF5D4037.toInt())
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
            visibility = View.GONE
            setOnClickListener {
                collapseFab()
                showAddPlaylistDialog(isFile = false)
            }
        }
        rootLayout.addView(fabAddUrl)
        
        // Main FAB click listener
        binding.fabAddPlaylist.setOnClickListener {
            if (isFabExpanded) {
                collapseFab()
            } else {
                expandFab()
            }
        }
        
        // Change main FAB color to brown/golden
        binding.fabAddPlaylist.backgroundTintList = 
            android.content.res.ColorStateList.valueOf(0xFF8B6914.toInt())
    }
    
    private fun expandFab() {
        isFabExpanded = true
        
        // Show scrim with fade in
        scrimView?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
        
        // Rotate main FAB to X (45 degrees)
        binding.fabAddPlaylist.animate()
            .rotation(45f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Show and animate sub FABs
        fabAddFile?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setStartDelay(50)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
        
        fabAddUrl?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setStartDelay(100)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }
    
    private fun collapseFab() {
        isFabExpanded = false
        
        // Hide scrim with fade out
        scrimView?.animate()
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                scrimView?.visibility = View.GONE
            }
            ?.start()
        
        // Rotate main FAB back to +
        binding.fabAddPlaylist.animate()
            .rotation(0f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Hide and animate sub FABs
        fabAddFile?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(200)
            ?.withEndAction {
                fabAddFile?.visibility = View.GONE
            }
            ?.start()
        
        fabAddUrl?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(200)
            ?.withEndAction {
                fabAddUrl?.visibility = View.GONE
            }
            ?.start()
    }
    // ========== END CHANGED ==========

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
