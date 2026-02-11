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
    
    fun launchFloatingPlayer(context: Context, channel: Channel, linkIndex: Int = 0) {
        if (!hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission required for floating player", Toast.LENGTH_LONG).show()
            return
        }
        
        if (channel.links.isNullOrEmpty()) {
            Toast.makeText(context, "No stream available for ${channel.name}", Toast.LENGTH_SHORT).show()
            return
        }
        
        createNewFloatingPlayer(context, channel = channel)
    }
    
    fun createNewFloatingPlayer(
        context: Context,
        channel: Channel? = null,
        event: LiveEvent? = null
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
        
        FloatingPlayerManager.addPlayer(instanceId, contentName, contentType)
        
        return try {
            val started = FloatingPlayerService.startFloatingPlayer(context, instanceId, channel, event)
            
            if (started) {
                createdInstances.add(instanceId)
                val count = FloatingPlayerManager.getActivePlayerCount()
                val message = "Floating player $count/${FloatingPlayerManager.getMaxPlayerCount()}"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                instanceId
            } else {
                FloatingPlayerManager.removePlayer(instanceId)
                Toast.makeText(context, "Failed to start floating player", Toast.LENGTH_SHORT).show()
                null
            }
        } catch (e: Exception) {
            FloatingPlayerManager.removePlayer(instanceId)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }
    
    fun closeFloatingPlayer(context: Context, instanceId: String) {
        if (!FloatingPlayerManager.hasPlayer(instanceId)) return
        
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
}
