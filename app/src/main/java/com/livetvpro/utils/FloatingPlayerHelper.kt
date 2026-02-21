package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.ui.player.FloatingPlayerActivity
import com.livetvpro.ui.player.FloatingPlayerService
import com.livetvpro.utils.DeviceUtils
import java.util.UUID

object FloatingPlayerHelper {

    private val createdInstances = mutableListOf<String>()
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

            }
        }
    }

    fun launchFloatingPlayer(context: Context, channel: Channel, linkIndex: Int = 0, eventId: String? = null) {
        // On TV there is no overlay permission / window — launch FloatingPlayerActivity directly
        if (DeviceUtils.isTvDevice) {
            FloatingPlayerActivity.startWithChannel(context, channel, linkIndex)
            return
        }

        if (!hasOverlayPermission(context)) return

        val resolvedChannel = if (channel.links.isNullOrEmpty()) {
            if (channel.streamUrl.isBlank()) return
            channel.copy(links = listOf(parseLinkFromStreamUrl(channel.streamUrl)))
        } else {
            channel
        }

        val actualEventId = eventId ?: resolvedChannel.id
        val existingInstanceId = eventToInstanceMap[actualEventId]

        if (existingInstanceId != null && FloatingPlayerManager.hasPlayer(existingInstanceId)) {
            updateFloatingPlayer(context, existingInstanceId, resolvedChannel, linkIndex)
        } else {
            createNewFloatingPlayer(context, channel = resolvedChannel, linkIndex = linkIndex, eventId = actualEventId)
        }
    }

    fun launchFloatingPlayerWithEvent(context: Context, channel: Channel, event: LiveEvent, linkIndex: Int = 0) {
        if (!hasOverlayPermission(context)) return
        // On TV launch FloatingPlayerActivity directly
        if (DeviceUtils.isTvDevice) {
            FloatingPlayerActivity.startWithChannel(context, channel, linkIndex)
            return
        }

        if (channel.links.isNullOrEmpty()) return

        val existingInstanceId = eventToInstanceMap[event.id]

        if (existingInstanceId != null && FloatingPlayerManager.hasPlayer(existingInstanceId)) {
            updateFloatingPlayer(context, existingInstanceId, channel, linkIndex)
        } else {
            createNewFloatingPlayer(context, channel = channel, event = event, linkIndex = linkIndex, eventId = event.id)
        }
    }

    fun launchFloatingPlayerWithNetworkStream(
        context: Context,
        streamUrl: String,
        cookie: String = "",
        referer: String = "",
        origin: String = "",
        drmLicense: String = "",
        userAgent: String = "Default",
        drmScheme: String = "clearkey",
        streamName: String = "Network Stream"
    ): String? {
        // On TV launch FloatingPlayerActivity directly
        if (DeviceUtils.isTvDevice) {
            FloatingPlayerActivity.startWithNetworkStream(context, streamUrl, cookie, referer, origin, drmLicense, userAgent, drmScheme, streamName)
            return null
        }

        if (!hasOverlayPermission(context)) return null
        if (streamUrl.isBlank()) return null
        if (!FloatingPlayerManager.canAddNewPlayer()) return null

        val instanceId = UUID.randomUUID().toString()
        FloatingPlayerManager.addPlayer(instanceId, streamName, "network_stream")

        return try {
            val started = FloatingPlayerService.startFloatingPlayerWithNetworkStream(
                context = context,
                instanceId = instanceId,
                streamUrl = streamUrl,
                cookie = cookie,
                referer = referer,
                origin = origin,
                drmLicense = drmLicense,
                userAgent = userAgent,
                drmScheme = drmScheme,
                streamName = streamName
            )
            if (started) {
                createdInstances.add(instanceId)
                instanceId
            } else {
                FloatingPlayerManager.removePlayer(instanceId)
                null
            }
        } catch (e: Exception) {
            FloatingPlayerManager.removePlayer(instanceId)
            null
        }
    }

    private fun updateFloatingPlayer(context: Context, instanceId: String, channel: Channel, linkIndex: Int) {
        try {
            val resolvedChannel = if (channel.links.isNullOrEmpty()) {
                if (channel.streamUrl.isBlank()) return
                channel.copy(links = listOf(parseLinkFromStreamUrl(channel.streamUrl)))
            } else {
                channel
            }
            FloatingPlayerService.updateFloatingPlayer(context, instanceId, resolvedChannel, linkIndex)
        } catch (e: Exception) {

        }
    }

    fun createNewFloatingPlayer(
        context: Context,
        channel: Channel? = null,
        event: LiveEvent? = null,
        linkIndex: Int = 0,
        eventId: String? = null
    ): String? {
        if (!FloatingPlayerManager.canAddNewPlayer()) return null
        if (!hasOverlayPermission(context)) {
            requestOverlayPermission(context)
            return null
        }
        if (channel == null && event == null) return null

        val instanceId = UUID.randomUUID().toString()
        val contentName = channel?.name ?: event?.title ?: "Unknown"
        val contentType = if (channel != null) "channel" else "event"
        val actualEventId = eventId ?: channel?.id ?: event?.id ?: ""

        FloatingPlayerManager.addPlayer(instanceId, contentName, contentType)

        if (actualEventId.isNotEmpty()) {
            eventToInstanceMap[actualEventId] = instanceId
        }

        return try {
            val started = FloatingPlayerService.startFloatingPlayer(context, instanceId, channel, event, linkIndex)
            if (started) {
                createdInstances.add(instanceId)
                instanceId
            } else {
                FloatingPlayerManager.removePlayer(instanceId)
                eventToInstanceMap.remove(actualEventId)
                null
            }
        } catch (e: Exception) {
            FloatingPlayerManager.removePlayer(instanceId)
            eventToInstanceMap.remove(actualEventId)
            null
        }
    }

    fun closeFloatingPlayer(context: Context, instanceId: String) {
        if (!FloatingPlayerManager.hasPlayer(instanceId)) return
        val eventId = eventToInstanceMap.entries.find { it.value == instanceId }?.key
        if (eventId != null) eventToInstanceMap.remove(eventId)
        FloatingPlayerService.stopFloatingPlayer(context, instanceId)
        FloatingPlayerManager.removePlayer(instanceId)
        createdInstances.remove(instanceId)
    }

    fun closeAllFloatingPlayers(context: Context) {
        val instanceIds = FloatingPlayerManager.getAllPlayerIds()
        instanceIds.forEach { FloatingPlayerService.stopFloatingPlayer(context, it) }
        FloatingPlayerManager.clearAll()
        createdInstances.clear()
        eventToInstanceMap.clear()
    }

    fun hasFloatingPlayers(): Boolean = FloatingPlayerManager.hasAnyPlayers()

    fun getFloatingPlayerCount(): Int = FloatingPlayerManager.getActivePlayerCount()

    fun canCreateMore(): Boolean = FloatingPlayerManager.canAddNewPlayer()

    fun getActivePlayers(): List<FloatingPlayerManager.PlayerMetadata> = FloatingPlayerManager.getAllPlayerMetadata()

    fun hasFloatingPlayerForEvent(eventId: String): Boolean {
        val instanceId = eventToInstanceMap[eventId]
        return instanceId != null && FloatingPlayerManager.hasPlayer(instanceId)
    }

    fun getInstanceIdForEvent(eventId: String): String? = eventToInstanceMap[eventId]

    /**
     * Parses a pipe-encoded stream URL into a ChannelLink with all DRM and header
     * fields correctly populated. Used when an M3U channel has no explicit links list.
     *
     * Example input:
     *   "https://....mpd|drmScheme=clearkey|drmLicense=de8045e9:6807bd09"
     * Produces ChannelLink with url="https://....mpd", drmScheme="clearkey",
     *   drmLicenseUrl="de8045e9:6807bd09"
     */
    private fun parseLinkFromStreamUrl(streamUrl: String): ChannelLink {
        val pipeIndex = streamUrl.indexOf('|')
        if (pipeIndex == -1) {
            return ChannelLink(quality = "Default", url = streamUrl)
        }
        val url = streamUrl.substring(0, pipeIndex).trim()
        val parts = streamUrl.substring(pipeIndex + 1).replace("&", "|").split("|")

        var cookie: String? = null
        var referer: String? = null
        var origin: String? = null
        var userAgent: String? = null
        var drmScheme: String? = null
        var drmLicenseUrl: String? = null

        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq == -1) continue
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim()
            when (key) {
                "drmscheme" -> drmScheme = value
                "drmlicense" -> drmLicenseUrl = value   // keep raw; FloatingPlayerService will decode it
                "cookie" -> cookie = value
                "referer", "referrer" -> referer = value
                "origin" -> origin = value
                "user-agent", "useragent" -> userAgent = value
            }
        }

        return ChannelLink(
            quality = "Default",
            url = url,
            cookie = cookie,
            referer = referer,
            origin = origin,
            userAgent = userAgent,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl
        )
    }
}
