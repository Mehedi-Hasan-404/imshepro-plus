package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityLandscapePlayerBinding
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.player.dialogs.LinkSelectionDialog
import com.livetvpro.ui.player.settings.PlayerSettingsDialog
import com.livetvpro.utils.PlayerOrientation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LandscapePlayerActivity - Fullscreen landscape video player
 * 
 * PURPOSE:
 * - Dedicated landscape/fullscreen player for x86 devices and tablets
 * - Handles configuration changes without activity recreation
 * - Switches to PlayerActivity when device rotates to portrait
 * 
 * USAGE SCENARIOS:
 * 1. x86 devices (emulators, Chromebooks, Android TV) use this directly
 * 2. Starting a channel in portrait → rotating to landscape → switches to this activity
 * 3. Starting in landscape → stays in this activity → rotating to portrait → switches to PlayerActivity
 * 
 * CONFIGURATION CHANGE HANDLING:
 * - Handles orientation changes in onConfigurationChanged()
 * - No activity recreation on rotation
 * - Seamless playback during orientation changes
 */
@AndroidEntryPoint
@UnstableApi
class LandscapePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandscapePlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentEvent: LiveEvent? = null
    private var selectedLinkIndex: Int = 0

    // REMOVED: private lateinit var pipHelper: PipHelper
    private var isControlsLocked = false
    private var isInPipMode = false

    private lateinit var linkChipAdapter: LinkChipAdapter
    private lateinit var relatedChannelAdapter: RelatedChannelAdapter

    companion object {
        private const val TAG = "LandscapePlayer"
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_LINK_INDEX = "extra_link_index"

        /**
         * Start landscape player with a channel
         */
        fun startWithChannel(
            context: Context,
            channel: Channel,
            linkIndex: Int = 0
        ) {
            val intent = Intent(context, LandscapePlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
                // For x86 devices or when starting from landscape
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        /**
         * Start landscape player with an event
         */
        fun startWithEvent(
            context: Context,
            event: LiveEvent,
            linkIndex: Int = 0
        ) {
            val intent = Intent(context, LandscapePlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Allow user rotation (portrait, landscape - no reverse orientations)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        binding = ActivityLandscapePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable fullscreen immersive mode
        setupFullscreenMode()

        // REMOVED: Initialize PiP helper
        // pipHelper = PipHelper(this)

        // Handle back press
        setupBackPressHandler()

        // Setup UI components
        setupPlayerControls()
        setupRecyclerViews()

        // Extract data from intent
        extractIntentData()

        // Initialize player
        initializePlayer()

        // Observe ViewModel
        observeViewModel()
    }

    /**
     * Handle configuration changes (orientation, screen size, etc.)
     * This is called instead of recreating the activity thanks to configChanges in manifest
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val newOrientation = newConfig.orientation
        Log.d(TAG, "Configuration changed: orientation = $newOrientation")

        // If device rotates to portrait, switch to PlayerActivity
        if (newOrientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.d(TAG, "Switching to PlayerActivity (portrait mode)")
            switchToPlayerActivity()
        } else {
            // Stay in landscape - update UI if needed
            setupFullscreenMode()
        }
    }

    /**
     * Switch to regular PlayerActivity when rotating to portrait
     */
    private fun switchToPlayerActivity() {
        val currentPosition = player?.currentPosition ?: 0L
        val isPlaying = player?.isPlaying ?: false

        // Prepare intent for PlayerActivity
        val intent = Intent(this, PlayerActivity::class.java).apply {
            currentChannel?.let { putExtra("extra_channel", it) }
            currentEvent?.let { putExtra("extra_event", it) }
            putExtra("extra_link_index", selectedLinkIndex)
            putExtra("extra_playback_position", currentPosition)
            putExtra("extra_is_playing", isPlaying)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Release player before switching
        releasePlayer()

        // Start PlayerActivity and finish this one
        startActivity(intent)
        finish()
        
        // Smooth transition
        overridePendingTransition(0, 0)
    }

    private fun setupFullscreenMode() {
        // Make the activity fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set navigation bar color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = Color.BLACK
            window.statusBarColor = Color.BLACK
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Release player and finish activity
                releasePlayer()
                finish()
            }
        })
    }

    private fun setupPlayerControls() {
        // The actual player controls are in the exo_modern_player_controls.xml
        // which is set via controller_layout_id in the PlayerView
        
        // Lock/unlock button would be handled in the custom controls layout
        binding.unlockButton?.setOnClickListener {
            isControlsLocked = false
            binding.unlockButton?.visibility = View.GONE
        }

        // Settings button
        binding.playerView.findViewById<View>(R.id.exo_settings)?.setOnClickListener {
            player?.let { exoPlayer ->
                PlayerSettingsDialog(this, exoPlayer).show()
            }
        }
    }

    private fun setupRecyclerViews() {
        // Setup link chip adapter
        linkChipAdapter = LinkChipAdapter { link, index ->
            selectedLinkIndex = index
            playStream(link.url)
        }

        binding.linksRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@LandscapePlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }

        // Setup related channels adapter
        relatedChannelAdapter = RelatedChannelAdapter { channel ->
            // Switch to new channel
            currentChannel = channel
            selectedLinkIndex = 0
            playStream(channel.streamUrl)
            
            // FIXED: Added channel.id parameter
            viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        }

        binding.relatedChannelsRecycler?.apply {
            layoutManager = LinearLayoutManager(this@LandscapePlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelAdapter
        }
    }

    private fun extractIntentData() {
        intent?.let {
            // Check for channel data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentChannel = intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
                currentEvent = intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                currentChannel = intent.getParcelableExtra(EXTRA_CHANNEL)
                @Suppress("DEPRECATION")
                currentEvent = intent.getParcelableExtra(EXTRA_EVENT)
            }

            selectedLinkIndex = intent.getIntExtra(EXTRA_LINK_INDEX, 0)

            // Handle playback position if switching from PlayerActivity
            val playbackPosition = intent.getLongExtra("extra_playback_position", 0L)
            if (playbackPosition > 0) {
                // Will be used when initializing player
            }
        }
    }

    private fun initializePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(this)
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer

                    // Setup player listeners
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar?.visibility = View.VISIBLE
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar?.visibility = View.GONE
                                    binding.errorView?.visibility = View.GONE
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar?.visibility = View.GONE
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            binding.progressBar?.visibility = View.GONE
                            binding.errorView?.visibility = View.VISIBLE
                            binding.errorText?.text = "Failed to load stream: ${error.message}"
                            Log.e(TAG, "Playback error", error)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // Update PiP params when playback state changes
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                updatePipParams()
                            }
                        }
                    })

                    // Start playback
                    currentChannel?.let { channel ->
                        playStream(channel.streamUrl)
                        
                        // FIXED: Added channel.id parameter
                        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
                        
                        // Show links if multiple available
                        channel.links?.let { links ->
                            if (links.size > 1) {
                                // Convert ChannelLink to LiveEventLink for the adapter
                                val eventLinks = links.map { channelLink ->
                                    com.livetvpro.data.models.LiveEventLink(
                                        label = channelLink.quality,
                                        url = channelLink.url
                                    )
                                }
                                linkChipAdapter.submitList(eventLinks)
                                binding.linksSection?.visibility = View.VISIBLE
                            }
                        }
                    }

                    currentEvent?.let { event ->
                        // Play the first available link
                        event.links.firstOrNull()?.let { link ->
                            playStream(link.url)
                            // Submit all links to the adapter
                            if (event.links.size > 1) {
                                linkChipAdapter.submitList(event.links)
                                binding.linksSection?.visibility = View.VISIBLE
                            }
                        }
                    }

                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
        }
    }

    private fun playStream(streamUrl: String) {
        player?.let { exoPlayer ->
            binding.progressBar?.visibility = View.VISIBLE
            binding.errorView?.visibility = View.GONE

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun observeViewModel() {
        // Observe related channels
        viewModel.relatedChannels.observe(this) { channels ->
            if (channels.isNotEmpty()) {
                relatedChannelAdapter.submitList(channels)
                binding.relatedChannelsSection?.visibility = View.VISIBLE
            }
        }

        viewModel.relatedChannelsLoading.observe(this) { isLoading ->
            binding.relatedLoadingProgress?.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            setPictureInPictureParams(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Enter PiP mode when user presses home
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (player?.isPlaying == true) {
            // FIXED: Removed pipHelper call, using built-in PiP functionality
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // Hide UI in PiP mode
            binding.linksSection?.visibility = View.GONE
            binding.relatedChannelsSection?.visibility = View.GONE
        } else {
            // Restore UI when exiting PiP
            currentChannel?.links?.let { links ->
                if (links.size > 1) {
                    binding.linksSection?.visibility = View.VISIBLE
                }
            }
            viewModel.relatedChannels.value?.let { channels ->
                if (channels.isNotEmpty()) {
                    binding.relatedChannelsSection?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            exoPlayer.release()
        }
        player = null
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
