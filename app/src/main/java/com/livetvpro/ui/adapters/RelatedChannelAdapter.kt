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
import java.text.SimpleDateFormat
import java.util.*

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

    // ✅ EVENT VIEW HOLDER - Horizontal card with Team 1 vs Team 2
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
            // Event title (e.g., "Arsenal vs Chelsea")
            binding.eventTitle.text = channel.name
            
            // League name (e.g., "Premier League")
            binding.eventLeague.text = channel.categoryName
            
            // ✅ Load ACTUAL team logos from event data
            Glide.with(binding.team1Logo)
                .load(channel.team1Logo.ifEmpty { channel.logoUrl })
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.team1Logo)
            
            Glide.with(binding.team2Logo)
                .load(channel.team2Logo.ifEmpty { channel.logoUrl })
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.team2Logo)
            
            // ✅ SMART LIVE DETECTION: Check if current time is between start and end
            val isCurrentlyLive = try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                
                val startTime = inputFormat.parse(channel.startTime)?.time ?: 0L
                val endTime = if (channel.endTime.isNotEmpty()) {
                    inputFormat.parse(channel.endTime)?.time ?: Long.MAX_VALUE
                } else {
                    Long.MAX_VALUE
                }
                val currentTime = System.currentTimeMillis()
                
                // Check if current time is between start and end
                currentTime in startTime..endTime
            } catch (e: Exception) {
                channel.isLive // Fallback to API's isLive flag
            }
            
            // ✅ Show live indicator based on calculated live status
            binding.liveIndicatorContainer.visibility = if (isActuallyLive) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    // Regular Channel ViewHolder - Grid card (unchanged)
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
