package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ItemLiveEventBinding
import java.text.SimpleDateFormat
import java.util.*

class LiveEventAdapter(
    private val onEventClick: (LiveEvent) -> Unit
) : ListAdapter<LiveEvent, LiveEventAdapter.EventViewHolder>(LiveEventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemLiveEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventViewHolder(private val binding: ItemLiveEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEventClick(getItem(pos))
            }
        }

        fun bind(event: LiveEvent) {
            // 1. Set Text Data (using correct IDs from layout)
            binding.eventLeague.text = event.league
            binding.team1Name.text = event.team1Name
            binding.team2Name.text = event.team2Name

            // 2. Load Logos
            Glide.with(binding.team1Logo)
                .load(event.team1Logo)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(binding.team1Logo)

            Glide.with(binding.team2Logo)
                .load(event.team2Logo)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(binding.team2Logo)

            // 3. Handle Status (Live vs Upcoming)
            if (event.isLive) {
                // LIVE STATE
                binding.liveIndicatorContainer.visibility = View.VISIBLE
                binding.eventDate.visibility = View.GONE
                binding.eventCountdown.visibility = View.GONE
                binding.eventTime.text = "LIVE" 

                // Start Pulse Animation
                try {
                    val pulseAnim = AnimationUtils.loadAnimation(binding.root.context, R.anim.live_pulse_ring_1)
                    binding.livePulseBg.startAnimation(pulseAnim)
                } catch (e: Exception) {
                    // Ignore animation errors
                }
            } else {
                // UPCOMING STATE
                binding.liveIndicatorContainer.visibility = View.GONE
                binding.eventDate.visibility = View.VISIBLE
                binding.eventCountdown.visibility = View.VISIBLE
                binding.livePulseBg.clearAnimation()

                // Format Time
                try {
                    // Try parsing ISO format first
                    val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                    val date = inputFmt.parse(event.startTime)
                    
                    if (date != null) {
                        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
                        
                        binding.eventTime.text = timeFmt.format(date)
                        binding.eventDate.text = dateFmt.format(date)
                    } else {
                        binding.eventTime.text = event.startTime
                        binding.eventDate.text = ""
                    }
                } catch (e: Exception) {
                    binding.eventTime.text = event.startTime
                    binding.eventDate.text = ""
                }
            }
        }
    }

    private class LiveEventDiffCallback : DiffUtil.ItemCallback<LiveEvent>() {
        override fun areItemsTheSame(oldItem: LiveEvent, newItem: LiveEvent) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LiveEvent, newItem: LiveEvent) = oldItem == newItem
    }
}
