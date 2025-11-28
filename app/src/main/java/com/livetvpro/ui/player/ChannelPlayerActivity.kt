// app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

    // direct controller views (found from controller layout inside PlayerView)
    private var exoPip: ImageButton? = null
    private var exoFullscreen: ImageButton? = null

    private var isInPipMode = false
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

        // anchor player container to top, set 16:9 height to avoid vertical centering
        val screenWidth = resources.displayMetrics.widthPixels
        val expected16by9Height = (screenWidth * 9f / 16f).toInt()
        val containerParams = binding.playerContainer.layoutParams
        if (containerParams is ConstraintLayout.LayoutParams) {
            containerParams.height = expected16by9Height
            containerParams.dimensionRatio = null
            containerParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            containerParams.topMargin = 0
            binding.playerContainer.layoutParams = containerParams
        } else {
            containerParams.height = expected16by9Height
            binding.playerContainer.layoutParams = containerParams
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // lock portrait by default (you can change)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupUIInteractions()

        // restore related section (use binding directly)
        binding.relatedRecyclerView.visibility = View.VISIBLE
        // If you have an existing method to populate related items, call it here:
        // loadRelatedChannels()
    }

    private fun setupPlayer() {
        try {
            player?.release()
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this).setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setUserAgent("LiveTVPro/1.0")
                            .setConnectTimeoutMs(30_000)
                            .setReadTimeoutMs(30_000)
                            .setAllowCrossProtocolRedirects(true)
                    )
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            // telemetry / debug if needed
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error setting up ExoPlayer")
        }

        // ensure player view interactive and on top
        binding.playerView.apply {
            useController = true
            isClickable = true
            isFocusable = true
            requestFocus()
            bringToFront()
            controllerShowTimeoutMs = 4000
        }

        // ensure container doesn't intercept touches
        binding.playerContainer.isClickable = false
        binding.playerContainer.isFocusable = false
    }

    private fun setupCustomControls() {
        // Find controller buttons inside the PlayerView controller layout
        // (these IDs must exist in your controller layout: exo_pip, exo_fullscreen)
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)

        // If your controller layout uses different ids, update those ids accordingly.
        // Wire PiP
        exoPip?.setOnClickListener { enterPipMode() }

        // Wire fullscreen toggle
        exoFullscreen?.setOnClickListener { toggleFullscreen() }

        // Ensure icons are visible in controller
        exoPip?.visibility = View.VISIBLE
        exoFullscreen?.visibility = View.VISIBLE

        // Set initial fullscreen icon if drawable exists
        val drawableId = resources.getIdentifier("ic_fullscreen", "drawable", packageName)
        if (drawableId != 0) {
            exoFullscreen?.setImageResource(drawableId)
        }
    }

    private fun setupUIInteractions() {
        // Show controller on tap
        binding.playerView.setOnClickListener {
            binding.playerView.showController()
        }

        // explicitly use PlayerView.ControllerVisibilityListener to avoid ambiguity
        binding.playerView.setControllerVisibilityListener(object :
            PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                if (visibility == View.VISIBLE) {
                    binding.playerView.bringToFront()
                }
            }
        })
    }

    // --------------------
    // Picture-in-Picture (PiP)
    // --------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.pip_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, getString(R.string.pip_not_supported), Toast.LENGTH_SHORT).show()
            return
        }

        val width = binding.playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = binding.playerContainer.height.takeIf { it > 0 } ?: (width * 9 / 16)
        val aspectRatio = Rational(width, height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()

        isInPipMode = true
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        // optionally auto-enter PiP when user leaves; left commented to keep explicit PiP action
        // if (!isInPipMode) enterPipMode()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.playerView.useController = false
        } else {
            binding.playerView.useController = true
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
        }
    }

    // --------------------
    // Fullscreen toggle
    // --------------------
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            binding.relatedRecyclerView.visibility = View.GONE
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.relatedRecyclerView.visibility = View.VISIBLE
        }

        // swap fullscreen icon if exit drawable exists
        val drawableName = if (isFullscreen) "ic_fullscreen_exit" else "ic_fullscreen"
        val drawableId = resources.getIdentifier(drawableName, "drawable", packageName)
        if (drawableId != 0) exoFullscreen?.setImageResource(drawableId)
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            player?.run {
                playWhenReady = false
                release()
            }
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isInPipMode) {
            player?.release()
            player = null
        }
    }

    // helper to hide system UI
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
