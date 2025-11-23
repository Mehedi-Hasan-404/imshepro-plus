package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
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
            binding.tvTitle.text = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }
            binding.tvLeague.text = event.league
            binding.tvTeams.text = "${event.team1Name} • ${event.team2Name}"

            // Load team logos if available (fallback to placeholder)
            if (event.team1Logo.isNotEmpty()) {
                Glide.with(binding.imgTeam1).load(event.team1Logo).placeholder(R.drawable.ic_channel_placeholder).into(binding.imgTeam1)
            } else {
                binding.imgTeam1.setImageResource(R.drawable.ic_channel_placeholder)
            }
            if (event.team2Logo.isNotEmpty()) {
                Glide.with(binding.imgTeam2).load(event.team2Logo).placeholder(R.drawable.ic_channel_placeholder).into(binding.imgTeam2)
            } else {
                binding.imgTeam2.setImageResource(R.drawable.ic_channel_placeholder)
            }

            // start time formatting
            val formatted = try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                val date = fmt.parse(event.startTime)
                if (date != null) SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(date) else event.startTime
            } catch (e: Exception) { event.startTime }

            binding.tvStartTime.text = formatted

            // Status indicator
            binding.statusIndicator.setImageResource(
                when {
                    event.isLive -> R.drawable.ic_live_indicator
                    else -> R.drawable.ic_clock
                }
            )
        }
    }

    private class LiveEventDiffCallback : DiffUtil.ItemCallback<LiveEvent>() {
        override fun areItemsTheSame(oldItem: LiveEvent, newItem: LiveEvent) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LiveEvent, newItem: LiveEvent) = oldItem == newItem
    }
}
