package com.livetvpro.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent

/**
 * LandscapePlayerActivity - DEPRECATED/SIMPLIFIED
 * 
 * NOTE: This activity is now mostly deprecated since PlayerActivity handles
 * both portrait and landscape orientations dynamically without activity switching.
 * 
 * This simplified version just redirects to PlayerActivity in landscape mode.
 * You can keep this for backward compatibility or remove it entirely.
 * 
 * RECOMMENDATION: Use PlayerActivity.startWithChannel() or PlayerActivity.startWithEvent()
 * directly instead of using this activity.
 */
class LandscapePlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_LINK_INDEX = "extra_link_index"

        /**
         * Start with channel - redirects to PlayerActivity
         */
        fun startWithChannel(
            context: Context,
            channel: Channel,
            linkIndex: Int = 0
        ) {
            // Just redirect to PlayerActivity which handles both orientations
            PlayerActivity.startWithChannel(context, channel, linkIndex)
        }

        /**
         * Start with event - redirects to PlayerActivity
         */
        fun startWithEvent(
            context: Context,
            event: LiveEvent,
            linkIndex: Int = 0
        ) {
            // Just redirect to PlayerActivity which handles both orientations
            PlayerActivity.startWithEvent(context, event, linkIndex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract intent data
        val channel = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }
        
        val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_EVENT)
        }
        
        val linkIndex = intent.getIntExtra(EXTRA_LINK_INDEX, 0)
        
        // Redirect to PlayerActivity which handles both orientations
        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            channel?.let { putExtra("extra_channel", it) }
            event?.let { putExtra("extra_event", it) }
            putExtra("extra_link_index", linkIndex)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        startActivity(playerIntent)
        finish()
    }
}
