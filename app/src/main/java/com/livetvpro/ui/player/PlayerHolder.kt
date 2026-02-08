package com.livetvpro.ui.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * Singleton to hold player instance during transitions
 * Prevents player destruction and recreation between Activity <-> Service
 */
object PlayerHolder {
    var player: ExoPlayer? = null
    var streamUrl: String? = null
    var channelName: String? = null
    
    fun transferPlayer(exoPlayer: ExoPlayer, url: String, name: String) {
        player = exoPlayer
        streamUrl = url
        channelName = name
        android.util.Log.d("PlayerHolder", "Player transferred - no recreation needed!")
    }
    
    fun retrievePlayer(): Triple<ExoPlayer?, String?, String?> {
        val result = Triple(player, streamUrl, channelName)
        // Don't clear yet - service will take ownership
        return result
    }
    
    fun clear() {
        player?.release()
        player = null
        streamUrl = null
        channelName = null
    }
}
