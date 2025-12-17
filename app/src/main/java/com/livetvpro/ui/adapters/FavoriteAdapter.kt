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
) : ListAdapter<FavoriteChannel, FavoriteAdapter.ViewHolder>(FavoriteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(favorite: FavoriteChannel) {
            binding.apply {
                // Set channel name
                tvName.text = favorite.name
                
                // Set category name
                tvCategory.text = favorite.categoryName

                // Load logo
                Glide.with(root.context)
                    .load(favorite.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(imgLogo)

                // Handle the Remove (Cross) button click
                removeButton.setOnClickListener {
                    onFavoriteToggle(favorite)
                }

                // Handle clicking the whole card to play
                root.setOnClickListener {
                    onChannelClick(favorite)
                }
            }
        }
    }

    class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteChannel>() {
        override fun areItemsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem == newItem
        }
    }
}

