package com.livetvpro.ui.adapters

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.databinding.ItemLiveEventBinding
import com.livetvpro.data.models.LiveEvent
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiveEventAdapter(
    private val context: Context,
    private var events: List<LiveEvent>,
    private val onEventClick: (LiveEvent) -> Unit
) : RecyclerView.Adapter<LiveEventAdapter.EventViewHolder>() {

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Local time formats (user's timezone)
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
    
    // Handler for countdown updates
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            notifyDataSetChanged()
            handler.postDelayed(this, 1000) // Update every second
        }
    }

    init {
        handler.post(updateRunnable)
    }

    inner class EventViewHolder(val binding: ItemLiveEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemLiveEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        val binding = holder.binding

        // 1. Set League Name
        binding.leagueName.text = event.league ?: "Unknown League"
        
        // 2. Set Category Tag
        binding.categoryTag.text = event.category ?: "Sports"

        // 3. Set Team Names
        binding.team1Name.text = event.team1Name
        binding.team2Name.text = event.team2Name

        // 4. Load Team Logos with CIRCULAR CROP
        Glide.with(context)
            .load(event.team1Logo)
            .placeholder(R.drawable.ic_placeholder_team)
            .circleCrop()
            .into(binding.team1Logo)

        Glide.with(context)
            .load(event.team2Logo)
            .placeholder(R.drawable.ic_placeholder_team)
            .circleCrop()
            .into(binding.team2Logo)

        // 5. Logic: LIVE vs UPCOMING with real-time countdown
        try {
            val startDate = apiDateFormat.parse(event.startTime)
            val startTimeMillis = startDate?.time ?: 0L
            val currentTime = System.currentTimeMillis()
            
            // Parse end time if available
            val endTimeMillis = if (!event.endTime.isNullOrEmpty()) {
                try {
                    apiDateFormat.parse(event.endTime)?.time ?: Long.MAX_VALUE
                } catch (e: Exception) {
                    Long.MAX_VALUE
                }
            } else {
                Long.MAX_VALUE
            }
            
            when {
                // LIVE EVENT - Between start and end time
                currentTime >= startTimeMillis && currentTime <= endTimeMillis -> {
                    // SHOW LARGER LOTTIE ANIMATION
                    binding.liveAnimation.visibility = View.VISIBLE
                    binding.liveAnimation.playAnimation()
                    
                    // HIDE TIME AND DATE (show only lottie)
                    binding.matchTime.visibility = View.GONE
                    binding.matchDate.visibility = View.GONE
                    
                    // Calculate elapsed time since match started (hh:mm:ss)
                    val elapsedMillis = currentTime - startTimeMillis
                    val hours = (elapsedMillis / 1000 / 3600).toInt()
                    val minutes = ((elapsedMillis / 1000 / 60) % 60).toInt()
                    val seconds = ((elapsedMillis / 1000) % 60).toInt()
                    
                    // Show match timer in hh:mm:ss format
                    binding.statusText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    binding.statusText.setTextColor(Color.parseColor("#EF4444"))
                    binding.statusText.visibility = View.VISIBLE
                }
                
                // UPCOMING EVENT - Show start time and date
                currentTime < startTimeMillis -> {
                    // HIDE LOTTIE
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    // HIDE STATUS TEXT
                    binding.statusText.visibility = View.GONE
                    
                    // SHOW START TIME in user's local timezone
                    if (startDate != null) {
                        binding.matchTime.text = timeFormat.format(startDate)
                        binding.matchTime.setTextColor(Color.parseColor("#10B981"))
                        binding.matchTime.visibility = View.VISIBLE
                        
                        // SHOW DATE below time
                        binding.matchDate.text = dateFormat.format(startDate)
                        binding.matchDate.visibility = View.VISIBLE
                    }
                }
                
                // FINISHED EVENT
                else -> {
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    binding.matchTime.visibility = View.GONE
                    binding.matchDate.visibility = View.GONE
                    
                    binding.statusText.text = "Ended"
                    binding.statusText.setTextColor(Color.GRAY)
                    binding.statusText.visibility = View.VISIBLE
                }
            }
            
        } catch (e: Exception) {
            // Fallback if date parsing fails
            binding.liveAnimation.visibility = View.GONE
            binding.liveAnimation.pauseAnimation()
            binding.matchTime.visibility = View.GONE
            binding.matchDate.visibility = View.GONE
            binding.statusText.text = "Unknown"
            binding.statusText.setTextColor(Color.LTGRAY)
            binding.statusText.visibility = View.VISIBLE
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
    
    // Clean up handler when adapter is destroyed
    fun stopCountdown() {
        handler.removeCallbacks(updateRunnable)
    }
}
