package com.livetvpro.ui.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.ItemLiveEventBinding
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.models.Channel
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.FloatingPlayerHelper
import com.livetvpro.utils.GlideExtensions
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiveEventAdapter(
    private val context: Context,
    private var events: List<LiveEvent>,
    private val preferencesManager: PreferencesManager,
    private val onEventClick: ((LiveEvent, Int) -> Unit)? = null
) : RecyclerView.Adapter<LiveEventAdapter.EventViewHolder>() {

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
        val symbols = dateFormatSymbols
        symbols.amPmStrings = arrayOf("AM", "PM")
        dateFormatSymbols = symbols
    }
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            notifyDataSetChanged()
            handler.postDelayed(this, 1000)
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

        binding.leagueName.text = event.league ?: "Unknown League"
        
        binding.categoryTag.text = event.category.ifEmpty { 
            event.eventCategoryName.ifEmpty { "Sports" }
        }
        
        if (event.wrapper.isNotEmpty()) {
            binding.wrapperBadge.text = event.wrapper
            binding.wrapperBadge.visibility = View.VISIBLE
        } else {
            binding.wrapperBadge.visibility = View.GONE
        }
        
        GlideExtensions.loadImage(
            binding.leagueLogo,
            event.leagueLogo,
            R.drawable.ic_channel_placeholder,
            R.drawable.ic_channel_placeholder,
            isCircular = false
        )

        binding.team1Name.text = event.team1Name
        binding.team2Name.text = event.team2Name

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

        try {
            val startDate = apiDateFormat.parse(event.startTime)
            val startTimeMillis = startDate?.time ?: 0L
            val currentTime = System.currentTimeMillis()
            
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
                (currentTime >= startTimeMillis && currentTime <= endTimeMillis) || event.isLive -> {
                    binding.liveAnimation.visibility = View.VISIBLE
                    binding.liveAnimation.playAnimation()
                    
                    binding.matchTime.visibility = View.GONE
                    binding.matchDate.visibility = View.GONE
                    
                    val elapsedMillis = currentTime - startTimeMillis
                    val hours = (elapsedMillis / 1000 / 3600).toInt()
                    val minutes = ((elapsedMillis / 1000 / 60) % 60).toInt()
                    val seconds = ((elapsedMillis / 1000) % 60).toInt()
                    
                    binding.statusText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    binding.statusText.setTextColor(Color.parseColor("#EF4444"))
                    binding.statusText.visibility = View.VISIBLE
                }
                
                currentTime < startTimeMillis -> {
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    if (startDate != null) {
                        binding.matchTime.text = timeFormat.format(startDate)
                        binding.matchTime.setTextColor(Color.parseColor("#10B981"))
                        binding.matchTime.visibility = View.VISIBLE
                        
                        binding.matchDate.text = dateFormat.format(startDate)
                        binding.matchDate.visibility = View.VISIBLE
                        
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
                
                else -> {
                    binding.liveAnimation.visibility = View.GONE
                    binding.liveAnimation.pauseAnimation()
                    
                    val endDate = if (endTimeMillis != Long.MAX_VALUE) {
                        apiDateFormat.parse(event.endTime)
                    } else {
                        startDate
                    }
                    
                    if (endDate != null) {
                        binding.matchTime.text = timeFormat.format(endDate)
                        binding.matchTime.setTextColor(Color.GRAY)
                        binding.matchTime.visibility = View.VISIBLE
                        
                        binding.matchDate.text = dateFormat.format(endDate)
                        binding.matchDate.visibility = View.VISIBLE
                    }
                    
                    binding.statusText.text = "Ended"
                    binding.statusText.setTextColor(Color.GRAY)
                    binding.statusText.visibility = View.VISIBLE
                }
            }
            
        } catch (e: Exception) {
            binding.liveAnimation.visibility = View.GONE
            binding.liveAnimation.pauseAnimation()
            binding.matchTime.visibility = View.GONE
            binding.matchDate.visibility = View.GONE
            binding.statusText.text = "Unknown"
            binding.statusText.setTextColor(Color.LTGRAY)
            binding.statusText.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener {
            launchPlayer(event)
        }
    }

    private fun launchPlayer(event: LiveEvent) {
        if (event.links.isEmpty()) {
            android.widget.Toast.makeText(
                context,
                "No streams available for this event",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        // If a custom onClick listener is provided (from PlayerActivity), use it
        if (onEventClick != null) {
            // For single link, call with index 0
            if (event.links.size == 1) {
                onEventClick.invoke(event, 0)
            } else {
                // Multiple links - show dialog
                showLinkSelectionDialogForSwitching(event)
            }
            return
        }
        
        // Default behavior for floating player/new activity
        if (event.links.size > 1) {
            showLinkSelectionDialog(event)
            return
        }
        
        proceedWithPlayer(event, 0)
    }
    
    private fun showLinkSelectionDialogForSwitching(event: LiveEvent) {
        val linkLabels = event.links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(context)
            .setTitle("Select Stream")
            .setItems(linkLabels) { dialog, which ->
                onEventClick?.invoke(event, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLinkSelectionDialog(event: LiveEvent) {
        val linkLabels = event.links.map { it.quality }.toTypedArray()
        
        // Check if this event already has a floating player
        val hasExistingPlayer = FloatingPlayerHelper.hasFloatingPlayerForEvent(event.id)
        
        val title = if (hasExistingPlayer) {
            "Switch Stream (Update Existing Player)"
        } else {
            "Select Stream"
        }
        
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(linkLabels) { dialog, which ->
                proceedWithPlayer(event, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun proceedWithPlayer(event: LiveEvent, linkIndex: Int) {
        val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
        val hasPermission = FloatingPlayerHelper.hasOverlayPermission(context)
        
        if (floatingEnabled) {
            if (!hasPermission) {
                android.widget.Toast.makeText(
                    context,
                    "Overlay permission required for floating player. Opening normally instead.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
                PlayerActivity.startWithEvent(context, event, linkIndex)
                return
            }
            
            try {
                // Create a channel object with ALL links from the event
                val channel = Channel(
                    id = event.id,
                    name = "${event.team1Name} vs ${event.team2Name}",
                    logoUrl = event.leagueLogo.ifEmpty { event.team1Logo },
                    categoryName = event.category,
                    groupTitle = "__EVENT__",  // Mark as event for FloatingPlayerActivity
                    links = event.links.map { liveEventLink ->
                        com.livetvpro.data.models.ChannelLink(
                            quality = liveEventLink.quality,
                            url = liveEventLink.url,
                            cookie = liveEventLink.cookie,
                            referer = liveEventLink.referer,
                            origin = liveEventLink.origin,
                            userAgent = liveEventLink.userAgent,
                            drmScheme = liveEventLink.drmScheme,
                            drmLicenseUrl = liveEventLink.drmLicenseUrl
                        )
                    }
                )
                
                // This will either create a new player or update existing one for this event
                FloatingPlayerHelper.launchFloatingPlayer(context, channel, linkIndex, event.id)
                
            } catch (e: Exception) {
                PlayerActivity.startWithEvent(context, event, linkIndex)
            }
        } else {
            PlayerActivity.startWithEvent(context, event, linkIndex)
        }
    }

    override fun getItemCount(): Int = events.size

    fun updateData(newEvents: List<LiveEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }
    
    fun stopCountdown() {
        handler.removeCallbacks(updateRunnable)
    }
}
