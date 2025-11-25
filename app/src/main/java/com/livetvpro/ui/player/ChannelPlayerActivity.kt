// ===================================
// FILE: app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
// ACTION: CREATE NEW FILE
// ===================================

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
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var isFullscreen = false

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // START IN PORTRAIT MODE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel data provided")
            finish()
            return
        }

        Timber.d("ChannelPlayerActivity started for: ${channel.name}")

        setupToolbar()
        setupPlayer()
        setupRelatedChannels()
        loadRelatedChannels()
    }

    private fun setupToolbar() {
        binding.toolbarTitle.text = channel.name
        
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        binding.btnFill.setOnClickListener {
            toggleAspectRatio()
        }
    }

    private fun toggleAspectRatio() {
        binding.playerView.resizeMode = 
            if (binding.playerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        
        Timber.d("Aspect ratio toggled to: ${binding.playerView.resizeMode}")
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        
        if (isFullscreen) {
            // FULLSCREEN MODE - Landscape
            Timber.d("Entering fullscreen mode")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            // Hide related channels and toolbar
            binding.toolbar.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE
            
            // Make player fill screen
            val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.dimensionRatio = null
            params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            binding.playerContainer.layoutParams = params
            
            // Update fullscreen icon
            binding.btnFullscreen.setImageResource(R.drawable.ic_close)
            
        } else {
            // PORTRAIT MODE
            Timber.d("Exiting fullscreen mode")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            
            // Show related channels and toolbar
            binding.toolbar.visibility = View.VISIBLE
            binding.relatedChannelsSection.visibility = View.VISIBLE
            
            // Restore 16:9 player
            val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.dimensionRatio = "16:9"
            params.bottomToTop = R.id.related_channels_section
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            binding.playerContainer.layoutParams = params
            
            // Update fullscreen icon
            binding.btnFullscreen.setImageResource(R.drawable.ic_search)
        }
    }

    private fun setupPlayer() {
        try {
            Timber.d("Setting up player for: ${channel.name}")
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
                    
                    // Player configuration
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    // Prepare media
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    // Add listener
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    Timber.d("Player buffering")
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    Timber.d("Player ready - playing")
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
                            Timber.e(error, "Playback error")
                            binding.progressBar.visibility = View.GONE
                            
                            val errorMessage = when (error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> 
                                    "Server error: Unable to connect"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> 
                                    "Network error: Check connection"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> 
                                    "Connection timeout"
                                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> 
                                    "Invalid stream format"
                                else -> "Failed to play: ${error.message}"
                            }
                            
                            showError(errorMessage)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Timber.d("Is playing: $isPlaying")
                        }
                    })
                }
                
            Timber.d("Player setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player: ${e.message}")
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(
                this@ChannelPlayerActivity,
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
            
            if (channels.isEmpty()) {
                binding.relatedChannelsSection.visibility = View.GONE
            }
        }
    }

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching from '${channel.name}' to '${newChannel.name}'")
        
        // Update channel
        channel = newChannel
        binding.toolbarTitle.text = channel.name
        
        // Stop current playback
        player?.stop()
        
        // Load new stream
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
        
        // Hide error if showing
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
        Timber.e("Showing error: $message")
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
        Timber.d("Player stopped and released")
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        Timber.d("ChannelPlayerActivity destroyed")
    }
}
