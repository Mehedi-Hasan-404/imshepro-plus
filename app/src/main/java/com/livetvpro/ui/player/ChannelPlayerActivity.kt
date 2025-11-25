package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.video.VideoSize
import com.livetvpro.databinding.ActivityChannelPlayerBinding

class ChannelPlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: SimpleExoPlayer? = null

    private lateinit var exoPlayPause: ImageButton
    private lateinit var exoRewind: ImageButton
    private lateinit var exoForward: ImageButton
    private lateinit var exoFullscreen: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoLock: ImageButton

    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var retryButton: View

    private var isFullscreen = false
    private var isLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        initPlayer()
    }

    private fun initViews() {
        progressBar = binding.progressBar
        errorView = binding.errorView
        retryButton = binding.retryButton

        // Custom controls inside PlayerView
        exoPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
        exoRewind = binding.playerView.findViewById(R.id.exo_rewind)
        exoForward = binding.playerView.findViewById(R.id.exo_forward)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoLock = binding.playerView.findViewById(R.id.exo_lock)

        retryButton.setOnClickListener {
            errorView.isVisible = false
            player?.prepare()
            player?.play()
        }

        exoFullscreen.setOnClickListener { toggleFullscreen() }
        exoPip.setOnClickListener { enterPipMode() }
        exoLock.setOnClickListener { toggleLock() }

        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.lockOverlay.isVisible = false
        }
    }

    private fun initPlayer() {
        player = SimpleExoPlayer.Builder(this).build()
        binding.playerView.player = player

        val url = intent.getStringExtra("url") ?: return
        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)

        player?.prepare()
        player?.playWhenReady = true

        player?.addListener(object : Player.Listener {
            override fun onIsLoadingChanged(isLoading: Boolean) {
                progressBar.isVisible = isLoading
            }

            override fun onPlayerError(error: PlaybackException) {
                errorView.isVisible = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Player remains TOP (no jump)
                // No dynamic resizing here
            }
        })
    }

    private fun toggleFullscreen() {
        if (!isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isFullscreen = true
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            isFullscreen = false
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        binding.lockOverlay.isVisible = isLocked
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.relatedChannelsSection.isVisible = false

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()

            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        if (!isFinishing)
            enterPipMode()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
