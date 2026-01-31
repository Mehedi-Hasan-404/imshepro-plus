package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.data.models.LiveEventLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerListener: Player.Listener? = null
    
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()
    private lateinit var linkChipAdapter: LinkChipAdapter

    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnAspectRatio: ImageButton? = null
    private var tvChannelName: TextView? = null

    private var isInPipMode = false
    private var isLocked = false
    private var isMuted = false
    private val skipMs = 10_000L
    private var userRequestedPip = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable {
        binding.unlockButton?.visibility = View.GONE
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_MEDIA_CONTROL -> {
                    val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                    when (controlType) {
                        CONTROL_TYPE_PLAY -> player?.play()
                        CONTROL_TYPE_PAUSE -> player?.pause()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_LIVE_EVENT = "extra_live_event"
        private const val EXTRA_LINK_INDEX = "extra_link_index"
        private const val ACTION_MEDIA_CONTROL = "media_control"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val REQUEST_PIP = 100

        fun startWithChannel(context: Context, channel: Channel, linkIndex: Int = 0) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            })
        }

        fun startWithLiveEvent(context: Context, event: LiveEvent, linkIndex: Int = 0) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_LIVE_EVENT, event as Parcelable)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindow()
        setupPlayer()
        setupAdapters()
        setupUI()
        setupObservers()
        setupBackPress()
        handleIntent()
        
        updatePaddingForNotch()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }
    }

    private fun updatePaddingForNotch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                val displayCutout = insets.displayCutout
                
                val params = binding.playerContainer?.layoutParams as? ConstraintLayout.LayoutParams
                params?.let {
                    if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                        it.setMargins(
                            displayCutout?.safeInsetLeft ?: 0,
                            0,
                            displayCutout?.safeInsetRight ?: 0,
                            0
                        )
                    } else {
                        it.setMargins(0, systemBars.top, 0, 0)
                    }
                    binding.playerContainer?.layoutParams = it
                }
                insets
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val displayCutout = insets.displayCutout
                
                if (displayCutout != null) {
                    when (resources.configuration.orientation) {
                        Configuration.ORIENTATION_LANDSCAPE -> {
                            binding.playerContainer?.setPadding(
                                displayCutout.safeInsetLeft,
                                0,
                                displayCutout.safeInsetRight,
                                0
                            )
                        }
                        else -> {
                            binding.playerContainer?.setPadding(
                                0,
                                displayCutout.safeInsetTop,
                                0,
                                0
                            )
                        }
                    }
                }
                insets
            }
        }
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun setupPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                
                playerListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.loadingProgress?.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                binding.loadingProgress?.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                            }
                            Player.STATE_IDLE -> {
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        binding.loadingProgress?.visibility = View.GONE
                        showError("Playback error: ${error.message}")
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        updateAspectRatio(videoSize)
                    }
                }
                exoPlayer.addListener(playerListener!!)
            }
    }

    private fun updateAspectRatio(videoSize: VideoSize) {
        val videoAspectRatio = if (videoSize.height == 0 || videoSize.width == 0) {
            16f / 9f
        } else {
            (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
        }
    }

    private fun setupAdapters() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            player?.stop()
            viewModel.setChannel(channel)
            playCurrentContent()
        }

        binding.relatedChannelsRecycler?.apply {
            layoutManager = GridLayoutManager(this@PlayerActivity, 2)
            adapter = relatedChannelsAdapter
        }

        linkChipAdapter = LinkChipAdapter { link, index ->
            viewModel.selectLink(index)
            playCurrentContent()
        }

        binding.linksRecycler?.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }
    }

    private fun setupUI() {
        binding.playerView.findViewById<View>(R.id.exo_back)?.let { view ->
            btnBack = view as? ImageButton
            btnBack?.setOnClickListener { finish() }
        }

        binding.playerView.findViewById<View>(R.id.exo_pip)?.let { view ->
            btnPip = view as? ImageButton
            btnPip?.setOnClickListener {
                userRequestedPip = true
                enterPipMode()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                btnPip?.visibility = View.GONE
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_settings)?.let { view ->
            btnSettings = view as? ImageButton
            btnSettings?.setOnClickListener {
                showPlayerSettings()
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_lock)?.let { view ->
            btnLock = view as? ImageButton
            btnLock?.setOnClickListener {
                toggleLock()
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_mute)?.let { view ->
            btnMute = view as? ImageButton
            updateMuteButton()
            btnMute?.setOnClickListener {
                toggleMute()
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_rew)?.let { view ->
            btnRewind = view as? ImageButton
            btnRewind?.setOnClickListener {
                player?.let { 
                    it.seekTo((it.currentPosition - skipMs).coerceAtLeast(0))
                }
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_play_pause)?.let { view ->
            btnPlayPause = view as? ImageButton
        }

        binding.playerView.findViewById<View>(R.id.exo_ffwd)?.let { view ->
            btnForward = view as? ImageButton
            btnForward?.setOnClickListener {
                player?.let { 
                    it.seekTo((it.currentPosition + skipMs).coerceAtMost(it.duration))
                }
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_fullscreen)?.let { view ->
            btnFullscreen = view as? ImageButton
            btnFullscreen?.setOnClickListener {
                toggleFullscreen()
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_aspect_ratio)?.let { view ->
            btnAspectRatio = view as? ImageButton
            btnAspectRatio?.setOnClickListener {
                cycleAspectRatio()
            }
        }

        binding.playerView.findViewById<View>(R.id.exo_channel_name)?.let { view ->
            tvChannelName = view as? TextView
        }

        binding.unlockButton?.setOnClickListener {
            toggleLock()
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.currentChannel.collect { channel ->
                channel?.let {
                    tvChannelName?.text = it.name
                    updateRelatedChannels(it)
                    
                    if (it.links.size > 1) {
                        binding.linksSection?.visibility = View.VISIBLE
                        linkChipAdapter.submitList(it.links.mapIndexed { index, link ->
                            link to index
                        })
                    } else {
                        binding.linksSection?.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.currentLiveEvent.collect { event ->
                event?.let {
                    tvChannelName?.text = it.title
                    
                    if (it.links.isNotEmpty()) {
                        if (it.links.size > 1) {
                            binding.linksSection?.visibility = View.GONE
                        } else {
                            binding.linksSection?.visibility = View.VISIBLE
                            linkChipAdapter.submitList(it.links.mapIndexed { index, link ->
                                LiveEventLink(link, "Link ${index + 1}") to index
                            })
                        }
                    } else {
                        binding.linksSection?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateRelatedChannels(currentChannel: Channel) {
        lifecycleScope.launch {
            viewModel.getRelatedChannels(currentChannel).collect { channels ->
                relatedChannels = channels
                relatedChannelsAdapter.submitList(channels)
                
                binding.relatedChannelsSection?.visibility = if (channels.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLocked) {
                    return
                }
                
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    finish()
                }
            }
        })
    }

    private fun handleIntent() {
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        val liveEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LIVE_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_LIVE_EVENT)
        }

        val linkIndex = intent.getIntExtra(EXTRA_LINK_INDEX, 0)

        when {
            channel != null -> {
                viewModel.setChannel(channel)
                viewModel.selectLink(linkIndex)
                playCurrentContent()
            }
            liveEvent != null -> {
                viewModel.setLiveEvent(liveEvent)
                viewModel.selectLink(linkIndex)
                playCurrentContent()
            }
        }
    }

    private fun playCurrentContent() {
        val url = viewModel.getCurrentStreamUrl()
        
        if (url.isNullOrEmpty()) {
            showError("No valid stream URL")
            return
        }

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    private fun showPlayerSettings() {
        player?.let { exoPlayer ->
            trackSelector?.let { selector ->
                val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog.newInstance()
                dialog.show(supportFragmentManager, "PlayerSettings")
            }
        }
    }

    private fun toggleFullscreen() {
        val isFullscreen = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val parent = binding.playerContainer?.parent as? androidx.constraintlayout.widget.ConstraintLayout
        
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            )
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.setMargins(0, 0, 0, 0)
            
            binding.playerContainer?.layoutParams = params
            binding.playerContainer?.setPadding(0, 0, 0, 0)
            
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            binding.relatedChannelsSection?.visibility = View.GONE
            binding.linksSection?.visibility = View.GONE
        } else {
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                0
            )
            
            params.dimensionRatio = "H,16:9"
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.setMargins(0, 0, 0, 0)
            
            binding.playerContainer?.layoutParams = params
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                binding.root.requestApplyInsets()
            }
            
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 5000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
        
        parent?.requestLayout()
        binding.playerContainer?.requestLayout()
        binding.playerView.requestLayout()
        binding.root.invalidate()
    }

    override fun onResume() {
        super.onResume()
        if (!isInPipMode) {
            player?.play()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode) {
                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    val params = binding.playerContainer?.layoutParams as? ConstraintLayout.LayoutParams
                    params?.let {
                        it.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        it.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        binding.playerContainer?.layoutParams = it
                    }
                    
                    binding.playerView.controllerAutoShow = false
                    binding.playerView.controllerShowTimeoutMs = 5000
                    binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                } else {
                    binding.playerView.controllerAutoShow = false
                    binding.playerView.controllerShowTimeoutMs = 5000
                    binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                
                binding.relatedChannelsSection?.visibility = View.VISIBLE
                binding.linksSection?.visibility = View.VISIBLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipMode) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
        }
        
        playerListener?.let { player?.removeListener(it) }
        player?.release()
        player = null
        trackSelector = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (userRequestedPip) {
            enterPipMode()
            userRequestedPip = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }

        val params = getPipParams()
        try {
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter PiP mode", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPipParams(): PictureInPictureParams {
        val aspectRatio = Rational(16, 9)
        val visibleRect = Rect()
        binding.playerView.getGlobalVisibleRect(visibleRect)

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setSourceRectHint(visibleRect)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }

        val actions = mutableListOf<RemoteAction>()
        
        val isPlaying = player?.isPlaying == true
        val playPauseIcon = if (isPlaying) {
            Icon.createWithResource(this, R.drawable.ic_pause)
        } else {
            Icon.createWithResource(this, R.drawable.ic_play)
        }
        
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_PIP,
            Intent(ACTION_MEDIA_CONTROL).apply {
                putExtra(EXTRA_CONTROL_TYPE, if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val playPauseAction = RemoteAction(
            playPauseIcon,
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        )
        
        actions.add(playPauseAction)
        builder.setActions(actions)

        return builder.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            binding.playerView.useController = false
            binding.relatedChannelsSection?.visibility = View.GONE
            binding.linksSection?.visibility = View.GONE
        } else {
            binding.playerView.useController = true
            
            val currentChannel = viewModel.currentChannel.value
            val currentEvent = viewModel.currentLiveEvent.value
            
            if (currentChannel != null) {
                if (currentChannel.links.size > 1) {
                    binding.linksSection?.visibility = View.VISIBLE
                }
                
                if (relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection?.visibility = View.VISIBLE
                }
            } else if (currentEvent != null) {
                if (currentEvent.links.size > 1) {
                    binding.linksSection?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteButton()
        }
    }

    private fun updateMuteButton() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun cycleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            binding.playerView.useController = false
            binding.lockOverlay?.visibility = View.VISIBLE
            binding.unlockButton?.visibility = View.VISIBLE
            
            binding.playerView.hideController()
        } else {
            binding.playerView.useController = true
            binding.lockOverlay?.visibility = View.GONE
            
            binding.unlockButton?.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            mainHandler.postDelayed(hideUnlockButtonRunnable, 2000)
        }
    }

    private fun showError(message: String) {
        binding.errorText?.apply {
            text = message
            visibility = View.VISIBLE
            
            postDelayed({
                visibility = View.GONE
            }, 3000)
        }
    }
}
