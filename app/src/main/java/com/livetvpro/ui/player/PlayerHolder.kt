package com.livetvpro.ui.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * Singleton to hold player instance during transitions
 * Prevents player destruction and recreation between Activity <-> Service
 * This enables seamless transitions without loading/buffering
 */
object PlayerHolder {
    var player: ExoPlayer? = null
    var streamUrl: String? = null
    var channelName: String? = null
    
    /**
     * Transfer player from one component to another
     * Call this before closing the source component
     */
    fun transferPlayer(exoPlayer: ExoPlayer, url: String, name: String) {
        player = exoPlayer
        streamUrl = url
        channelName = name
        android.util.Log.d("PlayerHolder", "Player transferred - no recreation needed!")
    }
    
    /**
     * Retrieve the transferred player
     * Call this in the destination component
     */
    fun retrievePlayer(): Triple<ExoPlayer?, String?, String?> {
        val result = Triple(player, streamUrl, channelName)
        return result
    }
    
    /**
     * Clear the player holder
     * Only call when component takes ownership
     */
    fun clearReferences() {
        player = null
        streamUrl = null
        channelName = null
    }
    
    /**
     * Release and clear everything
     * Only call when completely stopping playback
     */
    fun releaseAndClear() {
        player?.release()
        player = null
        streamUrl = null
        channelName = null
    }
}
