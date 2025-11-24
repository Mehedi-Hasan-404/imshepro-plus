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
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

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

        // Make fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        setupUI()
        initializePlayer()
    }

    private fun setupUI() {
        // Set channel name in custom player controls
        val channelNameView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        channelNameView?.text = channel.name

        // Setup back button in custom controls
        val backButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_back", "id", packageName)
        )
        backButton?.setOnClickListener {
            finish()
        }

        // Lock to landscape for better viewing
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setUserAgent("LiveTVPro/1.0")
                            .setConnectTimeoutMs(30000)
                            .setReadTimeoutMs(30000)
                    )
            )
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                binding.playerView.controllerAutoShow = true
                binding.playerView.controllerShowTimeoutMs = 3000 // Auto-hide after 3 seconds

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
                            }
                            Player.STATE_READY -> {
                                binding.loadingProgress.visibility = View.GONE
                                binding.errorView.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Playback ended")
                            }
                            Player.STATE_IDLE -> {
                                Timber.d("Player idle")
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Timber.e(error, "Playback error")
                        binding.loadingProgress.visibility = View.GONE
                        binding.errorView.visibility = View.VISIBLE
                        binding.errorText.text = "Failed to play stream: ${error.message}"
                    }
                })
            }

        Timber.d("Player initialized for channel: ${channel.name}")
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
    }

    override fun onStop() {
        super.onStop()
        player?.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
