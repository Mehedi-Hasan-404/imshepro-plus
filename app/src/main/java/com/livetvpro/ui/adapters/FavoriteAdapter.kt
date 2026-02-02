package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.ItemFavoriteBinding
import com.livetvpro.utils.GlideExtensions

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
                
                // ✅ FIXED: Use GlideExtensions for automatic SVG support
                GlideExtensions.loadImage(
                    imgLogo,
                    favorite.logoUrl,
                    R.drawable.ic_channel_placeholder,
                    R.drawable.ic_channel_placeholder,
                    isCircular = false
                )

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
