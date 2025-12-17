package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.ItemChannelBinding

class FavoriteAdapter(
    private val onChannelClick: (FavoriteChannel) -> Unit,
    private val onFavoriteToggle: (FavoriteChannel) -> Unit
) : ListAdapter<FavoriteChannel, FavoriteAdapter.FavoriteViewHolder>(FavoriteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(channel: FavoriteChannel) {
            binding.channelName.text = channel.name
            binding.channelName.isSelected = true // Enable Marquee
            
            binding.favoriteIndicator.visibility = android.view.View.VISIBLE

            Glide.with(binding.channelLogo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.channelLogo)

            binding.root.setOnClickListener { onChannelClick(channel) }
            binding.root.setOnLongClickListener {
                onFavoriteToggle(channel)
                true
            }
        }
    }

    class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteChannel>() {
        override fun areItemsTheSame(old: FavoriteChannel, new: FavoriteChannel) = old.id == new.id
        override fun areContentsTheSame(old: FavoriteChannel, new: FavoriteChannel) = old == new
    }
}
