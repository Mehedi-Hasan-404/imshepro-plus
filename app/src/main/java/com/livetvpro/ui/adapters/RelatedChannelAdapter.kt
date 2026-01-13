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
            // Extract team names from event title (e.g., "BaÅakÅehir vs Boluspor")
            val teamNames = channel.name.split(" vs ", ignoreCase = true)
            val team1Name = teamNames.getOrNull(0)?.trim() ?: ""
            val team2Name = teamNames.getOrNull(1)?.trim() ?: ""
            
            // Set team names
            val team1NameView = binding.root.findViewById<android.widget.TextView>(R.id.team1_name)
            val team2NameView = binding.root.findViewById<android.widget.TextView>(R.id.team2_name)
            team1NameView?.text = team1Name
            team2NameView?.text = team2Name
            
            // League name with category (e.g., "Football | Turkiye Kupasi, Group A")
            binding.eventLeague.text = channel.categoryName
            
            // Category icon (Football/Cricket icon)
            val categoryIconView = binding.root.findViewById<android.widget.ImageView>(R.id.category_icon)
            categoryIconView?.let {
                Glide.with(it)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(it)
            }
            
            // Load team logos with circular crop
            Glide.with(binding.team1Logo)
                .load(channel.team1Logo.ifEmpty { channel.logoUrl })
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .circleCrop()
                .into(binding.team1Logo)
            
            Glide.with(binding.team2Logo)
                .load(channel.team2Logo.ifEmpty { channel.logoUrl })
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .circleCrop()
                .into(binding.team2Logo)
            
            // Get views for time display
            val eventTimeView = binding.root.findViewById<android.widget.TextView>(R.id.event_time)
            val eventDateView = binding.root.findViewById<android.widget.TextView>(R.id.event_date)
            val eventCountdownView = binding.root.findViewById<android.widget.TextView>(R.id.event_countdown)
            
            // Check if event is live
            val isCurrentlyLive = try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                
                val startTime = inputFormat.parse(channel.startTime)?.time ?: 0L
                val endTimeValue = if (channel.endTime.isNotEmpty()) {
                    inputFormat.parse(channel.endTime)?.time ?: Long.MAX_VALUE
                } else {
                    Long.MAX_VALUE
                }
                val currentTime = System.currentTimeMillis()
                
                currentTime in startTime..endTimeValue
            } catch (e: Exception) {
                channel.isLive
            }
            
            if (isCurrentlyLive) {
                // LIVE EVENT: Show animated broadcast icon with "Live" text below
                binding.liveIndicatorContainer.visibility = View.VISIBLE
                eventDateView?.visibility = View.GONE
                eventCountdownView?.visibility = View.GONE
                
                // Start broadcast-style pulsing animations on all three rings
                val pulseBg = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_bg)
                val pulseRing2 = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_ring_2)
                val pulseRing3 = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_ring_3)
                
                // Apply different animations to create staggered broadcast effect
                pulseBg?.let {
                    val animation = android.view.animation.AnimationUtils.loadAnimation(
                        binding.root.context,
                        R.anim.live_pulse_ring_1
                    )
                    it.startAnimation(animation)
                }
                
                pulseRing2?.let {
                    val animation = android.view.animation.AnimationUtils.loadAnimation(
                        binding.root.context,
                        R.anim.live_pulse_ring_2
                    )
                    it.startAnimation(animation)
                }
                
                pulseRing3?.let {
                    val animation = android.view.animation.AnimationUtils.loadAnimation(
                        binding.root.context,
                        R.anim.live_pulse_ring_3
                    )
                    it.startAnimation(animation)
                }
                
                // Show match time (e.g., "00:01")
                eventTimeView?.text = "00:00" // You can implement actual match time tracking
                
            } else {
                // UPCOMING EVENT: Show start time, date, and countdown
                binding.liveIndicatorContainer.visibility = View.GONE
                eventDateView?.visibility = View.VISIBLE
                eventCountdownView?.visibility = View.VISIBLE
                
                // Parse and format start time
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = inputFormat.parse(channel.startTime)
                    
                    // Format time (e.g., "06:30 pm")
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    eventTimeView?.text = timeFormat.format(date)
                    
                    // Format date (e.g., "13/01/2026")
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    eventDateView?.text = dateFormat.format(date)
                    
                    // Calculate countdown
                    val currentTime = System.currentTimeMillis()
                    val startTime = date?.time ?: 0L
                    val diff = startTime - currentTime
                    
                    if (diff > 0) {
                        val hours = diff / (1000 * 60 * 60)
                        val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)
                        val seconds = (diff % (1000 * 60)) / 1000
                        eventCountdownView?.text = "Match Starting in $hours:${String.format("%02d", minutes)}:${String.format("%02d", seconds)}"
                    }
                    
                } catch (e: Exception) {
                    eventTimeView?.text = channel.startTime
                }
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
