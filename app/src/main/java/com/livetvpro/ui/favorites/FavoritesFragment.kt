// File: app/src/main/java/com/livetvpro/ui/favorites/FavoritesFragment.kt
package com.livetvpro.ui.favorites

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentFavoritesBinding
import com.livetvpro.ui.adapters.FavoriteAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FavoritesViewModel by viewModels()
    private lateinit var favoriteAdapter: FavoriteAdapter
    
    @Inject
    lateinit var listenerManager: NativeListenerManager
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        favoriteAdapter = FavoriteAdapter(
            onChannelClick = { favChannel ->
                // CHANGED: No uniqueId passed. 
                // This implies: "Show ad once for the entire Favorites section"
                val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_FAVORITES)
                
                if (shouldBlock) {
                    return@FavoriteAdapter
                }
                
                // Open Player
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
                showRemoveConfirmation(favChannel) 
            }
        )

        binding.recyclerViewFavorites.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = favoriteAdapter
        }
    }

    private fun setupButtons() {
        binding.clearAllButton.setOnClickListener { 
            showClearAllDialog() 
        }
    }

    private fun showRemoveConfirmation(favorite: FavoriteChannel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove Favorite")
            .setMessage("Remove '${favorite.name}' from your favorites list?")
            .setPositiveButton("Remove") { _, _ -> 
                viewModel.removeFavorite(favorite.id) 
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Favorites")
            .setMessage("This will remove all channels from your list.")
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
