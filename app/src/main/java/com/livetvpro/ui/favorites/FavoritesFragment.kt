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
        // Refresh list whenever user returns to this screen
        viewModel.loadFavorites()
    }

    private fun setupRecyclerView() {
        favoriteAdapter = FavoriteAdapter(
            onChannelClick = { favChannel ->
                // Convert Favorite back to regular Channel for the player
                val channel = Channel(
                    id = favChannel.id,
                    name = favChannel.name,
                    logoUrl = favChannel.logoUrl,
                    streamUrl = favChannel.streamUrl, // streamUrl must be in the model
                    categoryId = favChannel.categoryId,
                    categoryName = favChannel.categoryName
                )
                ChannelPlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { favChannel ->
                // Action triggered by the Cross button
                viewModel.removeFavorite(favChannel.id)
            }
        )

        binding.recyclerViewFavorites.apply {
            // Using 3 columns as requested previously for grid view
            layoutManager = GridLayoutManager(context, 3)
            adapter = favoriteAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupButtons() {
        // "Clear All" logic
        binding.clearAllButton.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Favorites")
            .setMessage("Are you sure you want to remove all channels from your favorites?")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            favoriteAdapter.submitList(favorites)

            // UI Visibility logic based on if list is empty
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

