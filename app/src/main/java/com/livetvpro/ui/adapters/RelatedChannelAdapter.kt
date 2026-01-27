package com.livetvpro.ui.adapters

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemChannelBinding
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
                val binding = ItemChannelBinding.inflate(
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
            val teamNames = channel.name.split(" vs ", ignoreCase = true)
            val team1Name = teamNames.getOrNull(0)?.trim() ?: ""
            val team2Name = teamNames.getOrNull(1)?.trim() ?: ""
            
            val team1NameView = binding.root.findViewById<android.widget.TextView>(R.id.team1_name)
            val team2NameView = binding.root.findViewById<android.widget.TextView>(R.id.team2_name)
            team1NameView?.text = team1Name
            team2NameView?.text = team2Name
            
            binding.eventLeague.text = channel.categoryName
            
            val categoryIconView = binding.root.findViewById<android.widget.ImageView>(R.id.category_icon)
            categoryIconView?.let {
                Glide.with(it)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(it)
            }
            
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
            
            val eventTimeView = binding.root.findViewById<android.widget.TextView>(R.id.event_time)
            val eventDateView = binding.root.findViewById<android.widget.TextView>(R.id.event_date)
            val eventCountdownView = binding.root.findViewById<android.widget.TextView>(R.id.event_countdown)
            
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
                binding.liveIndicatorContainer.visibility = View.VISIBLE
                eventDateView?.visibility = View.GONE
                eventCountdownView?.visibility = View.GONE
                
                val pulseBg = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_bg)
                val pulseRing2 = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_ring_2)
                val pulseRing3 = binding.root.findViewById<android.widget.ImageView>(R.id.live_pulse_ring_3)
                
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
                
                eventTimeView?.text = "00:00"
                
            } else {
                binding.liveIndicatorContainer.visibility = View.GONE
                eventDateView?.visibility = View.VISIBLE
                eventCountdownView?.visibility = View.VISIBLE
                
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = inputFormat.parse(channel.startTime)
                    
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    eventTimeView?.text = timeFormat.format(date)
                    
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    eventDateView?.text = dateFormat.format(date)
                    
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

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
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
            
            // Enable marquee scroll - CRITICAL: Must be done in this exact order
            binding.channelName.post {
                binding.channelName.isSelected = true
            }

            // Apply circular clipping to logo container
            binding.logoContainer.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            binding.logoContainer.clipToOutline = true

            // Load logo with proper clipping
            Glide.with(binding.channelLogo)
                .load(channel.logoUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .fitCenter()
                .into(binding.channelLogo)

            // Set card stroke with reduced width
            val cardView = binding.root as MaterialCardView
            cardView.strokeColor = ContextCompat.getColor(binding.root.context, R.color.player_channel_stroke)
            cardView.strokeWidth = binding.root.resources.getDimensionPixelSize(R.dimen.player_channel_stroke_width)
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
