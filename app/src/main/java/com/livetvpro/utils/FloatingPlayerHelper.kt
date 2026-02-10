package com.livetvpro.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.ui.player.FloatingPlayerService

object FloatingPlayerHelper {

    /**
     * Check if the app has overlay permission
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Permission not required on Android < M
        }
    }

    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, requestCode)
        }
    }

    /**
     * Launch floating player with a Channel
     * Shows link selection dialog if multiple links are available
     */
    fun launchFloatingPlayer(context: Context, channel: Channel) {
        android.util.Log.d("FloatingPlayerHelper", "launchFloatingPlayer called for channel: ${channel.name}")
        android.util.Log.d("FloatingPlayerHelper", "Channel has ${channel.links?.size ?: 0} links")
        
        // Validate that we have links
        if (channel.links.isNullOrEmpty()) {
            android.widget.Toast.makeText(
                context,
                "No streams available",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        // If only one link, launch directly
        if (channel.links.size == 1) {
            android.util.Log.d("FloatingPlayerHelper", "Only 1 link - launching directly")
            launchFloatingPlayerWithLink(context, channel, linkIndex = 0)
            return
        }

        // ============================================
        // FIX: Show link selection dialog for multiple links
        // ============================================
        android.util.Log.d("FloatingPlayerHelper", "Multiple links (${channel.links.size}) - showing dialog")
        showLinkSelectionDialog(context, channel)
    }

    /**
     * Launch floating player with a LiveEvent
     * Shows link selection dialog if multiple links are available
     */
    fun launchFloatingPlayer(context: Context, event: LiveEvent) {
        android.util.Log.d("FloatingPlayerHelper", "launchFloatingPlayer called for event: ${event.team1Name} vs ${event.team2Name}")
        android.util.Log.d("FloatingPlayerHelper", "Event has ${event.links.size} links")
        
        // Convert LiveEvent to Channel
        val channel = Channel(
            id = event.id,
            name = "${event.team1Name} vs ${event.team2Name}",
            logoUrl = event.leagueLogo.ifEmpty { event.team1Logo },
            categoryName = event.category,
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
        
        launchFloatingPlayer(context, channel)
    }

    /**
     * Show dialog to select which link to use
     */
    private fun showLinkSelectionDialog(context: Context, channel: Channel) {
        if (context !is FragmentActivity) {
            android.util.Log.e("FloatingPlayerHelper", "Context is not a FragmentActivity, cannot show dialog")
            // Fallback to first link
            launchFloatingPlayerWithLink(context, channel, linkIndex = 0)
            return
        }

        val links = channel.links ?: return
        
        // Create quality labels for the dialog
        val qualityLabels = links.mapIndexed { index, link ->
            val quality = link.quality.ifEmpty { "Link ${index + 1}" }
            quality
        }.toTypedArray()

        android.util.Log.d("FloatingPlayerHelper", "Showing dialog with ${qualityLabels.size} options: ${qualityLabels.joinToString()}")

        AlertDialog.Builder(context)
            .setTitle("Select Stream Quality")
            .setItems(qualityLabels) { dialog, which ->
                android.util.Log.d("FloatingPlayerHelper", "User selected link $which: ${qualityLabels[which]}")
                launchFloatingPlayerWithLink(context, channel, linkIndex = which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                android.util.Log.d("FloatingPlayerHelper", "User cancelled link selection")
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Launch floating player with a specific link index
     */
    private fun launchFloatingPlayerWithLink(context: Context, channel: Channel, linkIndex: Int) {
        android.util.Log.d("FloatingPlayerHelper", "Launching floating player with link index: $linkIndex")
        
        if (channel.links.isNullOrEmpty()) {
            android.util.Log.e("FloatingPlayerHelper", "No links available")
            return
        }

        if (linkIndex < 0 || linkIndex >= channel.links.size) {
            android.util.Log.e("FloatingPlayerHelper", "Invalid link index: $linkIndex (available: 0-${channel.links.size - 1})")
            return
        }

        val selectedLink = channel.links[linkIndex]
        android.util.Log.d("FloatingPlayerHelper", "Selected link: ${selectedLink.quality} - ${selectedLink.url}")

        // Create a new channel with only the selected link
        val singleLinkChannel = channel.copy(
            links = listOf(selectedLink)
        )

        // Launch the floating player service
        FloatingPlayerService.start(
            context = context,
            channel = singleLinkChannel,
            playbackPosition = 0L
        )
        
        android.util.Log.d("FloatingPlayerHelper", "FloatingPlayerService.start() called successfully")
    }
}
