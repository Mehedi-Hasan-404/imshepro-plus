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

    // controller buttons from the PlayerView controller
    private var exoPip: ImageButton? = null
    private var exoFullscreen: ImageButton? = null

    // state
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

        // Ensure the top player container is anchored to top and sized as expected (fix centering)
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

        // lock portrait by default
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        // Setup player and UI
        setupPlayer()
        setupCustomControls()
        setupUIInteractions()

        // Restore related section visibility and load data (you likely have a method to populate it)
        binding.relatedRecyclerView.visibility = View.VISIBLE
        loadRelatedChannels()
    }

    private fun setupPlayer() {
        try {
            player?.release()
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("LiveTVPro/1.0")
                                .setConnectTimeoutMs(30_000)
                                .setReadTimeoutMs(30_000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build()
                .also { exoPlayer ->
                    // Attach player to view
                    binding.playerView.player = exoPlayer

                    // Prepare media item from channel stream
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    // Optional: listen for player events for debugging
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            // placeholder: handle events if needed
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error setting up ExoPlayer")
        }

        // Ensure PlayerView is interactive and on top (fix controller unresponsive)
        binding.playerView.apply {
            useController = true
            isClickable = true
            isFocusable = true
            requestFocus()
            bringToFront()
            controllerShowTimeoutMs = 4000
        }

        // Avoid parent views intercepting touches — make container non-clickable so PlayerView receives touch events
        binding.playerContainer.isClickable = false
        binding.playerContainer.isFocusable = false
    }

    private fun setupCustomControls() {
        // Find controller buttons inside the PlayerView controller
        // NOTE: these ids must match your controller layout. If you use different ids, change them here.
        exoPip = binding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_pip) ?:
                 binding.playerView.findViewById(R.id.exo_pip)  // try common ids (media3 vs exoplayer)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)

        // If the controller buttons are null because your controller layout does not define them,
        // you may add a floating button in activity layout and find it here instead.

        // PiP button wiring
        exoPip?.setOnClickListener {
            enterPipMode()
        }

        // Fullscreen toggle wiring
        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        // If icons were accidentally removed from controller layout, ensure visibility if present
        exoPip?.visibility = View.VISIBLE
        exoFullscreen?.visibility = View.VISIBLE
    }

    private fun setupUIInteractions() {
        // When user taps PlayerView, show controller
        binding.playerView.setOnClickListener {
            binding.playerView.showController()
        }

        // When controller is hidden/shown, ensure it is on top
        binding.playerView.setControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                binding.playerView.bringToFront()
            }
        }
    }

    // --------------------
    // Picture-in-Picture (PiP)
    // --------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        // Build aspect ratio from the current player container size
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
        // optional: auto-enter PiP when user presses home. Uncomment if you want auto PiP.
        // if (!isInPipMode) enterPipMode()
        super.onUserLeaveHint()
    }

    // Use single-argument override for broader compatibility
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // hide controls in PiP
            binding.playerView.useController = false
        } else {
            // restore controller after returning from PiP
            binding.playerView.useController = true
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
        }
    }

    // --------------------
    // Fullscreen handling
    // --------------------
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // enter landscape fullscreen
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // optionally hide other UI (toolbar, related list)
            binding.relatedRecyclerView.visibility = View.GONE
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.relatedRecyclerView.visibility = View.VISIBLE
        }
        // update icon if you have different drawables for fullscreen/exit-fullscreen
        exoFullscreen?.setImageResource(
            if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
    }

    override fun onStop() {
        super.onStop()
        // If in PiP, keep the player running. Otherwise release to avoid leaks.
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

    // --------------------
    // Related channels loader (restore your original method)
    // --------------------
    private fun loadRelatedChannels() {
        // This is a placeholder: call your existing logic that populates binding.relatedRecyclerView
        // Example:
        // relatedAdapter = RelatedChannelAdapter(channel.related)
        // binding.relatedRecyclerView.adapter = relatedAdapter
        // binding.relatedRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        //
        // If you already have a loadRelatedChannels() method in your class, call it instead of this stub.
    }

    // Simple helper to hide system UI (keeps previous behavior)
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
