package com.livetvpro.ui.adapters // Adjust package name as needed

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.databinding.ItemLiveEventBinding
import com.livetvpro.data.models.LiveEvent // FIXED: Correct import path
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiveEventAdapter(
    private val context: Context,
    private var events: List<LiveEvent>,
    private val onEventClick: (LiveEvent) -> Unit
) : RecyclerView.Adapter<LiveEventAdapter.EventViewHolder>() {

    // Date formatter for parsing API dates (ISO 8601)
    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Date formatter for display (e.g., "12 Jan, 10:00 PM")
    private val displayDateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    inner class EventViewHolder(val binding: ItemLiveEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemLiveEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        val binding = holder.binding

        // 1. Set League Name and Category
        binding.leagueName.text = event.league ?: "Unknown League"
        binding.categoryTag.text = event.category ?: "Sports"
        
        // Dynamic Category Color (Optional example)
        if (event.category.equals("Cricket", ignoreCase = true)) {
             binding.categoryTag.setBackgroundResource(R.drawable.bg_category_pill) // Define blue/red etc
        }

        // 2. Set Team Names
        binding.team1Name.text = event.team1Name
        binding.team2Name.text = event.team2Name
        
        // 3. Set Match Title (VS or specific title)
        // If the title is just "Team A vs Team B", we might prefer just "VS" in the center, 
        // but if the title is special like "Final", we show it.
        if (event.title.contains("vs", ignoreCase = true)) {
             binding.matchTitle.text = "VS"
        } else {
             binding.matchTitle.text = event.title
        }

        // 4. Load Logos - FIXED: Use correct placeholder
        Glide.with(context).load(event.team1Logo).placeholder(R.drawable.ic_placeholder_team).into(binding.team1Logo)
        Glide.with(context).load(event.team2Logo).placeholder(R.drawable.ic_placeholder_team).into(binding.team2Logo)

        // 5. Logic: LIVE vs UPCOMING
        if (event.isLive) {
            // SHOW LOTTIE
            binding.liveAnimation.visibility = View.VISIBLE
            binding.liveAnimation.playAnimation()
            
            // Hide Time / Show 'LIVE' text if preferred, or hide status text entirely
            binding.statusText.text = "LIVE"
            binding.statusText.setTextColor(Color.RED)
            binding.statusText.visibility = View.VISIBLE
        } else {
            // HIDE LOTTIE
            binding.liveAnimation.visibility = View.GONE
            binding.liveAnimation.pauseAnimation()

            // CALCULATE TIME
            try {
                val startDate = apiDateFormat.parse(event.startTime)
                val serverDate = if (event.serverTimeUTC != null) apiDateFormat.parse(event.serverTimeUTC) else null
                
                if (startDate != null) {
                    // Display the formatted start time
                    binding.statusText.text = displayDateFormat.format(startDate)
                    binding.statusText.setTextColor(Color.LTGRAY)
                    binding.statusText.visibility = View.VISIBLE
                    
                    // Optional: If you want a countdown like "Starts in 2h"
                    /*
                    if (serverDate != null) {
                        val diff = startDate.time - serverDate.time
                        if (diff > 0) {
                            val hours = diff / (1000 * 60 * 60)
                            binding.statusText.text = "Starts in ${hours}h"
                        }
                    }
                    */
                } else {
                    binding.statusText.text = "Upcoming"
                }
            } catch (e: Exception) {
                binding.statusText.text = "Upcoming"
            }
        }

        // Click Listener
        holder.itemView.setOnClickListener {
            onEventClick(event)
        }
    }

    override fun getItemCount(): Int = events.size

    fun updateData(newEvents: List<LiveEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }
}
