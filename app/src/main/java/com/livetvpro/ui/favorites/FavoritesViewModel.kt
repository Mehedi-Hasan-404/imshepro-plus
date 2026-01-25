package com.livetvpro.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentFavoritesBinding
import com.livetvpro.ui.adapters.FavoriteAdapter
import com.livetvpro.ui.player.PlayerActivity
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
                val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_FAVORITES)
                
                if (shouldBlock) {
                    return@FavoriteAdapter
                }
                
                [span_0](start_span)[span_1](start_span)// ✅ KEY FIX: Try to get the "Fresh" channel data first[span_0](end_span)[span_1](end_span)
                // The DB snapshot might lack links if added long ago.
                val liveChannel = viewModel.getLiveChannel(favChannel.id)
                
                // If live channel found, use it. Otherwise convert the favorite snapshot.
                val finalChannel = liveChannel ?: convertToChannel(favChannel)

                // Now check links on the FINAL channel object (which is fresh)
                if (finalChannel.links != null && finalChannel.links.isNotEmpty() && finalChannel.links.size > 1) {
                    showLinkSelectionDialog(finalChannel)
                } else {
                    // Start player directly with the fresh channel object
                    PlayerActivity.startWithChannel(requireContext(), finalChannel)
                }
            },
            onFavoriteToggle = { favChannel -> 
                showRemoveConfirmation(favChannel) 
            }
        )

        binding.recyclerViewFavorites.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = favoriteAdapter
            itemAnimator = null 
        }
    }

    private fun convertToChannel(favorite: FavoriteChannel): Channel {
        val channelLinks = favorite.links?.map { favoriteLink ->
            ChannelLink(
                quality = favoriteLink.quality,
                url = favoriteLink.url
            )
        }
        
        return Channel(
            id = favorite.id,
            name = favorite.name,
            logoUrl = favorite.logoUrl,
            streamUrl = favorite.streamUrl,
            [span_2](start_span)// Use original category info[span_2](end_span) instead of hardcoding "favorites"
            categoryId = favorite.categoryId,
            categoryName = favorite.categoryName,
            links = channelLinks
        )
    }

    // ✅ UPDATED: Takes 'Channel' instead of 'FavoriteChannel' to support the fresh object
    private fun showLinkSelectionDialog(channel: Channel) {
        val links = channel.links ?: return
        val linkLabels = links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream")
            .setItems(linkLabels) { dialog, which ->
                val selectedLink = links[which]
                
                // Create a copy with the selected URL for the player
                val modifiedChannel = channel.copy(
                    streamUrl = selectedLink.url
                )
                
                PlayerActivity.startWithChannel(requireContext(), modifiedChannel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
