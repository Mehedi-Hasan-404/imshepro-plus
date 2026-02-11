package com.livetvpro.utils

object FloatingPlayerManager {
    
    private const val MAX_FLOATING_PLAYERS = 5
    private val activeFloatingPlayers = mutableMapOf<String, PlayerMetadata>()
    
    data class PlayerMetadata(
        val instanceId: String,
        val contentName: String,
        val contentType: String,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    fun canAddNewPlayer(): Boolean {
        return activeFloatingPlayers.size < MAX_FLOATING_PLAYERS
    }
    
    fun getActivePlayerCount(): Int {
        return activeFloatingPlayers.size
    }
    
    fun getMaxPlayerCount(): Int {
        return MAX_FLOATING_PLAYERS
    }
    
    fun addPlayer(instanceId: String, contentName: String, contentType: String) {
        if (activeFloatingPlayers.size >= MAX_FLOATING_PLAYERS) return
        
        val metadata = PlayerMetadata(instanceId, contentName, contentType)
        activeFloatingPlayers[instanceId] = metadata
    }
    
    fun removePlayer(instanceId: String) {
        activeFloatingPlayers.remove(instanceId)
    }
    
    fun getAllPlayerIds(): List<String> {
        return activeFloatingPlayers.keys.toList()
    }
    
    fun getPlayerMetadata(instanceId: String): PlayerMetadata? {
        return activeFloatingPlayers[instanceId]
    }
    
    fun getAllPlayerMetadata(): List<PlayerMetadata> {
        return activeFloatingPlayers.values.toList()
    }
    
    fun hasPlayer(instanceId: String): Boolean {
        return activeFloatingPlayers.containsKey(instanceId)
    }
    
    fun hasAnyPlayers(): Boolean {
        return activeFloatingPlayers.isNotEmpty()
    }
    
    fun clearAll() {
        activeFloatingPlayers.clear()
    }
}
