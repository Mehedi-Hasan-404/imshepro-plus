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
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.player.dialogs.LinkSelectionDialog
import com.livetvpro.ui.player.settings.PlayerSettingsDialog
import com.livetvpro.utils.PlayerOrientation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PlayerActivity - Main video player activity
 * 
 * ORIENTATION BEHAVIOR:
 * - Supports both portrait and landscape modes
 * - In portrait: Shows player in 16:9 ratio with related channels below
 * - In landscape: Switches to LandscapePlayerActivity for fullscreen experience
 * 
 * CONFIGURATION CHANGE HANDLING:
 * - Handles orientation changes in onConfigurationChanged()
 * - Switches to LandscapePlayerActivity when rotating to landscape
 * - Maintains playback position during activity switch
 */
@AndroidEntryPoint
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentEvent: LiveEvent? = null
    private var selectedLinkIndex: Int = 0

    private lateinit var pipHelper: PipHelper
    private var isControlsLocked = false
    private var isInPipMode = false

    private lateinit var linkChipAdapter: LinkChipAdapter
    private lateinit var relatedChannelAdapter: RelatedChannelAdapter

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_LINK_INDEX = "extra_link_index"

        /**
         * Start player with a channel
         */
        fun startWithChannel(
            context: Context,
            channel: Channel,
            linkIndex: Int = 0
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            }
            context.startActivity(intent)
        }

        /**
         * Start player with an event
         */
        fun startWithEvent(
            context: Context,
            event: LiveEvent,
            linkIndex: Int = 0
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow user rotation (portrait, landscape - no reverse orientations)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Allow free rotation - we'll handle orientation changes in onConfigurationChanged
        // If currently in landscape, switch to LandscapePlayerActivity
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switchToLandscapePlayer(isInitialLaunch = true)
            return
        }

        // Setup for portrait mode
        setupFullscreenMode()
        pipHelper = PipHelper(this, binding.playerView) { player }
        setupBackPressHandler()
        setupPlayerControls()
        setupRecyclerViews()
        extractIntentData()
        initializePlayer()
        observeViewModel()
    }

    /**
     * Handle configuration changes (orientation, screen size, etc.)
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val newOrientation = newConfig.orientation
        Log.d(TAG, "Configuration changed: orientation = $newOrientation")

        // If device rotates to landscape, switch to LandscapePlayerActivity
        if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "Switching to LandscapePlayerActivity")
            switchToLandscapePlayer(isInitialLaunch = false)
        } else {
            // Stay in portrait - update UI if needed
            updateLayoutForOrientation(newOrientation)
        }
    }

    /**
     * Switch to LandscapePlayerActivity
     * @param isInitialLaunch: true if switching during onCreate, false if during orientation change
     */
    private fun switchToLandscapePlayer(isInitialLaunch: Boolean) {
        val currentPosition = player?.currentPosition ?: 0L
        val isPlaying = player?.isPlaying ?: false

        // Prepare intent for LandscapePlayerActivity
        val intent = Intent(this, LandscapePlayerActivity::class.java).apply {
            currentChannel?.let { putExtra("extra_channel", it) }
            currentEvent?.let { putExtra("extra_event", it) }
            putExtra("extra_link_index", selectedLinkIndex)
            
            if (!isInitialLaunch) {
                // Only pass playback position if switching during playback
                putExtra("extra_playback_position", currentPosition)
                putExtra("extra_is_playing", isPlaying)
            }
            
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Release player before switching (only if not initial launch)
        if (!isInitialLaunch) {
            releasePlayer()
        }

        // Start LandscapePlayerActivity and finish this one
        startActivity(intent)
        finish()
        
        // Smooth transition
        overridePendingTransition(0, 0)
    }

    private fun updateLayoutForOrientation(orientation: Int) {
        // Update UI elements based on orientation
        // In portrait mode, we want to show related channels below player
        when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                // Show related channels in portrait
                viewModel.relatedItems.value?.let { channels ->
                    if (channels.isNotEmpty()) {
                        binding.relatedChannelsSection?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupFullscreenMode() {
        // Make status bar transparent
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.apply {
            // In portrait, we might want to show system bars
            show(WindowInsetsCompat.Type.systemBars())
        }

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set navigation bar color
        window.navigationBarColor = Color.BLACK
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isInPipMode) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }

                if (isControlsLocked) {
                    toggleControlsLock()
                    return
                }

                finish()
            }
        })
    }

    private fun setupPlayerControls() {
        binding.unlockButton?.setOnClickListener {
            toggleControlsLock()
        }
    }

    private fun toggleControlsLock() {
        isControlsLocked = !isControlsLocked
        
        if (isControlsLocked) {
            binding.playerView.hideController()
            binding.playerView.useController = false
            binding.lockOverlay?.visibility = View.VISIBLE
            binding.unlockButton?.visibility = View.VISIBLE
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay?.visibility = View.GONE
            binding.unlockButton?.visibility = View.GONE
        }
    }

    private fun setupRecyclerViews() {
        linkChipAdapter = LinkChipAdapter { link, index ->
            selectedLinkIndex = index
            currentChannel?.let {
                val modifiedChannel = it.copy(streamUrl = link.url)
                playStream(modifiedChannel.streamUrl)
            }
        }

        binding.linksRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }

        relatedChannelAdapter = RelatedChannelAdapter { channel ->
            currentChannel = channel
            selectedLinkIndex = 0
            channel.links.firstOrNull()?.let { link ->
                playStream(link.url)
            }
            viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        }

        binding.relatedChannelsRecycler?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelAdapter
        }
    }

    private fun extractIntentData() {
        intent?.let { intent ->
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

            // Handle playback position if switching from LandscapePlayerActivity
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                updatePipParams()
                            }
                        }
                    })

                    currentChannel?.let { channel ->
                        channel.links.firstOrNull()?.let { link ->
                            playStream(link.url)
                        }
                        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
                        
                        channel.links?.let { links ->
                            if (links.size > 1) {
                                linkChipAdapter.submitList(links, selectedLinkIndex)
                                binding.linksSection?.visibility = View.VISIBLE
                            }
                        }
                    }

                    currentEvent?.let { event ->
                        event.links.firstOrNull()?.let { link ->
                            playStream(link.url)
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
        viewModel.relatedItems.observe(this) { channels ->
            if (channels.isNotEmpty()) {
                relatedChannelAdapter.submitList(channels)
                binding.relatedChannelsSection?.visibility = View.VISIBLE
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (player?.isPlaying == true) {
            pipHelper.enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            binding.linksSection?.visibility = View.GONE
            binding.relatedChannelsSection?.visibility = View.GONE
        } else {
            currentChannel?.links?.let { links ->
                if (links.size > 1) {
                    binding.linksSection?.visibility = View.VISIBLE
                }
            }
            viewModel.relatedItems.value?.let { channels ->
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
