package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
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
    private var selectedSpeed: Float = 1.0f

    // State flags for "None" and "Auto"
    private var isVideoNone = false
    private var isAudioNone = false
    private var isTextNone = false
    
    // If these are false and selectedX is null, it implies "Auto"
    private var isVideoAuto = true
    private var isAudioAuto = true
    private var isTextAuto = true

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

    private fun loadTracks() {
        try {
            // 1. Get current parameters to determine if we are in Auto, Manual, or Disabled mode
            val parameters = player.trackSelectionParameters
            
            // --- VIDEO STATE DETECTION ---
            val disabledVideo = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
            val videoOverrides = player.currentTracks.groups.filter { 
                it.type == C.TRACK_TYPE_VIDEO && parameters.overrides.containsKey(it.mediaTrackGroup) 
            }
            
            isVideoNone = disabledVideo
            // It is Auto if NOT disabled AND NO overrides exist
            isVideoAuto = !disabledVideo && videoOverrides.isEmpty()

            // --- AUDIO STATE DETECTION ---
            val disabledAudio = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
            val audioOverrides = player.currentTracks.groups.filter { 
                it.type == C.TRACK_TYPE_AUDIO && parameters.overrides.containsKey(it.mediaTrackGroup) 
            }
            
            isAudioNone = disabledAudio
            isAudioAuto = !disabledAudio && audioOverrides.isEmpty()

            // --- TEXT STATE DETECTION ---
            val disabledText = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val textOverrides = player.currentTracks.groups.filter { 
                it.type == C.TRACK_TYPE_TEXT && parameters.overrides.containsKey(it.mediaTrackGroup) 
            }
            
            isTextNone = disabledText
            isTextAuto = !disabledText && textOverrides.isEmpty()


            // 2. Load Raw Tracks from Mapper
            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks = PlayerTrackMapper.textTracks(player)


            // 3. Set Selected Objects based on State
            // If we are in Auto or None, selectedX should be null. 
            // If we are Manual (not Auto and not None), we find the track that is currently active.
            
            selectedVideo = if (!isVideoAuto && !isVideoNone) {
                videoTracks.firstOrNull { it.isSelected }
            } else null

            selectedAudio = if (!isAudioAuto && !isAudioNone) {
                audioTracks.firstOrNull { it.isSelected }
            } else null
            
            selectedText = if (!isTextAuto && !isTextNone) {
                // Filter out the "Off" dummy track if it exists from the mapper
                textTracks.filter { it.language != "Off" }.firstOrNull { it.isSelected }
            } else null

            // 4. Get Speed
            selectedSpeed = player.playbackParameters.speed
            
            Timber.d("State -> Video Auto: $isVideoAuto, None: $isVideoNone | Audio Auto: $isAudioAuto")

        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun setupViews() {
        recyclerView.layoutManager = LinearLayoutManager(context)

        if (videoTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Video"))
        if (audioTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Audio"))
        if (textTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Text"))
        tabLayout.addTab(tabLayout.newTab().setText("Speed"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.text?.toString()) {
                    "Video" -> showVideoTracks()
                    "Audio" -> showAudioTracks()
                    "Text" -> showTextTracks()
                    "Speed" -> showSpeedOptions()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnCancel.setOnClickListener { dismiss() }
        btnApply.setOnClickListener {
            applySelections()
            dismiss()
        }
    }

    private fun showFirstAvailableTab() {
        when {
            videoTracks.isNotEmpty() -> showVideoTracks()
            audioTracks.isNotEmpty() -> showAudioTracks()
            textTracks.isNotEmpty() -> showTextTracks()
            else -> showSpeedOptions()
        }
    }

    private fun showVideoTracks() {
        if (videoTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Video>()

        // 1. Auto Option (RED RADIO BUTTON)
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -1,
            trackIndex = -1,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoAuto,
            isRadio = true // Explicitly Radio
        ))

        // 2. None Option (RED RADIO BUTTON)
        tracksWithOptions.add(TrackUiModel.Video(
            groupIndex = -2,
            trackIndex = -2,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoNone,
            isRadio = true // Explicitly Radio
        ))

        // 3. Specific Qualities (RED CHECKBOXES)
        // Note: PlayerTrackMapper sets isRadio = false by default for Video, which is what we want here
        tracksWithOptions.addAll(videoTracks.map { track ->
            track.copy(isSelected = !isVideoAuto && !isVideoNone && track.isSelected)
        })

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            when (selected.groupIndex) {
                -1 -> { // Auto selected
                    selectedVideo = null
                    isVideoNone = false
                    isVideoAuto = true
                }
                -2 -> { // None selected
                    selectedVideo = null
                    isVideoNone = true
                    isVideoAuto = false
                }
                else -> { // Specific track selected
                    selectedVideo = selected
                    isVideoNone = false
                    isVideoAuto = false
                }
            }
            
            // Re-build the list to update UI states (Radio vs Checkbox logic logic)
            val updatedList = mutableListOf<TrackUiModel.Video>()
            
            // Re-add Auto
            updatedList.add(tracksWithOptions[0].copy(isSelected = isVideoAuto))
            // Re-add None
            updatedList.add(tracksWithOptions[1].copy(isSelected = isVideoNone))
            
            // Re-add tracks
            // If Auto or None is selected, uncheck all specific tracks
            // If specific track is selected, check only that one (mimic radio behavior for quality selection usually)
            // Or allow multi-selection? Standard ExoPlayer behavior for video is usually single selection of a quality or Auto.
            // Let's treat quality selection as Single Selection for simplicity/stability.
            
            videoTracks.forEach { track ->
                val isMe = track.groupIndex == selected.groupIndex && track.trackIndex == selected.trackIndex
                val shouldBeSelected = if (isVideoAuto || isVideoNone) false else isMe
                updatedList.add(track.copy(isSelected = shouldBeSelected))
            }
            
            (currentAdapter as? TrackAdapter<TrackUiModel.Video>)?.submit(updatedList)
        }

        adapter.submit(tracksWithOptions)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showAudioTracks() {
        if (audioTracks.isEmpty()) return

        val tracksWithOptions = mutableListOf<TrackUiModel.Audio>()

        tracksWithOptions.add(TrackUiModel.Audio(
            groupIndex = -1, trackIndex = -1, language = "Auto", channels = 0, bitrate = 0,
            isSelected = isAudioAuto
        ))

        tracksWithOptions.add(TrackUiModel.Audio(
            groupIndex = -2, trackIndex = -2, language = "None", channels = 0, bitrate = 0,
            isSelected = isAudioNone
        ))

        tracksWithOptions.addAll(audioTracks.map { track ->
            track.copy(isSelected = !isAudioAuto && !isAudioNone && track.isSelected)
        })

        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            when (selected.groupIndex) {
                -1 -> { selectedAudio = null; isAudioNone = false; isAudioAuto = true }
                -2 -> { selectedAudio = null; isAudioNone = true; isAudioAuto = false }
                else -> { selectedAudio = selected; isAudioNone = false; isAudioAuto = false }
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

        tracksWithOptions.add(TrackUiModel.Text(
            groupIndex = -1, trackIndex = -1, language = "Auto",
            isSelected = isTextAuto
        ))

        tracksWithOptions.add(TrackUiModel.Text(
            groupIndex = -2, trackIndex = -2, language = "None",
            isSelected = isTextNone
        ))

        tracksWithOptions.addAll(textTracks.filter { it.language != "Off" }.map { track ->
            track.copy(isSelected = !isTextAuto && !isTextNone && track.isSelected)
        })

        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            when (selected.groupIndex) {
                -1 -> { selectedText = null; isTextNone = false; isTextAuto = true }
                -2 -> { selectedText = null; isTextNone = true; isTextAuto = false }
                else -> { selectedText = selected; isTextNone = false; isTextAuto = false }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Text>)?.updateSelection(selected)
        }

        adapter.submit(tracksWithOptions)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showSpeedOptions() {
        val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val speedModels = speedOptions.map { speed ->
            TrackUiModel.Speed(speed = speed, isSelected = speed == selectedSpeed)
        }

        val adapter = TrackAdapter<TrackUiModel.Speed> { selected ->
            selectedSpeed = selected.speed
            (currentAdapter as? TrackAdapter<TrackUiModel.Speed>)?.updateSelection(selected)
        }

        adapter.submit(speedModels)
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
            player.setPlaybackSpeed(selectedSpeed)
        } catch (e: Exception) {
            Timber.e(e, "Error applying selections")
        }
    }
}

