package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.player.settings.PlayerSettingsDialog
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    
    private var currentChannel: Channel? = null
    private var currentEvent: LiveEvent? = null
    private lateinit var relatedAdapter: RelatedChannelAdapter
    
    private var isFullscreen = false
    private var isControlsLocked = false
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }

        fun start(context: Context, event: LiveEvent) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsContainer.visibility = View.GONE

        if (intent.hasExtra(EXTRA_CHANNEL)) {
            currentChannel = intent.getParcelableExtra(EXTRA_CHANNEL)
        } else if (intent.hasExtra(EXTRA_EVENT)) {
            currentEvent = intent.getParcelableExtra(EXTRA_EVENT)
        }

        if (currentChannel == null && currentEvent == null) {
            finish()
            return
        }

        setupUI()
        setupRelatedList()
        
        if (currentChannel != null) {
            initializePlayer(currentChannel!!.streamUrl)
            viewModel.loadRelatedChannels(currentChannel!!.categoryId, currentChannel!!.id)
            updateTitle(currentChannel!!.name)
        } else if (currentEvent != null) {
            val url = currentEvent!!.links.firstOrNull()?.url ?: ""
            initializePlayer(url)
            viewModel.loadRelatedEvents(currentEvent!!.id)
            updateTitle(currentEvent!!.title.ifEmpty { "${currentEvent!!.team1Name} vs ${currentEvent!!.team2Name}" })
        }
    }

    private fun setupUI() {
        val backBtn = binding.playerView.findViewById<View>(resources.getIdentifier("exo_back", "id", packageName))
        backBtn?.setOnClickListener { finish() }

        val settingsBtn = binding.playerView.findViewById<View>(resources.getIdentifier("exo_settings", "id", packageName))
        settingsBtn?.setOnClickListener { showSettings() }

        val fullScreenBtn = binding.playerView.findViewById<ImageButton>(resources.getIdentifier("exo_fullscreen", "id", packageName))
        fullScreenBtn?.setOnClickListener { toggleFullscreen() }

        val pipBtn = binding.playerView.findViewById<View>(resources.getIdentifier("exo_pip", "id", packageName))
        pipBtn?.setOnClickListener { enterPipMode() }

        val lockBtn = binding.playerView.findViewById<View>(resources.getIdentifier("exo_lock", "id", packageName))
        lockBtn?.setOnClickListener { toggleLockMode() }

        val resizeBtn = binding.playerView.findViewById<View>(resources.getIdentifier("exo_aspect_ratio", "id", packageName))
        resizeBtn?.setOnClickListener { toggleResizeMode() }

        binding.unlockButton.setOnClickListener { toggleLockMode() }
        
        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (isControlsLocked && visibility == View.VISIBLE) {
                binding.playerView.hideController()
                binding.lockOverlay.visibility = View.VISIBLE
            }
        })
    }
    
    private fun updateTitle(title: String) {
        val titleView = binding.playerView.findViewById<TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        titleView?.text = title
    }

    private fun setupRelatedList() {
        relatedAdapter = RelatedChannelAdapter { item ->
            switchContent(item)
        }
        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedAdapter
        }

        viewModel.relatedItems.observe(this) { list ->
            relatedAdapter.submitList(list)
            binding.relatedChannelsContainer.visibility = if (list.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchContent(item: Channel) {
        player?.release()
        
        if (currentEvent != null) {
            updateTitle(item.name)
            initializePlayer(item.streamUrl)
            viewModel.loadRelatedEvents(item.id)
        } else {
            currentChannel = item
            updateTitle(item.name)
            initializePlayer(item.streamUrl)
            viewModel.loadRelatedChannels(item.categoryId, item.id)
        }
    }

    private fun initializePlayer(url: String) {
        if (url.isEmpty()) {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.no_data)
            return
        }

        try {
            val parts = url.split("|")
            val streamUrl = parts[0]
            val headers = mutableMapOf<String, String>()
            var userAgent = "LiveTVPro/1.0"
            var drmScheme: String? = null
            var drmLicense: String? = null
            
            if (parts.size > 1) {
                for (i in 1 until parts.size) {
                    val p = parts[i]
                    val eq = p.indexOf('=')
                    if (eq > 0) {
                        val key = p.substring(0, eq)
                        val value = p.substring(eq + 1)
                        when (key) {
                            "User-Agent" -> userAgent = value
                            "drmScheme" -> drmScheme = value
                            "drmLicense" -> drmLicense = value
                            else -> headers[key] = value
                        }
                    }
                }
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(headers)

            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

            binding.playerView.player = player
            binding.playerView.resizeMode = resizeMode
            binding.playerView.keepScreenOn = true

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)

            if (drmScheme != null) {
                val uuid = getDrmUuid(drmScheme)
                if (uuid != null) {
                    val drmConfig = MediaItem.DrmConfiguration.Builder(uuid)
                    if (drmLicense != null) {
                        if (drmLicense.contains(":") && !drmLicense.startsWith("http")) {
                            val dParts = drmLicense.split(":", limit = 2)
                            if (dParts.size == 2) drmConfig.setLicenseUri(dParts[0])
                        } else {
                            drmConfig.setLicenseUri(drmLicense)
                        }
                    }
                    mediaItemBuilder.setDrmConfiguration(drmConfig.build())
                }
            }

            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            player?.playWhenReady = true

            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            binding.loadingProgress.visibility = View.VISIBLE
                            binding.errorView.visibility = View.GONE
                        }
                        Player.STATE_READY -> {
                            binding.loadingProgress.visibility = View.GONE
                            binding.errorView.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> binding.loadingProgress.visibility = View.GONE
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.loadingProgress.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    binding.errorText.text = error.message
                }
            })

        } catch (e: Exception) {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = e.message
        }
    }

    private fun getDrmUuid(scheme: String): UUID? {
        return when (scheme.lowercase()) {
            "widevine" -> C.WIDEVINE_UUID
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> C.WIDEVINE_UUID
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val icon = binding.playerView.findViewById<ImageButton>(resources.getIdentifier("exo_fullscreen", "id", packageName))
        
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.relatedChannelsContainer.visibility = View.GONE
            icon?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (!relatedAdapter.currentList.isEmpty()) {
                binding.relatedChannelsContainer.visibility = View.VISIBLE
            }
            icon?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun toggleLockMode() {
        isControlsLocked = !isControlsLocked
        if (isControlsLocked) {
            binding.playerView.hideController()
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
        }
    }

    private fun toggleResizeMode() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = resizeMode
    }

    private fun showSettings() {
        player?.let { PlayerSettingsDialog(this, it).show() }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.playerView.hideController()
            binding.relatedChannelsContainer.visibility = View.GONE
        } else {
            binding.playerView.showController()
            if (!relatedAdapter.currentList.isEmpty()) {
                binding.relatedChannelsContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
        } else {
            player?.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
