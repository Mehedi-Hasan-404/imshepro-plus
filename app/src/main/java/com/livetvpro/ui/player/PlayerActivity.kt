package com.livetvpro.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make fullscreen and keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // START IN PORTRAIT MODE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel data provided")
            showError("No channel data available")
            return
        }

        // Validate channel data
        if (!validateChannel()) {
            return
        }

        setupUI()
        setupRelatedChannels()
        initializePlayer()
        loadRelatedChannels()
    }

    private fun validateChannel(): Boolean {
        return when {
            channel.name.isEmpty() -> {
                Timber.e("Channel name is empty")
                showError("Invalid channel: missing name")
                false
            }
            channel.streamUrl.isEmpty() -> {
                Timber.e("Stream URL is empty for channel: ${channel.name}")
                showError("Invalid stream URL for ${channel.name}")
                false
            }
            !isValidUrl(channel.streamUrl) -> {
                Timber.e("Invalid stream URL format: ${channel.streamUrl}")
                showError("Invalid stream URL format")
                false
            }
            else -> true
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) || 
               url.startsWith("https://", ignoreCase = true)
    }

    private fun setupUI() {
        // Set channel name in custom player controls
        val channelNameView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        channelNameView?.text = channel.name

        // Setup back button
        val backButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_back", "id", packageName)
        )
        backButton?.setOnClickListener {
            finish()
        }

        // Setup fullscreen button
        val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_fullscreen", "id", packageName)
        )
        fullscreenButton?.setOnClickListener {
            toggleFullscreen()
        }
    }

    private var isFullscreen = false

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        
        if (isFullscreen) {
            // Enter fullscreen - switch to landscape
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.relatedChannelsContainer.visibility = View.GONE
            
            // Update fullscreen icon
            val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
                resources.getIdentifier("exo_fullscreen", "id", packageName)
            )
            fullscreenButton?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            // Exit fullscreen - switch back to portrait
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.relatedChannelsContainer.visibility = View.VISIBLE
            
            // Update fullscreen icon
            val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
                resources.getIdentifier("exo_fullscreen", "id", packageName)
            )
            fullscreenButton?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            // When a related channel is clicked, switch to it
            switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(
                this@PlayerActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        
        viewModel.relatedChannels.observe(this) { channels ->
            Timber.d("Loaded ${channels.size} related channels")
            relatedChannelsAdapter.submitList(channels)
            
            // Show/hide related channels section
            binding.relatedChannelsContainer.visibility = 
                if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        
        // Release current player
        player?.release()
        player = null
        
        // Update current channel
        channel = newChannel
        
        // Update UI
        val channelNameView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        channelNameView?.text = channel.name
        
        // Reinitialize player with new channel
        if (validateChannel()) {
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        try {
            Timber.d("Initializing player for channel: ${channel.name}")
            Timber.d("Stream URL: ${channel.streamUrl}")

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("LiveTVPro/1.0")
                                .setConnectTimeoutMs(30000)
                                .setReadTimeoutMs(30000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 3000
                    binding.playerView.useController = true

                    // Setup FILL button for aspect ratio
                    setupPlayerControls()

                    // Prepare media source
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    // Add listener
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.loadingProgress.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    Timber.d("Player buffering")
                                }
                                Player.STATE_READY -> {
                                    binding.loadingProgress.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    Timber.d("Player ready")
                                }
                                Player.STATE_ENDED -> {
                                    Timber.d("Playback ended")
                                    showError("Stream ended")
                                }
                                Player.STATE_IDLE -> {
                                    Timber.d("Player idle")
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Timber.e(error, "Playback error for ${channel.name}")
                            binding.loadingProgress.visibility = View.GONE
                            
                            val errorMessage = when (error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> 
                                    "Server error: Unable to connect to stream"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> 
                                    "Network error: Check your internet connection"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> 
                                    "Connection timeout: Stream took too long to respond"
                                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> 
                                    "Invalid stream format"
                                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> 
                                    "Invalid stream manifest"
                                else -> "Failed to play stream: ${error.message ?: "Unknown error"}"
                            }
                            
                            showError(errorMessage)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                Timber.d("Playback started")
                            } else {
                                Timber.d("Playback paused")
                            }
                        }
                    })
                }

            Timber.d("Player initialized successfully for channel: ${channel.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize player")
            showError("Failed to initialize player: ${e.message}")
        }
    }

    private fun setupPlayerControls() {
        // Setup FILL button to toggle aspect ratio
        val fillButton = binding.playerView.findViewById<android.widget.Button>(
            resources.getIdentifier("exo_fill", "id", packageName)
        )
        fillButton?.setOnClickListener {
            // Toggle aspect ratio between FIT and FILL
            binding.playerView.resizeMode = 
                if (binding.playerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                } else {
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.loadingProgress.visibility = View.GONE
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        Timber.d("Player paused")
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        Timber.d("Player stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        Timber.d("Player destroyed")
    }
}
