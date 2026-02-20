package com.livetvpro.ui.player

import android.widget.FrameLayout
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import kotlin.math.abs

@dagger.hilt.android.AndroidEntryPoint
class FloatingPlayerService : Service() {

    data class FloatingPlayerInstance(
        val instanceId: String,
        val floatingView: View,
        val player: ExoPlayer,
        val playerView: PlayerView,
        val params: WindowManager.LayoutParams,
        var currentChannel: Channel?,
        var currentEvent: com.livetvpro.data.models.LiveEvent? = null,
        var controlsLocked: Boolean = false,
        var isMuted: Boolean = false,
        val lockOverlay: View?,
        val unlockButton: ImageButton?,
        var isNetworkStream: Boolean = false,
        var networkStreamUrl: String? = null,
        var networkStreamName: String? = null,
        var networkCookie: String? = null,
        var networkReferer: String? = null,
        var networkOrigin: String? = null,
        var networkDrmLicense: String? = null,
        var networkUserAgent: String? = null,
        var networkDrmScheme: String? = null
    )

    private var windowManager: WindowManager? = null
    private val activeInstances = mutableMapOf<String, FloatingPlayerInstance>()
    private val hideControlsHandlers = mutableMapOf<String, android.os.Handler>()

    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager

    private fun getMinWidth() = dpToPx(240)
    private fun getMaxWidth() = dpToPx(400)
    private fun getMinHeight() = getMinWidth() * 9 / 16
    private fun getMaxHeight() = getMaxWidth() * 9 / 16

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_EVENT = "extra_event"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val EXTRA_LINK_INDEX = "extra_link_index"
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val EXTRA_RESTORE_POSITION = "extra_restore_position"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_INSTANCE = "action_stop_instance"
        const val ACTION_UPDATE_STREAM = "action_update_stream"
        const val ACTION_HIDE_OTHERS = "action_hide_others"
        const val ACTION_SHOW_ALL = "action_show_all"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"

        fun start(context: Context, channel: Channel, linkIndex: Int = 0, playbackPosition: Long = 0L) {
            try {
                if (channel.links == null || channel.links?.isEmpty() == true) {
                    android.widget.Toast.makeText(context, "No stream available", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }

                val selectedLink = if (channel.links != null && linkIndex in channel.links!!.indices) {
                    channel.links!![linkIndex]
                } else {
                    channel.links?.firstOrNull()
                }

                val streamUrl = selectedLink?.url ?: ""

                if (streamUrl.isEmpty()) {
                    android.widget.Toast.makeText(context, "Invalid stream URL", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }

                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_CHANNEL, channel)
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, channel.name)
                    putExtra(EXTRA_PLAYBACK_POSITION, playbackPosition)
                    putExtra(EXTRA_LINK_INDEX, linkIndex)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to start floating player: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPlayerService::class.java))
        }

        fun startFloatingPlayer(
            context: Context,
            instanceId: String,
            channel: Channel? = null,
            event: com.livetvpro.data.models.LiveEvent? = null,
            linkIndex: Int = 0
        ): Boolean {
            try {
                if (channel == null && event == null) {
                    android.widget.Toast.makeText(context, "No content provided", android.widget.Toast.LENGTH_SHORT).show()
                    return false
                }

                val streamUrl = when {
                    channel != null -> {
                        val links = channel.links
                        if (!links.isNullOrEmpty()) {
                            val selectedLink = if (linkIndex in links.indices) links[linkIndex] else links.firstOrNull()
                            selectedLink?.url ?: channel.streamUrl
                        } else {
                            channel.streamUrl
                        }.also {
                            if (it.isBlank()) {
                                android.widget.Toast.makeText(context, "No stream available", android.widget.Toast.LENGTH_SHORT).show()
                                return false
                            }
                        }
                    }
                    event != null -> {
                        val links = event.links
                        if (links.isEmpty()) {
                            android.widget.Toast.makeText(context, "No stream available", android.widget.Toast.LENGTH_SHORT).show()
                            return false
                        }
                        val selectedLink = if (linkIndex in links.indices) links[linkIndex] else links.firstOrNull()
                        selectedLink?.url ?: ""
                    }
                    else -> {
                        android.widget.Toast.makeText(context, "No content provided", android.widget.Toast.LENGTH_SHORT).show()
                        return false
                    }
                }

                if (streamUrl.isEmpty()) {
                    android.widget.Toast.makeText(context, "Invalid stream URL", android.widget.Toast.LENGTH_SHORT).show()
                    return false
                }

                val title = channel?.name ?: event?.title ?: "Unknown"

                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    if (channel != null) putExtra(EXTRA_CHANNEL, channel)
                    if (event != null) putExtra(EXTRA_EVENT, event)
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_PLAYBACK_POSITION, 0L)
                    putExtra(EXTRA_LINK_INDEX, linkIndex)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                return true

            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to start floating player: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return false
            }
        }

        fun startFloatingPlayerWithNetworkStream(
            context: Context,
            instanceId: String,
            streamUrl: String,
            cookie: String = "",
            referer: String = "",
            origin: String = "",
            drmLicense: String = "",
            userAgent: String = "Default",
            drmScheme: String = "clearkey",
            streamName: String = "Network Stream"
        ): Boolean {
            try {
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra("IS_NETWORK_STREAM", true)
                    putExtra("STREAM_URL", streamUrl)
                    putExtra("COOKIE", cookie)
                    putExtra("REFERER", referer)
                    putExtra("ORIGIN", origin)
                    putExtra("DRM_LICENSE", drmLicense)
                    putExtra("USER_AGENT", userAgent)
                    putExtra("DRM_SCHEME", drmScheme)
                    putExtra("CHANNEL_NAME", streamName)
                    putExtra(EXTRA_LINK_INDEX, 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                return true

            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to start floating player: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return false
            }
        }

        fun updateFloatingPlayer(context: Context, instanceId: String, channel: Channel, linkIndex: Int) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_UPDATE_STREAM
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            }
            context.startService(intent)
        }

        fun stopFloatingPlayer(context: Context, instanceId: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP_INSTANCE
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            context.startService(intent)
        }

        fun hideOthers(context: Context, exceptInstanceId: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_HIDE_OTHERS
                putExtra(EXTRA_INSTANCE_ID, exceptInstanceId)
            }
            context.startService(intent)
        }

        fun showAll(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_SHOW_ALL
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllInstances()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_INSTANCE -> {
                val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                if (instanceId != null) stopInstance(instanceId)
                if (activeInstances.isEmpty()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STREAM -> {
                val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CHANNEL)
                }
                val linkIndex = intent.getIntExtra(EXTRA_LINK_INDEX, 0)
                if (instanceId != null && channel != null) updateInstanceStream(instanceId, channel, linkIndex)
                return START_STICKY
            }
            ACTION_HIDE_OTHERS -> {
                val exceptId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                activeInstances.forEach { (id, instance) ->
                    if (id != exceptId) instance.floatingView.visibility = View.INVISIBLE
                }
                return START_STICKY
            }
            ACTION_SHOW_ALL -> {
                activeInstances.values.forEach { it.floatingView.visibility = View.VISIBLE }
                return START_STICKY
            }
        }

        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: java.util.UUID.randomUUID().toString()
        val isRestoredFromFullscreen = intent?.getBooleanExtra("use_transferred_player", false) == true
        val isNetworkStream = intent?.getBooleanExtra("IS_NETWORK_STREAM", false) == true

        if (activeInstances.containsKey(instanceId) && !isRestoredFromFullscreen) {
            return START_STICKY
        }

        if (isNetworkStream) {
            val streamUrl = intent?.getStringExtra("STREAM_URL") ?: ""
            val cookie = intent?.getStringExtra("COOKIE") ?: ""
            val referer = intent?.getStringExtra("REFERER") ?: ""
            val origin = intent?.getStringExtra("ORIGIN") ?: ""
            val drmLicense = intent?.getStringExtra("DRM_LICENSE") ?: ""
            val userAgent = intent?.getStringExtra("USER_AGENT") ?: "Default"
            val drmScheme = intent?.getStringExtra("DRM_SCHEME") ?: "clearkey"
            val streamName = intent?.getStringExtra("CHANNEL_NAME") ?: "Network Stream"

            if (streamUrl.isNotBlank()) {
                createFloatingPlayerInstanceForNetworkStream(instanceId, streamUrl, cookie, referer, origin, drmLicense, userAgent, drmScheme, streamName)
                updateNotification()
            }
            return START_STICKY
        }

        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CHANNEL)
        }

        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_EVENT, com.livetvpro.data.models.LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_EVENT)
        }

        val linkIndex = intent?.getIntExtra(EXTRA_LINK_INDEX, 0) ?: 0
        val playbackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        val restorePosition = intent?.getBooleanExtra(EXTRA_RESTORE_POSITION, false) ?: false
        val useTransferredPlayer = intent?.getBooleanExtra("use_transferred_player", false) ?: false

        if (channel != null || event != null) {
            if (useTransferredPlayer) {
                createFloatingPlayerInstanceFromTransfer(instanceId, channel, event, restorePosition)
            } else {
                createFloatingPlayerInstance(instanceId, channel, event, linkIndex, playbackPosition, restorePosition)
            }
            updateNotification()
        }

        return START_STICKY
    }

    private fun createFloatingPlayerInstance(
        instanceId: String,
        channel: Channel? = null,
        event: com.livetvpro.data.models.LiveEvent? = null,
        linkIndex: Int,
        playbackPosition: Long,
        restorePosition: Boolean = false
    ) {
        try {
            val streamUrl = when {
                channel != null -> {
                    val links = channel.links
                    if (!links.isNullOrEmpty()) {
                        val selectedLink = if (linkIndex in links.indices) links[linkIndex] else links.firstOrNull()
                        selectedLink?.url ?: channel.streamUrl
                    } else {
                        channel.streamUrl
                    }.also { if (it.isBlank()) return }
                }
                event != null -> {
                    val links = event.links
                    if (links.isEmpty()) return
                    val selectedLink = if (linkIndex in links.indices) links[linkIndex] else links.firstOrNull()
                    selectedLink?.url ?: return
                }
                else -> return
            }

            val title = channel?.name ?: event?.title ?: "Unknown"
            val floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)

            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            val statusBarHeight: Int = run {
                val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            }

            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()

            val initialWidth: Int
            val initialHeight: Int

            if (savedWidth > 0 && savedHeight > 0) {
                initialWidth = savedWidth.coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = savedHeight.coerceIn(getMinHeight(), getMaxHeight())
            } else {
                initialWidth = (screenWidth * 0.6f).toInt().coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = initialWidth * 9 / 16
            }

            val initialX = (screenWidth - initialWidth) / 2
            val initialY = statusBarHeight + (screenHeight - statusBarHeight - initialHeight) / 2

            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }

            val selectedLinkForHeaders: com.livetvpro.data.models.ChannelLink? = when {
                channel != null -> {
                    val links = channel.links
                    if (links != null && linkIndex in links.indices) links[linkIndex] else links?.firstOrNull()
                }
                else -> null
            }
            val headers = mutableMapOf<String, String>()
            selectedLinkForHeaders?.let { link ->
                link.referer?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }
                link.cookie?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
                link.origin?.takeIf { it.isNotEmpty() }?.let { headers["Origin"] = it }
                link.userAgent?.takeIf { it.isNotEmpty() }?.let { headers["User-Agent"] = it }
            }
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            playerView.player = player

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
            if (streamUrl.contains("m3u8", ignoreCase = true) ||
                streamUrl.contains("extension=m3u8", ignoreCase = true)) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true

            if (playbackPosition > 0) player.seekTo(playbackPosition)

            val titleText = floatingView.findViewById<TextView>(R.id.tv_title)
            titleText.text = title

            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)

            setupFloatingControls(floatingView, playerView, params, instanceId, player, lockOverlay, unlockButton, channel, event)

            windowManager?.addView(floatingView, params)
            hideControlsHandlers[instanceId] = android.os.Handler(android.os.Looper.getMainLooper())

            val instance = FloatingPlayerInstance(
                instanceId = instanceId,
                floatingView = floatingView,
                player = player,
                playerView = playerView,
                params = params,
                currentChannel = channel,
                currentEvent = event,
                lockOverlay = lockOverlay,
                unlockButton = unlockButton
            )

            activeInstances[instanceId] = instance

            if (activeInstances.size == 1) {
                val notification = createNotification(title)
                startForeground(NOTIFICATION_ID, notification)
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Failed to create floating player: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFloatingPlayerInstanceFromTransfer(
        instanceId: String,
        channel: Channel? = null,
        event: com.livetvpro.data.models.LiveEvent? = null,
        restorePosition: Boolean
    ) {
        try {
            val transferredPlayer = PlayerHolder.player
            if (transferredPlayer == null) {
                createFloatingPlayerInstance(instanceId, channel, event, 0, 0L, restorePosition)
                return
            }

            PlayerHolder.clearReferences()

            val floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)

            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            val statusBarHeight: Int = run {
                val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            }

            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()
            val initialWidth = if (savedWidth > 0) savedWidth.coerceIn(getMinWidth(), getMaxWidth())
                               else (screenWidth * 0.6f).toInt().coerceIn(getMinWidth(), getMaxWidth())
            val initialHeight = if (savedHeight > 0) savedHeight.coerceIn(getMinHeight(), getMaxHeight())
                                else initialWidth * 9 / 16

            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            val initialX = if (savedX != Int.MIN_VALUE) savedX else (screenWidth - initialWidth) / 2
            val initialY = if (savedY != Int.MIN_VALUE) savedY
                           else statusBarHeight + (screenHeight - statusBarHeight - initialHeight) / 2

            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = initialX
                y = initialY
            }

            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            playerView.player = transferredPlayer

            val titleText = floatingView.findViewById<TextView>(R.id.tv_title)
            titleText.text = channel?.name ?: event?.title ?: "Unknown"

            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)

            setupFloatingControls(floatingView, playerView, params, instanceId, transferredPlayer, lockOverlay, unlockButton, channel, event)

            windowManager?.addView(floatingView, params)
            hideControlsHandlers[instanceId] = android.os.Handler(android.os.Looper.getMainLooper())

            val instance = FloatingPlayerInstance(
                instanceId = instanceId,
                floatingView = floatingView,
                player = transferredPlayer,
                playerView = playerView,
                params = params,
                currentChannel = channel,
                currentEvent = event,
                lockOverlay = lockOverlay,
                unlockButton = unlockButton
            )
            activeInstances[instanceId] = instance

            val contentName = channel?.name ?: event?.title ?: "Unknown"
            val contentType = if (channel != null) "channel" else "event"
            com.livetvpro.utils.FloatingPlayerManager.addPlayer(instanceId, contentName, contentType)

            if (activeInstances.size == 1) {
                val notification = createNotification(contentName)
                startForeground(NOTIFICATION_ID, notification)
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Failed to restore floating player: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFloatingPlayerInstanceForNetworkStream(
        instanceId: String,
        streamUrl: String,
        cookie: String,
        referer: String,
        origin: String,
        drmLicense: String,
        userAgent: String,
        drmScheme: String,
        streamName: String
    ) {
        try {
            val floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)

            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            val statusBarHeight: Int = run {
                val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            }

            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()
            val initialWidth = if (savedWidth > 0) savedWidth.coerceIn(getMinWidth(), getMaxWidth())
                               else (screenWidth * 0.6f).toInt().coerceIn(getMinWidth(), getMaxWidth())
            val initialHeight = if (savedHeight > 0) savedHeight.coerceIn(getMinHeight(), getMaxHeight())
                                else initialWidth * 9 / 16

            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            val initialX = if (savedX != Int.MIN_VALUE) savedX else (screenWidth - initialWidth) / 2
            val initialY = if (savedY != Int.MIN_VALUE) savedY
                           else statusBarHeight + (screenHeight - statusBarHeight - initialHeight) / 2

            val layoutParams = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = initialX
                y = initialY
            }

            val headers = mutableMapOf<String, String>()
            if (cookie.isNotEmpty()) headers["Cookie"] = cookie
            if (referer.isNotEmpty()) headers["Referer"] = referer
            if (origin.isNotEmpty()) headers["Origin"] = origin
            val effectiveUserAgent = if (userAgent.isNotEmpty() && userAgent != "Default")
                userAgent else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            headers["User-Agent"] = effectiveUserAgent

            val nsDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(effectiveUserAgent)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            val nsMediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(nsDataSourceFactory)

            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(nsMediaSourceFactory)
                .build()

            val nsMediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
            if (streamUrl.contains("m3u8", ignoreCase = true) ||
                streamUrl.contains("extension=m3u8", ignoreCase = true)) {
                nsMediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            player.setMediaItem(nsMediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true

            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            playerView.player = player

            val titleText = floatingView.findViewById<TextView>(R.id.tv_title)
            titleText.text = streamName

            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)

            setupFloatingControls(floatingView, playerView, layoutParams, instanceId, player, lockOverlay, unlockButton, null, null)

            windowManager?.addView(floatingView, layoutParams)
            hideControlsHandlers[instanceId] = android.os.Handler(android.os.Looper.getMainLooper())

            val instance = FloatingPlayerInstance(
                instanceId = instanceId,
                floatingView = floatingView,
                player = player,
                playerView = playerView,
                params = layoutParams,
                currentChannel = null,
                currentEvent = null,
                lockOverlay = lockOverlay,
                unlockButton = unlockButton,
                isNetworkStream = true,
                networkStreamUrl = streamUrl,
                networkStreamName = streamName,
                networkCookie = cookie,
                networkReferer = referer,
                networkOrigin = origin,
                networkDrmLicense = drmLicense,
                networkUserAgent = userAgent,
                networkDrmScheme = drmScheme
            )
            activeInstances[instanceId] = instance

            if (activeInstances.size == 1) {
                val notification = createNotification(streamName)
                startForeground(NOTIFICATION_ID, notification)
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Failed to create floating player: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateInstanceStream(instanceId: String, channel: Channel, linkIndex: Int) {
        val instance = activeInstances[instanceId] ?: return

        try {
            val streamUrl = if (!channel.links.isNullOrEmpty()) {
                val selectedLink = if (linkIndex in channel.links!!.indices) {
                    channel.links!![linkIndex]
                } else {
                    channel.links!!.firstOrNull()
                }
                selectedLink?.url ?: channel.streamUrl
            } else {
                channel.streamUrl
            }

            if (streamUrl.isBlank()) {
                android.widget.Toast.makeText(this, "No stream available", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            instance.currentChannel = channel

            val titleText = instance.floatingView.findViewById<TextView>(R.id.tv_title)
            titleText.text = channel.name

            val mediaItem = MediaItem.Builder().setUri(streamUrl).build()
            instance.player.setMediaItem(mediaItem)
            instance.player.prepare()
            instance.player.playWhenReady = true

            updateNotification()

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Failed to update stream: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFloatingControls(
        floatingView: View,
        playerView: PlayerView,
        params: WindowManager.LayoutParams,
        instanceId: String,
        player: ExoPlayer,
        lockOverlay: View?,
        unlockButton: ImageButton?,
        channel: Channel?,
        event: com.livetvpro.data.models.LiveEvent? = null
    ) {
        val btnClose = playerView.findViewById<ImageButton>(R.id.btn_close)
        val btnFullscreen = playerView.findViewById<ImageButton>(R.id.btn_fullscreen)
        val btnMute = playerView.findViewById<ImageButton>(R.id.btn_mute)
        val btnLock = playerView.findViewById<ImageButton>(R.id.btn_lock)
        val btnPlayPause = playerView.findViewById<ImageButton>(R.id.btn_play_pause)
        val btnSeekBack = playerView.findViewById<ImageButton>(R.id.btn_seek_back)
        val btnSeekForward = playerView.findViewById<ImageButton>(R.id.btn_seek_forward)
        val btnResize = floatingView.findViewById<ImageButton>(R.id.btn_resize)

        btnClose?.setOnClickListener {
            stopInstance(instanceId)
        }

        btnFullscreen?.setOnClickListener {
            val instance = activeInstances[instanceId]
            val currentPlayer = instance?.player
            val currentChannel = instance?.currentChannel
            val currentEvent = instance?.currentEvent

            if (currentPlayer != null) {
                preferencesManager.setFloatingPlayerWidth(params.width)
                preferencesManager.setFloatingPlayerHeight(params.height)
                preferencesManager.setFloatingPlayerX(params.x)
                preferencesManager.setFloatingPlayerY(params.y)

                val currentUri = currentPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
                val streamUrl = when {
                    currentUri != null -> currentUri
                    currentChannel != null -> currentChannel.links?.firstOrNull()?.url ?: ""
                    currentEvent != null -> currentEvent.links.firstOrNull()?.url ?: ""
                    else -> ""
                }
                val contentName = currentChannel?.name ?: currentEvent?.title ?: "Unknown"
                PlayerHolder.transferPlayer(currentPlayer, streamUrl, contentName)

                activeInstances.forEach { (id, inst) ->
                    if (id != instanceId) inst.floatingView.visibility = View.INVISIBLE
                }

                val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                    val inst = activeInstances[instanceId]
                    if (inst?.isNetworkStream == true) {
                        putExtra("IS_NETWORK_STREAM", true)
                        putExtra("STREAM_URL", inst.networkStreamUrl ?: "")
                        putExtra("CHANNEL_NAME", inst.networkStreamName ?: "Network Stream")
                        putExtra("COOKIE", inst.networkCookie ?: "")
                        putExtra("REFERER", inst.networkReferer ?: "")
                        putExtra("ORIGIN", inst.networkOrigin ?: "")
                        putExtra("DRM_LICENSE", inst.networkDrmLicense ?: "")
                        putExtra("USER_AGENT", inst.networkUserAgent ?: "Default")
                        putExtra("DRM_SCHEME", inst.networkDrmScheme ?: "clearkey")
                    } else {
                        if (currentChannel != null) putExtra("extra_channel", currentChannel)
                        if (currentEvent != null) putExtra("extra_event", currentEvent)
                    }
                    putExtra("use_transferred_player", true)
                    putExtra("source_instance_id", instanceId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)

                hideControlsHandlers[instanceId]?.removeCallbacksAndMessages(null)
                hideControlsHandlers.remove(instanceId)
                try {
                    windowManager?.removeView(activeInstances[instanceId]?.floatingView)
                } catch (e: Exception) { }
                activeInstances.remove(instanceId)
                com.livetvpro.utils.FloatingPlayerManager.removePlayer(instanceId)

                if (activeInstances.isEmpty()) {
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }

        btnMute?.setOnClickListener {
            val instance = activeInstances[instanceId] ?: return@setOnClickListener
            instance.isMuted = !instance.isMuted
            player.volume = if (instance.isMuted) 0f else 1f
            btnMute.setImageResource(if (instance.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }

        btnLock?.setOnClickListener {
            toggleLock(instanceId)
        }

        unlockButton?.setOnClickListener {
            toggleLock(instanceId)
        }

        btnPlayPause?.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                player.play()
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        btnSeekBack?.setOnClickListener {
            player.seekBack()
        }

        btnSeekForward?.setOnClickListener {
            player.seekForward()
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }

            override fun onPlaybackStateChanged(playbackState: Int) { }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)

                val errorMessage = when {
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                        "Connection Failed"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
                        error.message?.contains("403") == true -> "Access Denied"
                        error.message?.contains("404") == true -> "Stream Not Found"
                        else -> "Playback Error"
                    }
                    error.message?.contains("drm", ignoreCase = true) == true ||
                    error.message?.contains("widevine", ignoreCase = true) == true ||
                    error.message?.contains("clearkey", ignoreCase = true) == true ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                        "Stream Error"
                    error.message?.contains("geo", ignoreCase = true) == true ||
                    error.message?.contains("region", ignoreCase = true) == true ->
                        "Not Available"
                    else -> "Playback Error"
                }

                btnPlayPause?.setImageResource(R.drawable.ic_error_outline)

                // Show error message as Toast
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this@FloatingPlayerService,
                        errorMessage,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        setupResizeFunctionality(floatingView, btnResize, params, instanceId)
        setupDragFunctionality(floatingView, params, playerView, lockOverlay, unlockButton, instanceId)
    }

    private fun setupResizeFunctionality(
        floatingView: View,
        btnResize: ImageButton?,
        params: WindowManager.LayoutParams,
        instanceId: String
    ) {
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f

        btnResize?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeInitialWidth = params.width
                    resizeInitialHeight = params.height
                    resizeInitialTouchX = event.rawX
                    resizeInitialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - resizeInitialTouchX
                    val dy = event.rawY - resizeInitialTouchY
                    val delta = ((dx + dy) / 2).toInt()
                    var newWidth = resizeInitialWidth + delta
                    newWidth = newWidth.coerceIn(getMinWidth(), getMaxWidth())
                    val newHeight = newWidth * 9 / 16

                    if (newWidth != params.width || newHeight != params.height) {
                        params.width = newWidth
                        params.height = newHeight
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    preferencesManager.setFloatingPlayerWidth(params.width)
                    preferencesManager.setFloatingPlayerHeight(params.height)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDragFunctionality(
        floatingView: View,
        params: WindowManager.LayoutParams,
        playerView: PlayerView,
        lockOverlay: View?,
        unlockButton: ImageButton?,
        instanceId: String
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var hasMoved = false

        playerView.setOnTouchListener { _, event ->
            val instance = activeInstances[instanceId] ?: return@setOnTouchListener false

            if (instance.controlsLocked) {
                params?.let { p ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            hasMoved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                hasMoved = true
                                p.x = initialX + dx
                                p.y = initialY + dy
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                if (unlockButton?.visibility == View.VISIBLE) {
                                    hideUnlockButton(instanceId)
                                } else {
                                    showUnlockButton(instanceId)
                                }
                            }
                            isDragging = false
                            hasMoved = false
                            true
                        }
                        else -> true
                    }
                } ?: true
            } else {
                params?.let { p ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            hasMoved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                hasMoved = true
                                p.x = initialX + dx
                                p.y = initialY + dy
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            isDragging
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                val currentlyVisible = playerView.isControllerFullyVisible == true
                                if (currentlyVisible) {
                                    playerView.hideController()
                                } else {
                                    playerView.showController()
                                }
                            }
                            val wasMoving = hasMoved
                            isDragging = false
                            hasMoved = false
                            wasMoving
                        }
                        else -> false
                    }
                } ?: false
            }
        }

        lockOverlay?.apply {
            isClickable = false
            isFocusable = false
        }
    }

    private fun toggleLock(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val lockBtn = instance.playerView.findViewById<ImageButton>(R.id.btn_lock)

        if (instance.controlsLocked) {
            instance.controlsLocked = false
            instance.lockOverlay?.visibility = View.GONE
            hideUnlockButton(instanceId)
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            instance.playerView.showController()
        } else {
            instance.controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            instance.playerView.hideController()
            instance.lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
            }
        }
    }

    private fun showUnlockButton(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val handler = hideControlsHandlers[instanceId] ?: return
        instance.unlockButton?.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ instance.unlockButton?.visibility = View.GONE }, 3000)
    }

    private fun hideUnlockButton(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val handler = hideControlsHandlers[instanceId] ?: return
        handler.removeCallbacksAndMessages(null)
        instance.unlockButton?.visibility = View.GONE
    }

    private fun stopInstance(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return

        hideControlsHandlers[instanceId]?.removeCallbacksAndMessages(null)
        hideControlsHandlers.remove(instanceId)

        instance.player.release()

        try {
            windowManager?.removeView(instance.floatingView)
        } catch (e: Exception) { }

        activeInstances.remove(instanceId)
        com.livetvpro.utils.FloatingPlayerManager.removePlayer(instanceId)

        updateNotification()

        if (activeInstances.isEmpty()) stopSelf()
    }

    private fun stopAllInstances() {
        val instanceIds = activeInstances.keys.toList()
        instanceIds.forEach { stopInstance(it) }
    }

    private fun updateNotification() {
        if (activeInstances.isEmpty()) return

        val count = activeInstances.size
        val title = if (count == 1) {
            val instance = activeInstances.values.first()
            instance.currentChannel?.name ?: instance.currentEvent?.title ?: "Unknown"
        } else {
            "$count Floating Players Active"
        }

        val notification = createNotification(title)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating player service notification"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String): Notification {
        val stopIntent = Intent(this, FloatingPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Player")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_play)
            .addAction(R.drawable.ic_close, "Stop All", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width() ?: 1080
        } else {
            val display = windowManager?.defaultDisplay
            val size = Point()
            display?.getSize(size)
            size.x
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.height() ?: 1920
        } else {
            val display = windowManager?.defaultDisplay
            val size = Point()
            display?.getSize(size)
            size.y
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllInstances()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
