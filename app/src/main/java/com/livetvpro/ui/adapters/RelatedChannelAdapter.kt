package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemRelatedChannelModernBinding
import com.livetvpro.databinding.ItemRelatedEventBinding

class RelatedChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, RecyclerView.ViewHolder>(ChannelDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_CHANNEL = 1
        private const val VIEW_TYPE_EVENT = 2
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.categoryId == "live_events") {
            VIEW_TYPE_EVENT
        } else {
            VIEW_TYPE_CHANNEL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_EVENT -> {
                val binding = ItemRelatedEventBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                EventViewHolder(binding)
            }
            else -> {
                val binding = ItemRelatedChannelModernBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                ChannelViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is EventViewHolder -> holder.bind(item)
            is ChannelViewHolder -> holder.bind(item)
        }
    }

    // Event ViewHolder
    inner class EventViewHolder(
        private val binding: ItemRelatedEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }
        }

        fun bind(channel: Channel) {
            // Parse team names from the title
            val teams = channel.name.split(" vs ", ignoreCase = true)
            val team1Name = teams.getOrNull(0) ?: ""
            val team2Name = teams.getOrNull(1) ?: ""
            
            binding.eventTitle.text = channel.name
            binding.eventLeague.text = channel.categoryName
            
            // Load team logos (you'll need to add team1LogoUrl and team2LogoUrl to Channel model)
            // For now, using the same logo for both teams as placeholder
            Glide.with(binding.team1Logo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.team1Logo)
            
            Glide.with(binding.team2Logo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.team2Logo)
            
            // Show live indicator if it's a live event
            // You'd need to pass this information through the Channel model
            binding.liveIndicatorContainer.visibility = View.GONE
        }
    }

    // Regular Channel ViewHolder
    inner class ChannelViewHolder(
        private val binding: ItemRelatedChannelModernBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelName.isSelected = true

            if (channel.categoryId == "live_events" && channel.categoryName.isNotEmpty()) {
                binding.leagueBadge.visibility = View.VISIBLE
                binding.leagueBadge.text = channel.categoryName
            } else {
                binding.leagueBadge.visibility = View.GONE
            }

            Glide.with(binding.channelLogo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.channelLogo)
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
