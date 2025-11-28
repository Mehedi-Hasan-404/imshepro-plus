package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import timber.log.Timber

@UnstableApi
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

    private var isLocked = false
    private var isFullscreen = false
    private var isMuted = false

    // PiP action identifiers
    private val ACTION_PIP_PLAY = "com.livetvpro.pip.PLAY"
    private val ACTION_PIP_PAUSE = "com.livetvpro.pip.PAUSE"

    // BroadcastReceiver to handle PiP actions
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY -> player?.play()
                ACTION_PIP_PAUSE -> player?.pause()
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun start(context: Context, channel: Channel) {
            try {
                val i = Intent(context, ChannelPlayerActivity::class.java).apply {
                    putExtra(EXTRA_CHANNEL, channel)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start ChannelPlayerActivity")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep screen on while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // receive channel from intent safely
        val ch = intent?.getParcelableExtra<Channel>(EXTRA_CHANNEL)
        if (ch == null) {
            Timber.e("Channel extra missing; finishing")
            finish()
            return
        }
        channel = ch

        // register PiP action receiver
        registerReceiver(pipActionReceiver, IntentFilter().apply {
            addAction(ACTION_PIP_PLAY); addAction(ACTION_PIP_PAUSE)
        })

        setupControllerRefsAndActions()
        setupRelatedRecycler()
    }

    override fun onStart() {
        super.onStart()
        if (!validateChannel()) return
        initializePlayerSafe()
    }

    override fun onStop() {
        super.onStop()
        // Do not release if in PiP; otherwise release to free resources
        if (!isInPictureInPictureMode) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
        releasePlayer()
    }

    private fun setupControllerRefsAndActions() {
        // back button
        binding.playerView.findViewById<View?>(R.id.exo_back)?.setOnClickListener {
            if (isFullscreen) exitFullscreen() else finish()
        }

        // PiP button
        val pipBtn = binding.playerView.findViewById<View?>(R.id.exo_pip)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            pipBtn?.visibility = View.VISIBLE
            pipBtn?.setOnClickListener { enterPipMode() }
        } else {
            pipBtn?.visibility = View.GONE
        }

        // Mute toggle
        binding.playerView.findViewById<View?>(R.id.exo_mute)?.setOnClickListener {
            toggleMute()
        }

        // Lock / unlock
        binding.playerView.findViewById<View?>(R.id.exo_lock)?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            binding.unlockButton.visibility = View.VISIBLE
        }
        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
        }

        // play/pause, rewind, forward, fullscreen (safe null-handling)
        binding.playerView.findViewById<View?>(R.id.exo_play_pause)?.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        binding.playerView.findViewById<View?>(R.id.exo_rewind)?.setOnClickListener { safeSeekBy(-10_000L) }
        binding.playerView.findViewById<View?>(R.id.exo_forward)?.setOnClickListener { safeSeekBy(10_000L) }
        binding.playerView.findViewById<View?>(R.id.exo_fullscreen)?.setOnClickListener { toggleFullscreen() }

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            initializePlayerSafe()
        }
    }

    private fun setupRelatedRecycler() {
        // Safe placeholder: your adapter setup — keep existing implementation
        // E.g. binding.relatedChannelsRecycler.adapter = relatedAdapter
    }

    private fun validateChannel(): Boolean {
        if (channel.name.isNullOrBlank()) {
            showError("Invalid channel")
            return false
        }
        val url = channel.streamUrl ?: ""
        if (url.isBlank()) {
            showError("Stream URL missing")
            return false
        }
        return true
    }

    private fun initializePlayerSafe() {
        if (player != null) {
            binding.playerView.player = player
            return
        }

        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("LiveTVPro/1.0")
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .also { exo ->
                    binding.playerView.player = exo
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    val url = channel.streamUrl ?: ""
                    if (url.isNotBlank()) {
                        try {
                            val mediaItem = MediaItem.fromUri(Uri.parse(url))
                            exo.setMediaItem(mediaItem)
                            exo.prepare()
                            exo.playWhenReady = true
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to set media item")
                            showError("Unable to play stream")
                        }
                    } else {
                        showError("Empty stream URL")
                    }

                    exo.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            // optional: adapt aspect ratio if needed
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_ENDED -> binding.progressBar.visibility = View.GONE
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Timber.e(error, "Playback error")
                            binding.errorText.text = error.message ?: "Playback error"
                            binding.errorView.visibility = View.VISIBLE
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // update PiP actions while in PiP
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                updatePipActions(isPlaying)
                            }
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "initializePlayerSafe failed")
            showError("Player init failed")
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (ignored: Exception) { }
        player = null
        binding.playerView.player = null
    }

    private fun safeSeekBy(deltaMs: Long) {
        player?.let {
            val target = (it.currentPosition + deltaMs).coerceAtLeast(0L)
            it.seekTo(target)
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            val icon = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            binding.playerView.findViewById<View?>(R.id.exo_mute)?.let { v ->
                // If it's an ImageButton, update image resource; safe cast omitted to keep compile flexible
            }
        }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding.relatedChannelsSection.visibility = View.GONE
        binding.playerView.findViewById<View?>(R.id.exo_fullscreen)?.let { /* update icon if desired */ }
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsSection.visibility = View.VISIBLE
        binding.playerView.findViewById<View?>(R.id.exo_fullscreen)?.let { /* update icon if desired */ }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    // -------------------------
    // Picture-in-Picture support
    // -------------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        try {
            // Hide overlay controllers / scrims before snapshot
            val controllerRoot = binding.playerView.findViewById<View?>(R.id.exo_controller_root)
            controllerRoot?.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE

            binding.playerView.useController = false
            binding.playerView.hideController()

            // Give UI time to update so snapshot excludes overlays
            binding.playerView.postDelayed({
                try {
                    val sourceRect = Rect()
                    binding.playerView.getGlobalVisibleRect(sourceRect)

                    val vw = player?.videoSize?.width ?: binding.playerView.width.takeIf { it > 0 } ?: 16
                    val vh = player?.videoSize?.height ?: binding.playerView.height.takeIf { it > 0 } ?: 9
                    val ratio = if (vw > 0 && vh > 0) Rational(vw, vh) else Rational(16, 9)

                    val builder = PictureInPictureParams.Builder()
                        .setAspectRatio(ratio)
                        .setSourceRectHint(sourceRect)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        builder.setActions(createPipActions(player?.isPlaying == true))
                    }

                    enterPictureInPictureMode(builder.build())
                } catch (inner: Exception) {
                    Timber.e(inner, "enterPipMode: inner failure")
                    controllerRoot?.visibility = View.VISIBLE
                    binding.playerView.useController = true
                }
            }, 120L)
        } catch (e: Exception) {
            Timber.e(e, "enterPipMode failed")
            binding.playerView.useController = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipActions(isPlaying: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()

        val playPauseActionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(if (isPlaying) ACTION_PIP_PAUSE else ACTION_PIP_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val remoteIcon = Icon.createWithResource(this, iconRes)
        val label = if (isPlaying) getString(R.string.pip_pause) else getString(R.string.pip_play)

        actions.add(RemoteAction(remoteIcon, label, label, playPauseActionIntent))

        return actions
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipActions(isPlaying: Boolean) {
        try {
            val sourceRect = Rect()
            binding.playerView.getGlobalVisibleRect(sourceRect)

            val vw = player?.videoSize?.width ?: binding.playerView.width.takeIf { it > 0 } ?: 16
            val vh = player?.videoSize?.height ?: binding.playerView.height.takeIf { it > 0 } ?: 9
            val ratio = if (vw > 0 && vh > 0) Rational(vw, vh) else Rational(16, 9)

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setSourceRectHint(sourceRect)
                .setActions(createPipActions(isPlaying))
                .build()

            setPictureInPictureParams(params)
        } catch (e: Exception) {
            Timber.w(e, "updatePipActions failed")
        }
    }

    override fun onUserLeaveHint() {
        // optional: auto-enter PiP when user leaves the app
        if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPipMode()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        val controllerRoot = binding.playerView.findViewById<View?>(R.id.exo_controller_root)
        if (isInPictureInPictureMode) {
            controllerRoot?.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.playerView.useController = false
            binding.playerView.hideController()
        } else {
            controllerRoot?.visibility = if (isLocked) View.GONE else View.VISIBLE
            binding.relatedChannelsSection.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            binding.lockOverlay.visibility = if (isLocked) View.VISIBLE else View.GONE
            binding.playerView.useController = !isLocked
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBackPressed() {
        if (!isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPipMode()
        } else {
            super.onBackPressed()
        }
    }

    // Optional: public API to switch channel without re-creating activity
    fun switchChannel(newChannel: Channel) {
        runOnUiThread {
            try {
                if (newChannel.streamUrl.isNullOrBlank()) {
                    showError("Stream unavailable")
                    return@runOnUiThread
                }

                player?.let {
                    it.stop()
                    it.clearMediaItems()
                }

                channel = newChannel
                binding.playerView.findViewById<View?>(R.id.exo_channel_name)?.let { v ->
                    // If it's a TextView, set text. Use safe cast if needed in your codebase.
                }

                try {
                    val mediaItem = MediaItem.fromUri(Uri.parse(newChannel.streamUrl))
                    if (player == null) initializePlayerSafe()
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                    binding.errorView.visibility = View.GONE
                } catch (e: Exception) {
                    Timber.e(e, "switchChannel: setMediaItem failed")
                    showError("Failed to load channel")
                }
            } catch (e: Exception) {
                Timber.e(e, "switchChannel failed")
                showError("Cannot switch channel")
            }
        }
    }
}
