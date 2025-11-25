package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import timber.log.Timber
import java.util.concurrent.TimeUnit

class ChannelPlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        fun start(context: Context, url: String) {
            val i = Intent(context, ChannelPlayerActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            context.startActivity(i)
        }
    }

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null

    // controller views (we get references from the PlayerView's controller layout)
    private var btnPlayPause: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnPiP: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var seekBar: SeekBar? = null
    private var positionText: TextView? = null
    private var durationText: TextView? = null

    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep screen on while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // prepare player
        initPlayer()

        // wire controller views (they live inside PlayerView controller layout)
        binding.playerView.post {
            bindControllerViews()
            setupControllerListeners()
        }
    }

    private fun initPlayer() {
        if (player != null) return

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build().also { exo ->
                binding.playerView.player = exo
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                if (url.isNotEmpty()) {
                    val mediaItem = MediaItem.fromUri(url)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true
                }
                // update UI periodically
                exo.addListener(object: com.google.android.exoplayer2.Player.Listener {})
            }
    }

    private fun bindControllerViews() {
        val pv = binding.playerView
        // The controller layout is inflated inside PlayerView; find by id
        btnPlayPause = pv.findViewById(R.id.exo_play_pause)
        btnRewind = pv.findViewById(R.id.exo_rewind)
        btnForward = pv.findViewById(R.id.exo_forward)
        btnPiP = pv.findViewById(R.id.exo_pip)
        btnLock = pv.findViewById(R.id.exo_lock)
        seekBar = pv.findViewById(R.id.exo_seek)
        positionText = pv.findViewById(R.id.exo_position)
        durationText = pv.findViewById(R.id.exo_duration)

        // initial icon
        updatePlayPauseIcon()
    }

    private fun setupControllerListeners() {
        btnPlayPause?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
            } else {
                p.play()
            }
            updatePlayPauseIcon()
        }

        btnRewind?.setOnClickListener {
            player?.let {
                val pos = (it.currentPosition - 10000).coerceAtLeast(0)
                it.seekTo(pos)
            }
        }

        btnForward?.setOnClickListener {
            player?.let {
                val pos = (it.currentPosition + 10000).coerceAtMost(it.duration.coerceAtLeast(0))
                it.seekTo(pos)
            }
        }

        btnPiP?.setOnClickListener {
            enterPipMode()
        }

        btnLock?.setOnClickListener {
            locked = !locked
            setLockState(locked)
        }

        // Seekbar minimal wiring
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var userSeeking = false
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val dur = player!!.duration.coerceAtLeast(0)
                    val pos = dur * progress / 100
                    positionText?.text = formatTime(pos)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                val p = player ?: return
                val dur = p.duration.coerceAtLeast(0)
                val pos = dur * (seekBar?.progress ?: 0) / 100
                p.seekTo(pos)
            }
        })

        // Simple periodic UI updater
        binding.playerView.post(object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition
                    val dur = p.duration.coerceAtLeast(0)
                    if (dur > 0) {
                        val percent = (pos * 100 / dur).toInt()
                        seekBar?.progress = percent
                        positionText?.text = formatTime(pos)
                        durationText?.text = formatTime(dur)
                    }
                }
                binding.playerView.postDelayed(this, 700)
            }
        })
    }

    private fun updatePlayPauseIcon() {
        val p = player
        if (p != null && p.isPlaying) {
            btnPlayPause?.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause?.setImageResource(R.drawable.ic_play)
        }
    }

    private fun setLockState(lock: Boolean) {
        // When locked:
        // - hide top and bottom controls (except keep lock icon visible in its original place)
        // - optionally show a center lock icon for a second
        val pv = binding.playerView
        val top = pv.findViewById<View>(R.id.exo_top_bar)
        val bottom = pv.findViewById<View>(R.id.exo_bottom_bar)

        if (lock) {
            top?.visibility = View.GONE
            bottom?.visibility = View.GONE
            // show small center confirmation then hide
            binding.lockCenterIcon.visibility = View.VISIBLE
            binding.lockCenterIcon.postDelayed({ binding.lockCenterIcon.visibility = View.GONE }, 900)
            // update lock icon in top bar (if accessed later)
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
        } else {
            top?.visibility = View.VISIBLE
            bottom?.visibility = View.VISIBLE
            btnLock?.setImageResource(R.drawable.ic_lock_open)
        }
    }

    override fun onUserLeaveHint() {
        // If user leaves, consider entering PiP if supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // optional: auto enter PiP
            // enterPipMode()
        }
        super.onUserLeaveHint()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspect = Rational(binding.playerView.width, binding.playerView.height)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspect)
                    .build()
                enterPictureInPictureMode(params)
            } catch (t: Throwable) {
                Timber.e(t, "PIP failed")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // hide controls when in PiP
        val pv = binding.playerView
        val top = pv.findViewById<View>(R.id.exo_top_bar)
        val bottom = pv.findViewById<View>(R.id.exo_bottom_bar)
        if (isInPictureInPictureMode) {
            top?.visibility = View.GONE
            bottom?.visibility = View.GONE
        } else {
            // restore UI (unless locked)
            if (!locked) {
                top?.visibility = View.VISIBLE
                bottom?.visibility = View.VISIBLE
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If in PiP, player should keep playing; otherwise release to save resources
        if (!isInPictureInPictureMode) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
}
