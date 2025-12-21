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
    
    private var isVideoNone = false
    private var isAudioNone = false
    private var isTextNone = true  // Default to None for subtitles

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

        // If no tabs available, show error
        if (tabLayout.tabCount == 0) {
            Timber.e("No tracks available!")
            dismiss()
            return
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

            // Check current selections
            selectedVideo = videoTracks.firstOrNull { it.isSelected }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }
            selectedText = textTracks.firstOrNull { it.isSelected }
            
            // Check if text is currently disabled
            isTextNone = !player.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_TEXT)

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
            isSelected = selectedVideo == null && !isVideoNone
        ))
        
        // None option
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -2,
            trackIndex = -2,
            width = 0,
            height = 0,
            bitrate = 0,
            isSelected = isVideoNone
        ))
        
        // Actual tracks
        tracksWithOptions.addAll(videoTracks.map { track ->
            track.copy(isSelected = track == selectedVideo && !isVideoNone)
        })

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    // Auto
                    selectedVideo = null
                    isVideoNone = false
                }
                -2 -> {
                    // None
                    selectedVideo = null
                    isVideoNone = true
                }
                else -> {
                    // Specific track
                    selectedVideo = selected
                    isVideoNone = false
                }
            }
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
            isSelected = selectedAudio == null && !isAudioNone
        ))
        
        // None option
        tracksWithOptions.add(TrackUiModel.Audio(
            groupIndex = -2,
            trackIndex = -2,
            language = "None",
            channels = 0,
            bitrate = 0,
            isSelected = isAudioNone
        ))
        
        // Actual tracks
        tracksWithOptions.addAll(audioTracks.map { track ->
            track.copy(isSelected = track == selectedAudio && !isAudioNone)
        })

        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    // Auto
                    selectedAudio = null
                    isAudioNone = false
                }
                -2 -> {
                    // None
                    selectedAudio = null
                    isAudioNone = true
                }
                else -> {
                    // Specific track
                    selectedAudio = selected
                    isAudioNone = false
                }
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
            isSelected = selectedText != null && !isTextNone
        ))
        
        // None option (replaces "Off")
        tracksWithOptions.add(TrackUiModel.Text(
            groupIndex = -2,
            trackIndex = -2,
            language = "None",
            isSelected = isTextNone
        ))
        
        // Actual tracks (skip the old "Off" option from mapper)
        tracksWithOptions.addAll(textTracks.filter { it.language != "Off" }.map { track ->
            track.copy(isSelected = track == selectedText && !isTextNone)
        })

        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    // Auto - select first available subtitle
                    selectedText = textTracks.firstOrNull { it.groupIndex != null }
                    isTextNone = false
                }
                -2 -> {
                    // None
                    selectedText = null
                    isTextNone = true
                }
                else -> {
                    // Specific track
                    selectedText = selected
                    isTextNone = false
                }
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
                text = selectedText,
                disableVideo = isVideoNone,
                disableAudio = isAudioNone,
                disableText = isTextNone
            )
            Timber.d("Applied track selections successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error applying track selections")
        }
    }
}
