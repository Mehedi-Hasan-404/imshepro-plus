package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.media3.common.C
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

    // For video: support multiple selections (checkboxes)
    private var selectedVideos = mutableListOf<TrackUiModel.Video>()
    // For audio/text: single selection (radio buttons)
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null
    private var selectedSpeed: Float = 1.0f

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
        loadTracks()  // Load tracks FIRST
        setupViews()  // Then setup tabs based on available tracks

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

        // CRITICAL: Only add tabs if tracks actually exist
        val hasVideoTracks = videoTracks.isNotEmpty()
        val hasAudioTracks = audioTracks.isNotEmpty()
        val hasTextTracks = textTracks.isNotEmpty()

        Timber.d("Setting up tabs - Video: $hasVideoTracks (${videoTracks.size}), Audio: $hasAudioTracks (${audioTracks.size}), Text: $hasTextTracks (${textTracks.size})")

        if (hasVideoTracks) {
            tabLayout.addTab(tabLayout.newTab().setText("Video"))
            Timber.d("Added Video tab")
        }
        if (hasAudioTracks) {
            tabLayout.addTab(tabLayout.newTab().setText("Audio"))
            Timber.d("Added Audio tab")
        }
        if (hasTextTracks) {
            tabLayout.addTab(tabLayout.newTab().setText("Text"))
            Timber.d("Added Text tab")
        }

        // Always add Speed tab
        tabLayout.addTab(tabLayout.newTab().setText("Speed"))
        Timber.d("Added Speed tab")

        Timber.d("Total tabs created: ${tabLayout.tabCount}")

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val tabText = tab?.text?.toString() ?: return
                Timber.d("Tab selected: $tabText")
                when (tabText) {
                    "Video" -> showVideoTracks()
                    "Audio" -> showAudioTracks()
                    "Text" -> showTextTracks()
                    "Speed" -> showSpeedOptions()
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
            // Load tracks from player
            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks = PlayerTrackMapper.textTracks(player)

            // Check if ANY specific track is selected
            val hasVideoSelection = videoTracks.any { it.isSelected }
            val hasAudioSelection = audioTracks.any { it.isSelected }
            val hasTextSelection = textTracks.any { it.isSelected }
            
            // Video: can have multiple selections (checkboxes)
            selectedVideos.clear()
            if (hasVideoSelection) {
                selectedVideos.addAll(videoTracks.filter { it.isSelected })
            }
            // If no selection, Auto is the default (empty list means Auto)
            
            // Audio/Text: single selection (radio buttons)
            selectedAudio = if (hasAudioSelection) audioTracks.firstOrNull { it.isSelected } else null
            selectedText = if (hasTextSelection) textTracks.firstOrNull { it.isSelected } else null
            
            // Get current playback speed
            selectedSpeed = player.playbackParameters.speed
            
            // Check if tracks are currently disabled
            isVideoNone = false // Video is almost never disabled
            isAudioNone = false // Audio is almost never disabled
            isTextNone = !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)

            Timber.d("Video tracks: ${videoTracks.size}, Audio tracks: ${audioTracks.size}, Text tracks: ${textTracks.size}, Speed: ${selectedSpeed}x")
            Timber.d("Selected - Videos: ${selectedVideos.size}, Audio: ${selectedAudio != null}, Text: ${selectedText != null}")
            Timber.d("isVideoNone: $isVideoNone, isAudioNone: $isAudioNone, isTextNone: $isTextNone")
            
        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun showFirstAvailableTab() {
        Timber.d("Showing first available tab")
        when {
            videoTracks.isNotEmpty() -> {
                Timber.d("Showing Video tab")
                showVideoTracks()
            }
            audioTracks.isNotEmpty() -> {
                Timber.d("Showing Audio tab")
                showAudioTracks()
            }
            textTracks.isNotEmpty() -> {
                Timber.d("Showing Text tab")
                showTextTracks()
            }
            else -> {
                Timber.d("No tracks available, showing Speed tab")
                showSpeedOptions()
            }
        }
    }

    private fun showVideoTracks() {
        if (videoTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Video>()

        // Auto option (selected when no specific tracks are selected)
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -1,
            trackIndex = -1,
            width = 0,
            height = 0,
            bitrate = 0,
            isSelected = selectedVideos.isEmpty() && !isVideoNone
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

        // Actual tracks - mark as selected if in selectedVideos list
        tracksWithOptions.addAll(videoTracks.map { track ->
            track.copy(isSelected = selectedVideos.any { 
                it.groupIndex == track.groupIndex && it.trackIndex == track.trackIndex 
            })
        })

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    // Auto - clear all specific selections
                    selectedVideos.clear()
                    isVideoNone = false
                }
                -2 -> {
                    // None
                    selectedVideos.clear()
                    isVideoNone = true
                }
                else -> {
                    // Specific track - toggle it in the list
                    isVideoNone = false
                    val existingIndex = selectedVideos.indexOfFirst { 
                        it.groupIndex == selected.groupIndex && it.trackIndex == selected.trackIndex 
                    }
                    if (existingIndex >= 0) {
                        // Remove if already selected
                        selectedVideos.removeAt(existingIndex)
                    } else {
                        // Add if not selected
                        selectedVideos.add(selected)
                    }
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
            isSelected = selectedText == null && !isTextNone
        ))

        // None option
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
                    // Auto
                    selectedText = null
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
            // Apply track selections
            // For video: pass the list of selected tracks (or null for Auto)
            val videoToApply = if (selectedVideos.isNotEmpty()) selectedVideos.first() else null
            
            TrackSelectionApplier.apply(
                player = player,
                video = videoToApply,
                audio = selectedAudio,
                text = selectedText,
                disableVideo = isVideoNone,
                disableAudio = isAudioNone,
                disableText = isTextNone
            )

            // Apply playback speed
            player.setPlaybackSpeed(selectedSpeed)

            Timber.d("Applied track selections and speed: ${selectedSpeed}x")
        } catch (e: Exception) {
            Timber.e(e, "Error applying selections")
        }
    }

    private fun showSpeedOptions() {
        val speedOptions = listOf(
            0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f
        )

        val speedModels = speedOptions.map { speed ->
            TrackUiModel.Speed(
                speed = speed,
                isSelected = speed == selectedSpeed
            )
        }

        val adapter = TrackAdapter<TrackUiModel.Speed> { selected ->
            selectedSpeed = selected.speed
            (currentAdapter as? TrackAdapter<TrackUiModel.Speed>)?.updateSelection(selected)
        }

        adapter.submit(speedModels)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }
}

