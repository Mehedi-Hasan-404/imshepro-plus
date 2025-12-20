package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.livetvpro.R
import timber.log.Timber

class PlayerSettingsDialog(
    context: Context,
    private val player: ExoPlayer
) : Dialog(context) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnApply: MaterialButton

    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null

    private var videoTracks = listOf<TrackUiModel.Video>()
    private var audioTracks = listOf<TrackUiModel.Audio>()
    private var textTracks = listOf<TrackUiModel.Text>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_player_settings)

        // Set dialog size - Fixed width and wrap height
        val displayMetrics = context.resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.85).toInt()
        
        window?.setLayout(
            dialogWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        initViews()
        setupViews()
        loadTracks()
        
        // Show video tab by default
        showVideoTracks()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        tabLayout = findViewById(R.id.tabLayout)

        val btnCloseImage: ImageButton = findViewById(R.id.btnClose)
        btnCloseImage.setOnClickListener { dismiss() }

        btnCancel = findViewById(R.id.btnCancel)
        btnApply = findViewById(R.id.btnApply)
    }

    private fun setupViews() {
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Setup tabs with "Video", "Audio", "Text"
        tabLayout.addTab(tabLayout.newTab().setText("Video"))
        tabLayout.addTab(tabLayout.newTab().setText("Audio"))
        
        // Only add Text tab if text tracks are available
        if (textTracks.isNotEmpty()) {
            tabLayout.addTab(tabLayout.newTab().setText("Text"))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showVideoTracks()
                    1 -> showAudioTracks()
                    2 -> showTextTracks()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        btnCancel.setOnClickListener { dismiss() }

        btnApply.setOnClickListener {
            applySelections()
            dismiss()
        }
    }

    private fun loadTracks() {
        try {
            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks = PlayerTrackMapper.textTracks(player)

            selectedVideo = videoTracks.firstOrNull { it.isSelected }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }
            selectedText = textTracks.firstOrNull { it.isSelected }

            Timber.d(
                "Loaded ${videoTracks.size} video, " +
                        "${audioTracks.size} audio, " +
                        "${textTracks.size} text tracks"
            )
            
            // Update tabs after loading tracks
            if (textTracks.isEmpty() && tabLayout.tabCount > 2) {
                tabLayout.removeTabAt(2)
            } else if (textTracks.isNotEmpty() && tabLayout.tabCount < 3) {
                tabLayout.addTab(tabLayout.newTab().setText("Text"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun showVideoTracks() {
        if (videoTracks.isEmpty()) {
            Timber.w("No video tracks available")
            return
        }

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            selectedVideo = selected
            showVideoTracks()
        }

        adapter.submit(
            videoTracks.map { track ->
                track.copy(isSelected = track == selectedVideo)
            }
        )

        recyclerView.adapter = adapter
    }

    private fun showAudioTracks() {
        if (audioTracks.isEmpty()) {
            Timber.w("No audio tracks available")
            return
        }

        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            selectedAudio = selected
            showAudioTracks()
        }

        adapter.submit(
            audioTracks.map { track ->
                track.copy(isSelected = track == selectedAudio)
            }
        )

        recyclerView.adapter = adapter
    }

    private fun showTextTracks() {
        if (textTracks.isEmpty()) {
            Timber.w("No text tracks available")
            return
        }

        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            selectedText = selected
            showTextTracks()
        }

        adapter.submit(
            textTracks.map { track ->
                track.copy(isSelected = track == selectedText)
            }
        )

        recyclerView.adapter = adapter
    }

    private fun applySelections() {
        try {
            TrackSelectionApplier.apply(
                player = player,
                video = selectedVideo,
                audio = selectedAudio,
                text = selectedText
            )
            Timber.d("Applied track selections successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error applying track selections")
        }
    }
}
