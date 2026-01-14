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

    private val displayDateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    
    // Handler for countdown updates
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            notifyDataSetChanged()
            handler.postDelayed(this, 1000) // Update every second
        }
    }

    init {
        // Start countdown timer
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

        // 1. Set League Name and Category
        binding.leagueName.text = event.league ?: "Unknown League"
        binding.categoryTag.text = event.category ?: "Sports"
        
        if (event.category.equals("Cricket", ignoreCase = true)) {
             binding.categoryTag.setBackgroundResource(R.drawable.bg_category_pill)
        }

        // 2. Set Team Names
        binding.team1Name.text = event.team1Name
        binding.team2Name.text = event.team2Name
        
        // 3. Set Match Title
        if (event.title.contains("vs", ignoreCase = true)) {
             binding.matchTitle.text = "VS"
        } else {
             binding.matchTitle.text = event.title
        }

        // 4. Load Logos
        Glide.with(context).load(event.team1Logo).placeholder(R.drawable.ic_placeholder_team).into(binding.team1Logo)
        Glide.with(context).load(event.team2Logo).placeholder(R.drawable.ic_placeholder_team).into(binding.team2Logo)

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
                // LIVE EVENT - Show match timer
                event.isLive || (currentTime >= startTimeMillis && currentTime <= endTimeMillis) -> {
                    // SHOW LOTTIE ANIMATION
                    binding.liveAnimation.visibility = View.VISIBLE
                    binding.liveAnimation.playAnimation()
                    
                    // Calculate elapsed time since match started
                    val elapsedMillis = currentTime - startTimeMillis
                    val minutes = (elapsedMillis / 1000 / 60).toInt()
                    val seconds = ((elapsedMillis / 1000) % 60).toInt()
                    
                    // Show match timer (starts from 00:00)
                    binding.statusText.text = String.format("%02d:%02d", minutes, seconds)
                    binding.statusText.setTextColor(Color.RED)
                    binding.statusText.visibility = View.VISIBLE
                }
                
                // UPCOMING EVENT - Show countdown
                currentTime < startTimeMillis -> {
                    // HIDE LOTTIE
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    // Calculate time until match starts
                    val timeUntilStart = startTimeMillis - currentTime
                    
                    if (timeUntilStart > 0) {
                        val days = (timeUntilStart / (1000 * 60 * 60 * 24)).toInt()
                        val hours = ((timeUntilStart / (1000 * 60 * 60)) % 24).toInt()
                        val minutes = ((timeUntilStart / (1000 * 60)) % 60).toInt()
                        val seconds = ((timeUntilStart / 1000) % 60).toInt()
                        
                        // Format countdown display
                        val countdownText = when {
                            days > 0 -> String.format("Starts in %dd %02dh", days, hours)
                            hours > 0 -> String.format("Starts in %02d:%02d:%02d", hours, minutes, seconds)
                            else -> String.format("Starts in %02d:%02d", minutes, seconds)
                        }
                        
                        binding.statusText.text = countdownText
                        binding.statusText.setTextColor(Color.parseColor("#FFA500")) // Orange
                        binding.statusText.visibility = View.VISIBLE
                    } else {
                        binding.statusText.text = "Starting soon..."
                        binding.statusText.setTextColor(Color.LTGRAY)
                        binding.statusText.visibility = View.VISIBLE
                    }
                }
                
                // FINISHED EVENT
                else -> {
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    binding.statusText.text = "Ended"
                    binding.statusText.setTextColor(Color.GRAY)
                    binding.statusText.visibility = View.VISIBLE
                }
            }
            
        } catch (e: Exception) {
            // Fallback if date parsing fails
            binding.liveAnimation.visibility = View.GONE
            binding.liveAnimation.pauseAnimation()
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
