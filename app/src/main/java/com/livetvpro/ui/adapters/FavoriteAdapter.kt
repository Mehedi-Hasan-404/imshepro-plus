package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.ItemFavoriteBinding

class FavoriteAdapter(
    private val onChannelClick: (FavoriteChannel) -> Unit,
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
                
                // ✅ REMOVED: tvCategory reference (no longer in layout)
                // The new layout doesn't show category name for cleaner look
                
                // Load channel logo with Glide
                Glide.with(imgLogo.context)
                    .load(favorite.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(imgLogo)

                // Handle channel click
                root.setOnClickListener {
                    onChannelClick(favorite)
                }

                // Handle remove button click
                removeButton.setOnClickListener {
                    onFavoriteToggle(favorite)
                }
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
