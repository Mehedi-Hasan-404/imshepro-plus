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
import com.livetvpro.utils.GlideExtensions
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
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
        val symbols = dateFormatSymbols
        symbols.amPmStrings = arrayOf("AM", "PM")
        dateFormatSymbols = symbols
    }
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) // Full date with year
    
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

        // 1. Set League Name with bold font
        binding.leagueName.text = event.league ?: "Unknown League"
        
        // 2. Set Category Tag (use eventCategoryName if category is empty)
        binding.categoryTag.text = event.category.ifEmpty { 
            event.eventCategoryName.ifEmpty { "Sports" }
        }
        
        // 3. Set Wrapper Badge if present
        if (event.wrapper.isNotEmpty()) {
            binding.wrapperBadge.text = event.wrapper
            binding.wrapperBadge.visibility = View.VISIBLE
        } else {
            binding.wrapperBadge.visibility = View.GONE
        }
        
        // 4. Load League Logo (from leagueLogo URL) with SVG support
        GlideExtensions.loadImage(
            binding.leagueLogo,
            event.leagueLogo,
            R.drawable.ic_channel_placeholder,
            R.drawable.ic_channel_placeholder,
            isCircular = false
        )

        // 5. Set Team Names with bold font
        binding.team1Name.text = event.team1Name
        binding.team2Name.text = event.team2Name

        // 6. Load Team Logos with CIRCULAR CROP (SVG support)
        GlideExtensions.loadImage(
            binding.team1Logo,
            event.team1Logo,
            R.drawable.ic_placeholder_team,
            R.drawable.ic_placeholder_team,
            isCircular = true
        )

        GlideExtensions.loadImage(
            binding.team2Logo,
            event.team2Logo,
            R.drawable.ic_placeholder_team,
            R.drawable.ic_placeholder_team,
            isCircular = true
        )

        // 7. Logic: LIVE vs UPCOMING vs ENDED
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
                // ===== LIVE EVENT =====
                // Show as LIVE if: (time is between start-end) OR isLive flag is true
                (currentTime >= startTimeMillis && currentTime <= endTimeMillis) || event.isLive -> {
                    // Show LARGER Lottie animation
                    binding.liveAnimation.visibility = View.VISIBLE
                    binding.liveAnimation.playAnimation()
                    
                    // Hide time and date
                    binding.matchTime.visibility = View.GONE
                    binding.matchDate.visibility = View.GONE
                    
                    // Show elapsed time (hh:mm:ss)
                    val elapsedMillis = currentTime - startTimeMillis
                    val hours = (elapsedMillis / 1000 / 3600).toInt()
                    val minutes = ((elapsedMillis / 1000 / 60) % 60).toInt()
                    val seconds = ((elapsedMillis / 1000) % 60).toInt()
                    
                    binding.statusText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    binding.statusText.setTextColor(Color.parseColor("#EF4444"))
                    binding.statusText.visibility = View.VISIBLE
                }
                
                // ===== UPCOMING EVENT =====
                currentTime < startTimeMillis -> {
                    // Hide Lottie
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    // Show START TIME in local timezone (12:30 PM format)
                    if (startDate != null) {
                        binding.matchTime.text = timeFormat.format(startDate)
                        binding.matchTime.setTextColor(Color.parseColor("#10B981"))
                        binding.matchTime.visibility = View.VISIBLE
                        
                        // Show DATE (Wed, 15 Jan)
                        binding.matchDate.text = dateFormat.format(startDate)
                        binding.matchDate.visibility = View.VISIBLE
                        
                        // Show LIVE countdown timer (updates every second)
                        val diff = startTimeMillis - currentTime
                        val days = (diff / (1000 * 60 * 60 * 24)).toInt()
                        val hours = ((diff / (1000 * 60 * 60)) % 24).toInt()
                        val minutes = ((diff / (1000 * 60)) % 60).toInt()
                        val seconds = ((diff / 1000) % 60).toInt()
                        
                        binding.statusText.text = when {
                            days > 0 -> String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
                            hours > 0 -> String.format("%02dh %02dm %02ds", hours, minutes, seconds)
                            minutes > 0 -> String.format("%02dm %02ds", minutes, seconds)
                            else -> String.format("%02ds", seconds)
                        }
                        binding.statusText.setTextColor(Color.parseColor("#10B981"))
                        binding.statusText.visibility = View.VISIBLE
                    }
                }
                
                // ===== ENDED EVENT =====
                else -> {
                    // Hide Lottie
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    // Show END TIME
                    val endDate = if (endTimeMillis != Long.MAX_VALUE) {
                        apiDateFormat.parse(event.endTime)
                    } else {
                        startDate
                    }
                    
                    if (endDate != null) {
                        binding.matchTime.text = timeFormat.format(endDate)
                        binding.matchTime.setTextColor(Color.GRAY)
                        binding.matchTime.visibility = View.VISIBLE
                        
                        // Show END DATE
                        binding.matchDate.text = dateFormat.format(endDate)
                        binding.matchDate.visibility = View.VISIBLE
                    }
                    
                    // Show "Ended"
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
