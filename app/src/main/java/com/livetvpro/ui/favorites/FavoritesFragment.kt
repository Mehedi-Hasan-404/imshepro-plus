package com.livetvpro.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.FragmentFavoritesBinding
import com.livetvpro.ui.adapters.FavoriteAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FavoritesViewModel by viewModels()
    private lateinit var favoriteAdapter: FavoriteAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadFavorites()
    }

    private fun setupRecyclerView() {
        favoriteAdapter = FavoriteAdapter(
            onChannelClick = { favChannel ->
                // Play the channel
                val channel = Channel(
                    id = favChannel.id,
                    name = favChannel.name,
                    logoUrl = favChannel.logoUrl,
                    streamUrl = favChannel.streamUrl,
                    categoryId = favChannel.categoryId,
                    categoryName = favChannel.categoryName
                )
                ChannelPlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { favChannel ->
                // CHANGED: Instead of removing immediately, show confirmation dialog
                showRemoveConfirmation(favChannel)
            }
        )

        binding.recyclerViewFavorites.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = favoriteAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupButtons() {
        binding.clearAllButton.setOnClickListener {
            showClearAllDialog()
        }
    }

    /**
     * Shows a confirmation dialog for removing a SINGLE item
     */
    private fun showRemoveConfirmation(favorite: FavoriteChannel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Favorite")
            .setMessage("Are you sure you want to remove '${favorite.name}' from favorites?")
            .setPositiveButton("Remove") { _, _ ->
                // Only remove if user clicks "Remove"
                viewModel.removeFavorite(favorite.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a confirmation dialog for clearing ALL items
     */
    private fun showClearAllDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Favorites")
            .setMessage("Are you sure you want to remove ALL channels from your favorites?")
            .setPositiveButton("Clear All") { _, _ ->
                viewModel.clearAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            favoriteAdapter.submitList(favorites)

            val isEmpty = favorites.isEmpty()
            binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerViewFavorites.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.clearAllButton.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

