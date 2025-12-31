package com.example.myottapp.player

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import com.example.myottapp.R
import com.example.myottapp.databinding.ActivityChannelPlayerBinding
import com.example.myottapp.models.Channel
import com.example.myottapp.utils.EpgManager
import com.example.myottapp.utils.PermissionUtils
import com.example.myottapp.utils.PreferenceKeys
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class ChannelPlayerActivity : AppCompatActivity(), SessionAvailabilityListener {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_LOGO = "channel_logo"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_EPG_ID = "epg_id"
        const val EXTRA_START_POSITION = "start_position"
        const val EXTRA_EPG_DATA = "epg_data"

        private const val ACTION_MEDIA_CONTROL = "action_media_control"
        private const val EXTRA_CONTROL_TYPE = "extra_control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2

        private const val PIP_ANIMATION_DURATION = 300L
    }

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private var mediaSession: MediaSession? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var epgManager: EpgManager? = null
    private var channel: Channel? = null
    private var isLocked = false
    private var isInPipMode = false
    private var userRequestedPip = false
    private var isLandscape = false
    private var isChannelChanging = AtomicBoolean(false)
    private var currentChannelPosition = 0
    private var channelList: List<Channel> = emptyList()
    private var lastPlaybackPosition = 0L
    private var lastPlaybackState = Player.STATE_IDLE
    private var isPlaybackWhenReady = false
    private var cache: SimpleCache? = null
    private var currentChannelIndex = 0
    private var wasPausedBeforeChange = false
    private var isSeeking = false
    private var volumeBeforeMute = 0f
    private var brightnessBeforeChange = 0f
    private var initialTouchY = 0f
    private var initialVolume = 0f
    private var initialBrightness = 0f
    private var isVerticalGestureVolume = false
    private var isVerticalGestureBrightness = false
    private var isHorizontalGestureSeeking = false
    private var seekStartX = 0f
    private var initialSeekPosition = 0L
    private var currentSeekPosition = 0L
    private var gestureDetector: GestureDetector? = null
    private var doubleTapHandler: Handler? = null
    private var doubleTapRunnable: Runnable? = null
    private var isDoubleTapHandled = false

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY -> {
                    player?.play()
                }
                CONTROL_TYPE_PAUSE -> {
                    player?.pause()
                }
            }
        }
    }

    private val castStateListener = CastStateListener { state ->
        if (state == CastState.NO_DEVICES_AVAILABLE) {
            runOnUiThread {
                binding.castButton.visibility = View.GONE
            }
        } else {
            runOnUiThread {
                binding.castButton.visibility = View.VISIBLE
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    updatePlayPauseIcon(player?.isPlaying == true) // Check isPlaying for UI state
                    binding.progressBar.visibility = View.GONE
                    binding.errorView.visibility = View.GONE
                    updatePipActions() // Update PiP actions based on state
                }
                Player.STATE_BUFFERING -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.errorView.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    binding.progressBar.visibility = View.GONE
                    showEndOfStreamMessage()
                }
                Player.STATE_IDLE -> {
                    // Handle idle state if needed
                }
            }
            lastPlaybackState = playbackState
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.progressBar.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.player_error, error.message)
            Log.e("ChannelPlayerActivity", "Player error: ", error)
            Toast.makeText(this@ChannelPlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
            updatePipActions() // Update PiP actions based on state
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            // Update aspect ratio if needed
            runOnUiThread {
                binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            // Handle track changes if needed
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it == true }
        if (!granted) {
            Toast.makeText(this, "Permissions are required for the player to function correctly", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeViews()
        setupGestures()
        initializePlayer()
        loadChannel()
        setupCast()
        registerReceiver(mediaControlReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
    }

    private fun initializeViews() {
        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.GONE && !isInPipMode && !isLocked) {
                binding.topControls.visibility = View.GONE
                binding.bottomControls.visibility = View.GONE
                binding.channelInfo.visibility = View.GONE
                binding.epgInfo.visibility = View.GONE
                binding.lockButton.visibility = View.GONE
            }
        })

        binding.lockButton.setOnClickListener {
            toggleLock()
        }

        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.pipButton.setOnClickListener {
            userRequestedPip = true
            enterPipMode()
        }

        binding.castButton.setOnClickListener {
            // Cast functionality handled by CastButton in layout
        }

        binding.channelUpButton.setOnClickListener {
            changeChannel(true)
        }

        binding.channelDownButton.setOnClickListener {
            changeChannel(false)
        }

        binding.playPauseButton.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
        }

        binding.volumeUpButton.setOnClickListener {
            adjustVolume(true)
        }

        binding.volumeDownButton.setOnClickListener {
            adjustVolume(false)
        }

        binding.muteButton.setOnClickListener {
            toggleMute()
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                isDoubleTapHandled = true
                handleDoubleTap(e.x)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isDoubleTapHandled) {
                    toggleControlsVisibility()
                } else {
                    isDoubleTapHandled = false
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false

                val playerViewWidth = binding.playerView.measuredWidth.toFloat()
                val playerViewHeight = binding.playerView.measuredHeight.toFloat()
                val xPercent = e1.x / playerViewWidth
                val yPercent = e1.y / playerViewHeight

                if (isHorizontalGestureSeeking) {
                    // Already seeking horizontally
                    val dx = e1.x - e2.x
                    val percentSeek = dx / playerViewWidth
                    val duration = player?.duration ?: 0L
                    if (duration != C.TIME_UNSET) {
                        currentSeekPosition = (initialSeekPosition - (percentSeek * duration)).toLong().coerceIn(0, duration)
                        // Update seek preview UI if available
                        val currentPositionText = formatTime(currentSeekPosition)
                        val durationText = formatTime(duration)
                        // Example: binding.seekPreview.text = "$currentPositionText / $durationText"
                        // Example: binding.playerView.setCustomErrorMessage(currentPositionText) // Or similar preview
                    }
                    return true
                }

                if (isVerticalGestureVolume) {
                    // Already adjusting volume
                    val newY = e2.y
                    val distanceYPercent = (initialTouchY - newY) / playerViewHeight
                    val newVolume = (initialVolume + distanceYPercent).coerceIn(0f, 1f)
                    setVolume(newVolume)
                    updateVolumeUI()
                    return true
                }

                if (isVerticalGestureBrightness) {
                    // Already adjusting brightness
                    val newY = e2.y
                    val distanceYPercent = (initialTouchY - newY) / playerViewHeight
                    val newBrightness = (initialBrightness + distanceYPercent).coerceIn(0.01f, 1f)
                    setBrightness(newBrightness)
                    return true
                }

                // Determine gesture type based on initial touch position
                if (xPercent < 0.25) {
                    // Left side - volume
                    if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX) && kotlin.math.abs(distanceY) > ViewConfiguration.get(this@ChannelPlayerActivity).scaledTouchSlop) {
                        isVerticalGestureVolume = true
                        initialTouchY = e1.y
                        initialVolume = getVolume()
                        return true
                    }
                } else if (xPercent > 0.75) {
                    // Right side - brightness
                    if (kotlin.math.abs(distanceY) > kotlin.math.abs(distanceX) && kotlin.math.abs(distanceY) > ViewConfiguration.get(this@ChannelPlayerActivity).scaledTouchSlop) {
                        isVerticalGestureBrightness = true
                        initialTouchY = e1.y
                        initialBrightness = getBrightness()
                        return true
                    }
                } else {
                    // Center - seeking
                    if (kotlin.math.abs(distanceX) > kotlin.math.abs(distanceY) && kotlin.math.abs(distanceX) > ViewConfiguration.get(this@ChannelPlayerActivity).scaledTouchSlop) {
                        isHorizontalGestureSeeking = true
                        seekStartX = e1.x
                        initialSeekPosition = player?.currentPosition ?: 0L
                        currentSeekPosition = initialSeekPosition
                        return true
                    }
                }

                return false
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP) {
                // End gestures
                if (isHorizontalGestureSeeking) {
                    isHorizontalGestureSeeking = false
                    player?.seekTo(currentSeekPosition)
                    // Hide seek preview if shown
                }
                if (isVerticalGestureVolume) {
                    isVerticalGestureVolume = false
                }
                if (isVerticalGestureBrightness) {
                    isVerticalGestureBrightness = false
                }
            }

            true // Consume the touch event
        }
    }

    private fun handleDoubleTap(x: Float) {
        val playerViewWidth = binding.playerView.measuredWidth
        if (x < playerViewWidth / 2) {
            // Seek backward
            player?.let { p ->
                val newPosition = (p.currentPosition - 15000).coerceAtLeast(0)
                p.seekTo(newPosition)
            }
        } else {
            // Seek forward
            player?.let { p ->
                val newPosition = (p.currentPosition + 15000).coerceAtMost(p.duration)
                p.seekTo(newPosition)
            }
        }
    }

    private fun initializePlayer() {
        val preferExtensionDecoders = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PreferenceKeys.KEY_PREFER_EXTENSION_DECODERS, false)

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("en") // Set default audio language if needed
            )
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MyOTTApp/1.0")
            .setAllowCrossProtocolRedirects(true)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache ?: run { createCache(); cache!! })
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheWriteDataSinkFactory(null) // Disable write to cache on errors

        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = isPlaybackWhenReady
                addListener(playerListener)
            }

        binding.playerView.player = player
        binding.playerView.useController = false // Start with controller hidden
        binding.playerView.hideController() // Ensure it's hidden initially
    }

    private fun createCache() {
        val cacheDir = cacheDir
        cache = SimpleCache(cacheDir, androidx.media3.database.StandaloneDatabaseProvider(this))
    }

    private fun loadChannel() {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        val channelLogo = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val channelUrl = intent.getStringExtra(EXTRA_CHANNEL_URL)
        val epgId = intent.getStringExtra(EXTRA_EPG_ID)
        val startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0)

        if (channelUrl != null) {
            val mediaItem = MediaItem.fromUri(channelUrl)
            player?.setMediaItem(mediaItem, startPosition)
            player?.prepare()

            channel = Channel(
                id = channelId ?: "",
                name = channelName ?: "Unknown Channel",
                logo = channelLogo,
                url = channelUrl,
                epgId = epgId
            )
            updateChannelInfo()
        } else {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.channel_url_missing)
        }
    }

    private fun updateChannelInfo() {
        channel?.let { ch ->
            binding.channelName.text = ch.name
            binding.channelNumber.text = "${currentChannelIndex + 1}"
            // Load logo if needed using an image loading library like Glide
            // Glide.with(this).load(ch.logo).into(binding.channelLogo)

            // Update EPG info if available
            lifecycleScope.launch {
                epgManager?.getCurrentProgram(ch.epgId)?.let { program ->
                    binding.programTitle.text = program.title
                    binding.programTime.text = "${formatTime(program.startTime)} - ${formatTime(program.endTime)}"
                    binding.programDescription.text = program.description
                    binding.epgInfo.visibility = View.VISIBLE
                } ?: run {
                    binding.epgInfo.visibility = View.GONE
                }
            }
        }
    }

    private fun setupCast() {
        try {
            castContext = CastContext.getSharedInstance(this, MoreExecutors.mainThreadExecutor())
            castContext?.registerLifecycleCallbacksBeforeIceCreamSandwich(this)
            castContext?.addCastStateListener(castStateListener)
        } catch (e: Exception) {
            Log.e("ChannelPlayerActivity", "Failed to initialize CastContext", e)
            binding.castButton.visibility = View.GONE
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.playPauseButton.setImageResource(iconRes)
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
            Toast.makeText(this, "Player Locked", Toast.LENGTH_SHORT).show()
        } else {
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            if (!isInPipMode) { // Only show controller if not in PiP
                binding.playerView.useController = true
                binding.playerView.showController()
            }
            Toast.makeText(this, "Player Unlocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        binding.unlockButton.setOnClickListener {
            toggleLock()
        }
    }

    private fun toggleControlsVisibility() {
        if (isLocked) return

        if (binding.topControls.visibility == View.VISIBLE) {
            binding.topControls.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.channelInfo.visibility = View.GONE
            binding.epgInfo.visibility = View.GONE
            binding.lockButton.visibility = View.GONE
            binding.playerView.hideController()
        } else {
            binding.topControls.visibility = View.VISIBLE
            binding.bottomControls.visibility = View.VISIBLE
            binding.channelInfo.visibility = View.VISIBLE
            binding.epgInfo.visibility = View.VISIBLE
            binding.lockButton.visibility = View.VISIBLE
            binding.playerView.showController()
        }
    }

    private fun changeChannel(up: Boolean) {
        if (channelList.isEmpty()) return

        if (player?.isPlaying == true) {
            wasPausedBeforeChange = false
            player?.pause()
        } else {
            wasPausedBeforeChange = true
        }

        if (up) {
            currentChannelIndex = (currentChannelIndex + 1) % channelList.size
        } else {
            currentChannelIndex = if (currentChannelIndex - 1 < 0) channelList.size - 1 else currentChannelIndex - 1
        }

        loadChannelAtIndex(currentChannelIndex)
    }

    private fun loadChannelAtIndex(index: Int) {
        if (index < 0 || index >= channelList.size) return

        val newChannel = channelList[index]
        val mediaItem = MediaItem.fromUri(newChannel.url)
        player?.setMediaItem(mediaItem)
        player?.prepare()

        channel = newChannel
        currentChannelIndex = index
        updateChannelInfo()

        if (!wasPausedBeforeChange) {
            player?.play()
        }
    }

    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        updateVolumeUI()
    }

    private fun updateVolumeUI() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume.toFloat() / maxVolume.toFloat()) * 100
        // Example: Update a volume indicator if you have one
        // binding.volumeIndicator.text = "${volumePercent.toInt()}%"
        binding.muteButton.setImageResource(if (currentVolume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun toggleMute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            // Was muted, unmute to previous level
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeMute.toInt(), 0)
        } else {
            // Was unmuted, mute and save level
            volumeBeforeMute = currentVolume.toFloat()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
        updateVolumeUI()
    }

    private fun setVolume(volume: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
    }

    private fun getVolume(): Float {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        return if (maxVolume > 0) currentVolume / maxVolume else 0f
    }

    private fun setBrightness(brightness: Float) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        brightnessBeforeChange = brightness
    }

    private fun getBrightness(): Float {
        val lp = window.attributes
        return if (lp.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF) {
            // If brightness is set to adaptive/auto, return current system brightness if possible
            // For simplicity, returning 0.5 as a default middle value
            0.5f
        } else {
            lp.screenBrightness
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun showEndOfStreamMessage() {
        runOnUiThread {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.end_of_stream)
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        // 1. Check AppOpsManager (From Reference Code) - ensures permission is actually granted
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        if (android.app.AppOpsManager.MODE_ALLOWED != appOpsManager.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                packageName
            )) {
            val intent = Intent(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                android.net.Uri.fromParts("package", packageName, null)
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback or log if settings intent fails
                Timber.w(e, "Could not open PiP settings")
            }
            return
        }

        player?.let {
            if (!it.isPlaying) it.play()
        }

        // 2. Hide controls explicitly before entering (Fixes UI glitches)
        binding.playerView.useController = false
        binding.playerView.hideController()

        // 3. Calculate Aspect Ratio with Limits (From Reference Code logic)
        // Limits prevent crashes on very wide or very tall videos
        val format = player?.videoFormat
        if (format != null) {
            val width = format.width
            val height = format.height

            if (width > 0 && height > 0) {
                var ratio = Rational(width, height)
                val rationalLimitWide = Rational(239, 100) // 2.39:1
                val rationalLimitTall = Rational(100, 239) // 1:2.39

                if (ratio.toFloat() > rationalLimitWide.toFloat()) {
                    ratio = rationalLimitWide
                } else if (ratio.toFloat() < rationalLimitTall.toFloat()) {
                    ratio = rationalLimitTall
                }

                val builder = PictureInPictureParams.Builder()
                builder.setAspectRatio(ratio)

                // Set initial actions
                updatePipActions(builder)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false) // Use manual control
                    builder.setSeamlessResizeEnabled(true) // Allow smooth resizing
                }

                enterPictureInPictureMode(builder.build())
                isInPipMode = true
            }
        }
    }

    // Adapted from reference updatePictureInPictureActions
    private fun updatePipActions(builder: PictureInPictureParams.Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val actions = ArrayList<RemoteAction>()
                val isPlaying = player?.isPlaying == true

                // Determine icon and title based on state
                val iconId = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                val title = if (isPlaying) "Pause" else "Play"
                val controlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY

                val intent = Intent(ACTION_MEDIA_CONTROL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CONTROL_TYPE, controlType)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    controlType,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                actions.add(
                    RemoteAction(
                        Icon.createWithResource(this, iconId),
                        title,
                        title,
                        pendingIntent
                    )
                )

                // If a builder is passed (during enter), set it there.
                // Otherwise update the active session.
                if (builder != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setAutoEnterEnabled(false) // Ensure manual control
                        builder.setSeamlessResizeEnabled(true) // Ensure smooth resize
                    }
                    builder.setActions(actions)
                } else if (isInPipMode) {
                    val params = PictureInPictureParams.Builder()
                    params.setActions(actions)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        params.setAutoEnterEnabled(false) // Ensure manual control
                        params.setSeamlessResizeEnabled(true) // Ensure smooth resize
                    }
                    setPictureInPictureParams(params.build())
                }
            } catch (e: Exception) {
                // Handle Samsung/Talkback specific crashes mentioned in reference code
                Timber.e(e, "Error updating PiP actions")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPipMode) {
            // ENTERING PIP
            // Ensure controls are hidden and receiver is registered
            binding.playerView.hideController()
            binding.playerView.useController = false

            // Note: The receiver is already registered in onCreate in your original code,
            // so we don't need to re-register it here like the reference code does.
        } else {
            // EXITING PIP

            // 1. Check if we should finish (user closed PiP window via 'X')
            if (!userRequestedPip && lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            userRequestedPip = false

            // 2. Restore Orientation settings
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)

            // 3. Restore UI / Controls based on Lock State
            if (isLocked) {
                binding.playerView.useController = false
                binding.playerView.hideController()
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.playerView.post { binding.playerView.showController() } // Post to ensure view is ready
                // Reset timeout to default if needed, though it's likely set in applyOrientationSettings
                // binding.playerView.controllerShowTimeoutMs = if (isLandscape) 3000 else 5000
            }
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        // This function can be expanded to handle specific settings for portrait/landscape
        // For now, it just serves as a placeholder for future logic or to apply settings on PiP exit
        this.isLandscape = isLandscape
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadChannel() // Reload channel if intent changes
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
        loadChannel()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            player?.playWhenReady = isPlaybackWhenReady
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            isPlaybackWhenReady = player?.playWhenReady ?: false
            player?.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (mediaSession != null) {
            mediaSession?.release()
            mediaSession = null
        }
        if (castContext != null) {
            castContext?.removeCastStateListener(castStateListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.let { exoPlayer ->
            isPlaybackWhenReady = exoPlayer.playWhenReady
            lastPlaybackPosition = exoPlayer.currentPosition
            exoPlayer.stop()
            exoPlayer.release()
        }
        player = null
        unregisterReceiver(mediaControlReceiver)
        cache?.release()
        cache = null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Optional: Trigger PiP when user leaves the activity, if desired
        // enterPipMode()
    }

    override fun onCastSessionAvailable() {
        // Cast session is available
        castPlayer = CastContext.getSharedInstance(this).castPlayer
        // Handle cast player availability if needed
    }

    override fun onCastSessionUnavailable() {
        // Cast session is unavailable
        castPlayer = null
        // Handle cast player unavailability if needed
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) {
                        it.pause()
                    } else {
                        it.play()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                toggleControlsVisibility()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isInPipMode) {
                    // If in PiP, pressing back should not exit the app, just hide controls
                    if (!isLocked) {
                        binding.playerView.hideController()
                    }
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
