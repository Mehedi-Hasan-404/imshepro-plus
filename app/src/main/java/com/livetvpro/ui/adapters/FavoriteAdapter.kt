package com.livetvpro.ui.adapters

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.ItemFavoriteBinding
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.FloatingPlayerHelper

class FavoriteAdapter(
    private val preferencesManager: PreferencesManager,
    private val onFavoriteToggle: (FavoriteChannel) -> Unit
) : ListAdapter<FavoriteChannel, FavoriteAdapter.FavoriteViewHolder>(FavoriteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(
        private val binding: ItemFavoriteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(favorite: FavoriteChannel) {
            // 🔥 DEBUG: Log favorite data to help diagnose issues
            android.util.Log.e("FAVORITE_DEBUG", "=================================")
            android.util.Log.e("FAVORITE_DEBUG", "Favorite: ${favorite.name}")
            android.util.Log.e("FAVORITE_DEBUG", "ID: ${favorite.id}")
            android.util.Log.e("FAVORITE_DEBUG", "Stream URL: ${favorite.streamUrl}")
            android.util.Log.e("FAVORITE_DEBUG", "Links: ${favorite.links}")
            android.util.Log.e("FAVORITE_DEBUG", "Links count: ${favorite.links?.size ?: 0}")
            
            favorite.links?.forEachIndexed { index, link ->
                android.util.Log.e("FAVORITE_DEBUG", "Link $index: ${link.quality} -> ${link.url}")
            }
            android.util.Log.e("FAVORITE_DEBUG", "=================================")
            
            binding.apply {
                // Set channel name
                tvName.text = favorite.name
                
                // Load channel logo with Glide
                Glide.with(imgLogo.context)
                    .load(favorite.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(imgLogo)

                // Handle channel click - check for multiple links first
                root.setOnClickListener {
                    val links = favorite.links
                    
                    // 🔥 FIX: Check if favorite has links
                    if (links.isNullOrEmpty()) {
                        android.util.Log.e("FAVORITE_DEBUG", "ERROR: No links available for ${favorite.name}")
                        android.widget.Toast.makeText(
                            binding.root.context,
                            "No stream URL available for ${favorite.name}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    
                    android.util.Log.e("FAVORITE_DEBUG", "Links available: ${links.size}")
                    
                    // 🔥 FIX: Show dialog if multiple links exist
                    if (links.size > 1) {
                        android.util.Log.e("FAVORITE_DEBUG", "Multiple links - showing dialog")
                        showLinkSelectionDialog(favorite, links)
                    } else {
                        // Single link - launch directly
                        android.util.Log.e("FAVORITE_DEBUG", "Single link - launching directly")
                        launchPlayer(favorite, 0)
                    }
                }

                // Handle remove button click
                removeButton.setOnClickListener {
                    onFavoriteToggle(favorite)
                }
            }
        }

        // 🔥 UPDATED: Use MaterialAlertDialogBuilder for consistency with Channels/Sports
        private fun showLinkSelectionDialog(
            favorite: FavoriteChannel, 
            links: List<com.livetvpro.data.models.ChannelLink>
        ) {
            val context = binding.root.context
            val linkLabels = links.map { it.quality }.toTypedArray()
            
            android.util.Log.e("FAVORITE_DEBUG", "Showing dialog with ${linkLabels.size} options")
            linkLabels.forEachIndexed { index, label ->
                android.util.Log.e("FAVORITE_DEBUG", "Option $index: $label")
            }
            
            MaterialAlertDialogBuilder(context)
                .setTitle("Select Stream")
                .setItems(linkLabels) { dialog, which ->
                    android.util.Log.e("FAVORITE_DEBUG", "User selected index: $which (${linkLabels[which]})")
                    launchPlayer(favorite, which)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    android.util.Log.e("FAVORITE_DEBUG", "User cancelled dialog")
                    dialog.dismiss()
                }
                .show()
        }

        // 🔥 FIXED: Now properly sets streamUrl and accepts linkIndex parameter
        private fun launchPlayer(favorite: FavoriteChannel, linkIndex: Int) {
            val context = binding.root.context
            
            android.util.Log.e("FAVORITE_DEBUG", "launchPlayer called with index: $linkIndex")
            android.util.Log.e("FAVORITE_DEBUG", "Favorite streamUrl: ${favorite.streamUrl}")
            android.util.Log.e("FAVORITE_DEBUG", "Favorite links: ${favorite.links}")
            
            // Convert FavoriteChannel to Channel with proper streamUrl
            val channel = Channel(
                id = favorite.id,
                name = favorite.name,
                logoUrl = favorite.logoUrl,
                streamUrl = favorite.streamUrl,  // 🔥 FIX: Include streamUrl from favorite
                categoryId = favorite.categoryId,
                categoryName = favorite.categoryName,
                links = favorite.links
            )
            
            android.util.Log.e("FAVORITE_DEBUG", "Channel created:")
            android.util.Log.e("FAVORITE_DEBUG", "  - streamUrl: ${channel.streamUrl}")
            android.util.Log.e("FAVORITE_DEBUG", "  - links count: ${channel.links?.size ?: 0}")
            
            // Check if floating player is enabled
            val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
            val hasPermission = FloatingPlayerHelper.hasOverlayPermission(context)
            
            android.util.Log.e("FAVORITE_DEBUG", "Floating enabled: $floatingEnabled, has permission: $hasPermission")
            
            if (floatingEnabled && hasPermission) {
                // Floating player is ON - use floating player with selected link
                android.util.Log.e("FAVORITE_DEBUG", "Launching floating player with link index: $linkIndex")
                FloatingPlayerHelper.launchFloatingPlayer(context, channel, linkIndex)
            } else {
                // Floating player is OFF - use regular PlayerActivity with selected link
                android.util.Log.e("FAVORITE_DEBUG", "Launching normal player with link index: $linkIndex")
                PlayerActivity.startWithChannel(context, channel, linkIndex)
            }
        }
    }

    private class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteChannel>() {
        override fun areItemsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem == newItem
        }
    }
}
