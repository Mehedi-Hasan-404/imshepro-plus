package com.livetvpro.ui.adapters

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
            binding.apply {
                // Set channel name
                tvName.text = favorite.name
                
                // Load channel logo with Glide
                Glide.with(imgLogo.context)
                    .load(favorite.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(imgLogo)

                // Handle channel click - launch player based on setting
                root.setOnClickListener {
                    launchPlayer(favorite)
                }

                // Handle remove button click
                removeButton.setOnClickListener {
                    onFavoriteToggle(favorite)
                }
            }
        }

        private fun launchPlayer(favorite: FavoriteChannel) {
            val context = binding.root.context
            
            // Convert FavoriteChannel to Channel
            val channel = Channel(
                id = favorite.id,
                name = favorite.name,
                channel_logo = favorite.logoUrl,
                category = favorite.category,
                links = favorite.links
            )
            
            // Check if floating player is enabled
            if (preferencesManager.isFloatingPlayerEnabled() && 
                FloatingPlayerHelper.hasOverlayPermission(context)) {
                
                // Floating player is ON - use floating player
                FloatingPlayerHelper.launchFloatingPlayer(context, channel)
            } else {
                // Floating player is OFF - use regular PlayerActivity
                openFullscreenPlayer(favorite)
            }
        }

        private fun openFullscreenPlayer(favorite: FavoriteChannel) {
            val context = binding.root.context
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra("stream_url", favorite.links.firstOrNull() ?: "")
                putExtra("title", favorite.name)
                putExtra("channel_logo", favorite.logoUrl)
            }
            context.startActivity(intent)
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
