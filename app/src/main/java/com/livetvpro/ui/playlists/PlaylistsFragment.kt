package com.livetvpro.ui.playlists

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
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

    // FAB Speed Dial State
    private var isFabExpanded = false
    private var scrimView: View? = null
    private var fabFileContainer: LinearLayout? = null
    private var fabUrlContainer: LinearLayout? = null

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
        setupAnimatedFab()
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

    private fun setupAnimatedFab() {
        val rootLayout = binding.root as ConstraintLayout
        val context = requireContext()
        
        // Create scrim (dark overlay)
        scrimView = View(context).apply {
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
        
        // Create FAB with label for "Add Playlist File"
        fabFileContainer = createFabWithLabel(
            context,
            rootLayout,
            "Add Playlist File",
            R.drawable.ic_folder,
            isTopItem = true
        ) {
            collapseFab()
            openFilePicker()
        }
        rootLayout.addView(fabFileContainer)
        
        // Create FAB with label for "Add Playlist URL"
        fabUrlContainer = createFabWithLabel(
            context,
            rootLayout,
            "Add Playlist URL",
            R.drawable.ic_link,
            isTopItem = false
        ) {
            collapseFab()
            showAddPlaylistDialog(isFile = false)
        }
        rootLayout.addView(fabUrlContainer)
        
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
    
    private fun createFabWithLabel(
        context: android.content.Context,
        parent: ConstraintLayout,
        labelText: String,
        iconRes: Int,
        isTopItem: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val marginBottom = if (isTopItem) {
            resources.getDimensionPixelSize(R.dimen.fab_margin) + 160 // Higher position
        } else {
            resources.getDimensionPixelSize(R.dimen.fab_margin) + 80 // Lower position
        }
        
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = binding.fabAddPlaylist.id
                endToEnd = binding.fabAddPlaylist.id
                bottomMargin = marginBottom
                marginEnd = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
            visibility = View.GONE
            
            // Label card
            val labelCard = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                cardElevation = 4f
                radius = 24f
                setCardBackgroundColor(0xFF8B6914.toInt()) // Brown/golden
                
                addView(TextView(context).apply {
                    text = labelText
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 12, 24, 12)
                })
            }
            addView(labelCard)
            
            // FAB button
            val fab = FloatingActionButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                size = FloatingActionButton.SIZE_MINI
                setImageResource(iconRes)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFB74D.toInt())
                imageTintList = android.content.res.ColorStateList.valueOf(0xFF5D4037.toInt())
                setOnClickListener { onClick() }
            }
            addView(fab)
        }
    }
    
    private fun expandFab() {
        isFabExpanded = true
        
        // Show scrim with fade in (faster)
        scrimView?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(150) // Faster: 150ms instead of 200ms
                .start()
        }
        
        // Rotate main FAB to X (45 degrees) - faster
        binding.fabAddPlaylist.animate()
            .rotation(45f)
            .setDuration(200) // Faster: 200ms instead of 300ms
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Show and animate sub FABs (faster, no delay)
        fabFileContainer?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200) // Faster: 200ms instead of 300ms
                .setStartDelay(0) // No delay
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
        
        fabUrlContainer?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200) // Faster: 200ms instead of 300ms
                .setStartDelay(0) // No delay
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }
    
    private fun collapseFab() {
        isFabExpanded = false
        
        // Hide scrim with fade out (faster)
        scrimView?.animate()
            ?.alpha(0f)
            ?.setDuration(150) // Faster
            ?.withEndAction {
                scrimView?.visibility = View.GONE
            }
            ?.start()
        
        // Rotate main FAB back to + (faster)
        binding.fabAddPlaylist.animate()
            .rotation(0f)
            .setDuration(200) // Faster
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Hide and animate sub FABs (faster)
        fabFileContainer?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(150) // Faster
            ?.withEndAction {
                fabFileContainer?.visibility = View.GONE
            }
            ?.start()
        
        fabUrlContainer?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(150) // Faster
            ?.withEndAction {
                fabUrlContainer?.visibility = View.GONE
            }
            ?.start()
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
