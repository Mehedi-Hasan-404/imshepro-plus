package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.ui.player.FloatingPlayerService
import java.util.UUID

object FloatingPlayerHelper {
    
    private val createdInstances = mutableListOf<String>()
    
    // Map to track which event has which floating player instance
    private val eventToInstanceMap = mutableMapOf<String, String>()
    
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Please enable overlay permission in settings", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Launch floating player for a channel/event with specific link
     * If the event already has a floating player, update it with the new link instead of creating new one
     */
    fun launchFloatingPlayer(context: Context, channel: Channel, linkIndex: Int = 0, eventId: String? = null) {
        if (!hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission required for floating player", Toast.LENGTH_LONG).show()
            return
        }
        
        if (channel.links.isNullOrEmpty()) {
            Toast.makeText(context, "No stream available for ${channel.name}", Toast.LENGTH_SHORT).show()
            return
        }
        
        val actualEventId = eventId ?: channel.id
        
        // Check if this event already has a floating player
        val existingInstanceId = eventToInstanceMap[actualEventId]
        
        if (existingInstanceId != null && FloatingPlayerManager.hasPlayer(existingInstanceId)) {
            // Update the existing floating player with the new link
            updateFloatingPlayer(context, existingInstanceId, channel, linkIndex)
            Toast.makeText(context, "Updated floating player with new stream", Toast.LENGTH_SHORT).show()
        } else {
            // Create new floating player
            createNewFloatingPlayer(context, channel = channel, linkIndex = linkIndex, eventId = actualEventId)
        }
    }
    
    /**
     * Launch floating player for an event (keeps original event data)
     * This version passes both the converted channel AND the original event to preserve event context
     */
    fun launchFloatingPlayerWithEvent(context: Context, channel: Channel, event: LiveEvent, linkIndex: Int = 0) {
        if (!hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission required for floating player", Toast.LENGTH_LONG).show()
            return
        }
        
        if (channel.links.isNullOrEmpty()) {
            Toast.makeText(context, "No stream available for ${channel.name}", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check if this event already has a floating player
        val existingInstanceId = eventToInstanceMap[event.id]
        
        if (existingInstanceId != null && FloatingPlayerManager.hasPlayer(existingInstanceId)) {
            // Update the existing floating player with the new link
            updateFloatingPlayer(context, existingInstanceId, channel, linkIndex)
            Toast.makeText(context, "Updated floating player with new stream", Toast.LENGTH_SHORT).show()
        } else {
            // Create new floating player with both channel and event
            createNewFloatingPlayer(context, channel = channel, event = event, linkIndex = linkIndex, eventId = event.id)
        }
    }
    
    /**
     * Update an existing floating player with a new stream/link
     */
    private fun updateFloatingPlayer(context: Context, instanceId: String, channel: Channel, linkIndex: Int) {
        try {
            FloatingPlayerService.updateFloatingPlayer(context, instanceId, channel, linkIndex)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to update player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun createNewFloatingPlayer(
        context: Context,
        channel: Channel? = null,
        event: LiveEvent? = null,
        linkIndex: Int = 0,
        eventId: String? = null
    ): String? {
        if (!FloatingPlayerManager.canAddNewPlayer()) {
            val message = "Maximum ${FloatingPlayerManager.getMaxPlayerCount()} floating players active"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return null
        }
        
        if (!hasOverlayPermission(context)) {
            requestOverlayPermission(context)
            Toast.makeText(context, "Please grant overlay permission", Toast.LENGTH_LONG).show()
            return null
        }
        
        if (channel == null && event == null) {
            Toast.makeText(context, "No content to play", Toast.LENGTH_SHORT).show()
            return null
        }
        
        val instanceId = UUID.randomUUID().toString()
        val contentName = channel?.name ?: event?.title ?: "Unknown"
        val contentType = if (channel != null) "channel" else "event"
        val actualEventId = eventId ?: channel?.id ?: event?.id ?: ""
        
        FloatingPlayerManager.addPlayer(instanceId, contentName, contentType)
        
        // Map this event to this instance
        if (actualEventId.isNotEmpty()) {
            eventToInstanceMap[actualEventId] = instanceId
        }
        
        return try {
            val started = FloatingPlayerService.startFloatingPlayer(context, instanceId, channel, event, linkIndex)
            
            if (started) {
                createdInstances.add(instanceId)
                val count = FloatingPlayerManager.getActivePlayerCount()
                val message = "Floating player $count/${FloatingPlayerManager.getMaxPlayerCount()}"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                instanceId
            } else {
                FloatingPlayerManager.removePlayer(instanceId)
                eventToInstanceMap.remove(actualEventId)
                Toast.makeText(context, "Failed to start floating player", Toast.LENGTH_SHORT).show()
                null
            }
        } catch (e: Exception) {
            FloatingPlayerManager.removePlayer(instanceId)
            eventToInstanceMap.remove(actualEventId)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }
    
    fun closeFloatingPlayer(context: Context, instanceId: String) {
        if (!FloatingPlayerManager.hasPlayer(instanceId)) return
        
        // Remove from event mapping
        val eventId = eventToInstanceMap.entries.find { it.value == instanceId }?.key
        if (eventId != null) {
            eventToInstanceMap.remove(eventId)
        }
        
        FloatingPlayerService.stopFloatingPlayer(context, instanceId)
        FloatingPlayerManager.removePlayer(instanceId)
        createdInstances.remove(instanceId)
    }
    
    fun closeAllFloatingPlayers(context: Context) {
        val count = FloatingPlayerManager.getActivePlayerCount()
        if (count == 0) return
        
        val instanceIds = FloatingPlayerManager.getAllPlayerIds()
        instanceIds.forEach { instanceId ->
            FloatingPlayerService.stopFloatingPlayer(context, instanceId)
        }
        
        FloatingPlayerManager.clearAll()
        createdInstances.clear()
        eventToInstanceMap.clear()
        
        Toast.makeText(context, "Closed $count floating player(s)", Toast.LENGTH_SHORT).show()
    }
    
    fun hasFloatingPlayers(): Boolean {
        return FloatingPlayerManager.hasAnyPlayers()
    }
    
    fun getFloatingPlayerCount(): Int {
        return FloatingPlayerManager.getActivePlayerCount()
    }
    
    fun canCreateMore(): Boolean {
        return FloatingPlayerManager.canAddNewPlayer()
    }
    
    fun getActivePlayers(): List<FloatingPlayerManager.PlayerMetadata> {
        return FloatingPlayerManager.getAllPlayerMetadata()
    }
    
    /**
     * Check if a specific event already has a floating player
     */
    fun hasFloatingPlayerForEvent(eventId: String): Boolean {
        val instanceId = eventToInstanceMap[eventId]
        return instanceId != null && FloatingPlayerManager.hasPlayer(instanceId)
    }
    
    /**
     * Get the instance ID for a specific event's floating player
     */
    fun getInstanceIdForEvent(eventId: String): String? {
        return eventToInstanceMap[eventId]
    }
}
