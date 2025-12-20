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
    
    private var currentAdapter: TrackAdapter<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_player_settings)

        val displayMetrics = context.resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.85).toInt()
        
        window?.setLayout(
            dialogWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        initViews()
        loadTracks()
        setupViews()
        
        // Show first available tab
        showFirstAvailableTab()
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

        // Only add tabs for available content
        if (videoTracks.isNotEmpty()) {
            tabLayout.addTab(tabLayout.newTab().setText("Video"))
        }
        if (audioTracks.isNotEmpty()) {
            tabLayout.addTab(tabLayout.newTab().setText("Audio"))
        }
        if (textTracks.isNotEmpty()) {
            tabLayout.addTab(tabLayout.newTab().setText("Text"))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val tabText = tab?.text?.toString() ?: return
                when (tabText) {
                    "Video" -> showVideoTracks()
                    "Audio" -> showAudioTracks()
                    "Text" -> showTextTracks()
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

            Timber.d("Loaded ${videoTracks.size} video, ${audioTracks.size} audio, ${textTracks.size} text tracks")
        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun showFirstAvailableTab() {
        when {
            videoTracks.isNotEmpty() -> showVideoTracks()
            audioTracks.isNotEmpty() -> showAudioTracks()
            textTracks.isNotEmpty() -> showTextTracks()
        }
    }

    private fun showVideoTracks() {
        if (videoTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Video>()
        
        // Auto option
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -1,
            trackIndex = -1,
            width = 0,
            height = 0,
            bitrate = 0,
            isSelected = selectedVideo == null
        ))
        
        // None option
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -2,
            trackIndex = -2,
            width = 0,
            height = 0,
            bitrate = 0,
            isSelected = false
        ))
        
        // Actual tracks
        tracksWithOptions.addAll(videoTracks.map { track ->
            track.copy(isSelected = track == selectedVideo)
        })

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            selectedVideo = when (selected.groupIndex) {
                -1 -> null  // Auto
                -2 -> null  // None (could be handled differently if needed)
                else -> selected
            }
            // Update selection without recreating adapter
            (currentAdapter as? TrackAdapter<TrackUiModel.Video>)?.updateSelection(selected)
        }

        adapter.submit(tracksWithOptions)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showAudioTracks() {
        if (audioTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Audio>()
        
        // Auto option
        tracksWithOptions.add(TrackUiModel.Audio(
            groupIndex = -1,
            trackIndex = -1,
            language = "Auto",
            channels = 0,
            bitrate = 0,
            isSelected = selectedAudio == null
        ))
        
        // None option
        tracksWithOptions.add(TrackUiModel.Audio(
            groupIndex = -2,
            trackIndex = -2,
            language = "None",
            channels = 0,
            bitrate = 0,
            isSelected = false
        ))
        
        // Actual tracks
        tracksWithOptions.addAll(audioTracks.map { track ->
            track.copy(isSelected = track == selectedAudio)
        })

        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            selectedAudio = when (selected.groupIndex) {
                -1 -> null  // Auto
                -2 -> null  // None
                else -> selected
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Audio>)?.updateSelection(selected)
        }

        adapter.submit(tracksWithOptions)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showTextTracks() {
        if (textTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Text>()
        
        // Auto option
        tracksWithOptions.add(TrackUiModel.Text(
            groupIndex = -1,
            trackIndex = -1,
            language = "Auto",
            isSelected = false
        ))
        
        // Actual tracks (including Off option from mapper)
        tracksWithOptions.addAll(textTracks.map { track ->
            track.copy(isSelected = track == selectedText)
        })

        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            selectedText = when {
                selected.groupIndex == -1 -> null  // Auto
                selected.language == "Off" -> selected  // Off
                else -> selected
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Text>)?.updateSelection(selected)
        }

        adapter.submit(tracksWithOptions)
        recyclerView.adapter = adapter
        currentAdapter = adapter
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
