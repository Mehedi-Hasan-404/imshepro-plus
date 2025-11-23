package com.livetvpro.ui.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import com.livetvpro.databinding.ActivityEventPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class EventPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventPlayerBinding
    private var player: ExoPlayer? = null
    private val viewModel: EventPlayerViewModel by viewModels()

    companion object {
        private const val EXTRA_EVENT_ID = "extra_event_id"
        fun start(context: Context, eventId: String) {
            val i = Intent(context, EventPlayerActivity::class.java)
            i.putExtra(EXTRA_EVENT_ID, eventId)
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: run {
            finish(); return
        }

        viewModel.loadEvent(eventId)

        observe()
    }

    private fun observe() {
        viewModel.event.observe(this) { event ->
            if (event == null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = "Event not found"
                return@observe
            }

            binding.eventTitle.text = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }

            // choose first link
            val link = event.links.firstOrNull()?.url ?: ""
            if (link.isBlank()) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = "No playable link available"
                return@observe
            }

            initializePlayer(link)
        }

        viewModel.isLoading.observe(this) { binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE }
        viewModel.error.observe(this) {
            binding.errorView.visibility = if (it != null) View.VISIBLE else View.GONE
            binding.errorText.text = it ?: ""
        }
    }

    private fun initializePlayer(url: String) {
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultHttpDataSource.Factory()
                        .setUserAgent("LiveTVPro/1.0")
                        .setConnectTimeoutMs(30000)
                        .setReadTimeoutMs(30000)
                    )
            )
            .build().also { exo ->
                binding.playerView.player = exo
                val mediaItem = MediaItem.fromUri(url)
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true
                Timber.d("Event player prepared for $url")
            }
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
