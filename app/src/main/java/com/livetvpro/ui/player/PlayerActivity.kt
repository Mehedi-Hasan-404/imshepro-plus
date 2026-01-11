package com.livetvpro.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.player.settings.PlayerSettingsDialog
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    
    private var currentChannel: Channel? = null
    private var currentEvent: LiveEvent? = null
    
    private lateinit var relatedAdapter: RelatedChannelAdapter
    private var isFullscreen = false

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }

        fun start(context: Context, event: LiveEvent) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsContainer.visibility = View.GONE

        if (intent.hasExtra(EXTRA_CHANNEL)) {
            currentChannel = intent.getParcelableExtra(EXTRA_CHANNEL)
        } else if (intent.hasExtra(EXTRA_EVENT)) {
            currentEvent = intent.getParcelableExtra(EXTRA_EVENT)
        }

        if (currentChannel == null && currentEvent == null) {
            finish()
            return
        }

        setupUI()
        setupRelatedList()
        
        if (currentChannel != null) {
            initializePlayerForChannel(currentChannel!!)
            viewModel.loadRelatedChannels(currentChannel!!.categoryId, currentChannel!!.id)
        } else if (currentEvent != null) {
            initializePlayerForEvent(currentEvent!!)
            viewModel.loadRelatedEvents(currentEvent!!.id)
        }
    }

    private fun setupUI() {
        val titleView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        
        if (currentChannel != null) {
            titleView?.text = currentChannel!!.name
        } else if (currentEvent != null) {
            titleView?.text = currentEvent!!.title.ifEmpty { "${currentEvent!!.team1Name} vs ${currentEvent!!.team2Name}" }
        }

        binding.playerView.findViewById<View>(
            resources.getIdentifier("exo_back", "id", packageName)
        )?.setOnClickListener { finish() }

        binding.playerView.findViewById<View>(
            resources.getIdentifier("exo_fullscreen", "id", packageName)
        )?.setOnClickListener { toggleFullscreen() }

        binding.playerView.findViewById<View>(
            resources.getIdentifier("exo_settings", "id", packageName)
        )?.setOnClickListener { showSettings() }
    }

    private fun setupRelatedList() {
        relatedAdapter = RelatedChannelAdapter { channelItem ->
            switchContent(channelItem)
        }
        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedAdapter
        }

        viewModel.relatedItems.observe(this) { list ->
            relatedAdapter.submitList(list)
            if (list.isNullOrEmpty()) {
                binding.relatedChannelsContainer.visibility = View.GONE
            } else {
                binding.relatedChannelsContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun switchContent(item: Channel) {
        player?.release()
        
        if (currentEvent != null) {
            val titleView = binding.playerView.findViewById<android.widget.TextView>(
                resources.getIdentifier("exo_channel_name", "id", packageName)
            )
            titleView?.text = item.name
            initializePlayer(item.streamUrl)
            viewModel.loadRelatedEvents(item.id)
        } else {
            currentChannel = item
            val titleView = binding.playerView.findViewById<android.widget.TextView>(
                resources.getIdentifier("exo_channel_name", "id", packageName)
            )
            titleView?.text = item.name
            initializePlayerForChannel(item)
            viewModel.loadRelatedChannels(item.categoryId, item.id)
        }
    }

    private fun initializePlayerForChannel(channel: Channel) {
        initializePlayer(channel.streamUrl)
    }

    private fun initializePlayerForEvent(event: LiveEvent) {
        val url = event.links.firstOrNull()?.url ?: ""
        if (url.isNotEmpty()) {
            initializePlayer(url)
        } else {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = "No stream link available"
        }
    }

    private fun initializePlayer(url: String) {
        try {
            val parts = url.split("|")
            val streamUrl = parts[0]
            val headers = mutableMapOf<String, String>()
            var userAgent = "LiveTVPro/1.0"
            var drmScheme: String? = null
            var drmLicense: String? = null
            var drmKey: String? = null

            if (parts.size > 1) {
                for (i in 1 until parts.size) {
                    val p = parts[i]
                    val eq = p.indexOf('=')
                    if (eq > 0) {
                        val key = p.substring(0, eq)
                        val value = p.substring(eq + 1)
                        when (key) {
                            "User-Agent" -> userAgent = value
                            "drmScheme" -> drmScheme = value
                            "drmLicense" -> {
                                if (value.contains(":") && !value.startsWith("http")) {
                                    val dParts = value.split(":", limit = 2)
                                    if (dParts.size == 2) {
                                        drmLicense = dParts[0]
                                        drmKey = dParts[1]
                                    }
                                } else {
                                    drmLicense = value
                                }
                            }
                            else -> headers[key] = value
                        }
                    }
                }
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(headers)

            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

            binding.playerView.player = player
            binding.playerView.keepScreenOn = true

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)

            if (drmScheme != null) {
                val uuid = getDrmUuid(drmScheme)
                if (uuid != null) {
                    val drmConfig = MediaItem.DrmConfiguration.Builder(uuid)
                    if (drmLicense != null) {
                        drmConfig.setLicenseUri(drmLicense)
                    }
                    mediaItemBuilder.setDrmConfiguration(drmConfig.build())
                }
            }

            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            player?.playWhenReady = true

            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            binding.loadingProgress.visibility = View.VISIBLE
                            binding.errorView.visibility = View.GONE
                        }
                        Player.STATE_READY -> {
                            binding.loadingProgress.visibility = View.GONE
                            binding.errorView.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            binding.loadingProgress.visibility = View.GONE
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    binding.loadingProgress.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    binding.errorText.text = "Playback Error"
                }
            })

        } catch (e: Exception) {
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = "Error initializing player"
        }
    }

    private fun getDrmUuid(scheme: String): UUID? {
        return when (scheme.lowercase()) {
            "widevine" -> C.WIDEVINE_UUID
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> C.WIDEVINE_UUID
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val icon = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_fullscreen", "id", packageName)
        )
        
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.relatedChannelsContainer.visibility = View.GONE
            icon?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (relatedAdapter.currentList.isNotEmpty()) {
                binding.relatedChannelsContainer.visibility = View.VISIBLE
            }
            icon?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun showSettings() {
        player?.let {
            PlayerSettingsDialog(this, it).show()
        }
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
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}


