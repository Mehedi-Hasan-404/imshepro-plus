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

    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null
    private var selectedSpeed: Float = 1.0f

    private var isVideoNone = false
    private var isAudioNone = false
    private var isTextNone = false

    private var videoTracks = emptyList<TrackUiModel.Video>()
    private var audioTracks = emptyList<TrackUiModel.Audio>()
    private var textTracks = emptyList<TrackUiModel.Text>() // Includes "Off" from mapper

    private var currentAdapter: TrackAdapter<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_player_settings)

        val displayMetrics = context.resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.85).toInt()

        window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        initViews()
        loadTracks()
        setupTabs()
        showFirstAvailableTab()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        tabLayout = findViewById(R.id.tabLayout)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dismiss() }

        btnCancel = findViewById(R.id.btnCancel)
        btnApply = findViewById(R.id.btnApply)
    }

    private fun loadTracks() {
        try {
            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks = PlayerTrackMapper.textTracks(player) // Includes "Off" entry

            // Determine current selections
            selectedVideo = videoTracks.firstOrNull { it.isSelected }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }

            // For text: if "Off" is selected or no text track is active → treat as None
            val offTrackSelected = textTracks.firstOrNull { it.language == "Off" && it.isSelected } != null
            val anyTextTrackSelected = textTracks.any { it.language != "Off" && it.isSelected }

            selectedText = if (anyTextTrackSelected) {
                textTracks.firstOrNull { it.language != "Off" && it.isSelected }
            } else {
                null
            }

            isTextNone = !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) || offTrackSelected

            // Video/Audio "None" is rare, but we keep flags for consistency
            isVideoNone = false // Usually not supported
            isAudioNone = false // Usually not supported

            // Current speed
            selectedSpeed = player.playbackParameters.speed

            Timber.d(
                "Tracks loaded - Video: ${videoTracks.size}, Audio: ${audioTracks.size}, " +
                        "Text: ${textTracks.size} (isTextNone: $isTextNone), Speed: ${selectedSpeed}x"
            )
            Timber.d(
                "Selected - Video: ${selectedVideo != null}, Audio: ${selectedAudio != null}, " +
                        "Text: ${selectedText != null}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun setupTabs() {
        recyclerView.layoutManager = LinearLayoutManager(context)

        val hasVideo = videoTracks.isNotEmpty()
        val hasAudio = audioTracks.isNotEmpty()
        val hasText = textTracks.isNotEmpty()

        if (hasVideo) tabLayout.addTab(tabLayout.newTab().setText("Video"))
        if (hasAudio) tabLayout.addTab(tabLayout.newTab().setText("Audio"))
        if (hasText) tabLayout.addTab(tabLayout.newTab().setText("Text"))
        tabLayout.addTab(tabLayout.newTab().setText("Speed")) // Always show speed

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.text?.toString()) {
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

    private fun showFirstAvailableTab() {
        when {
            videoTracks.isNotEmpty() -> tabLayout.getTabAt(0)?.select() ?: showSpeedOptions()
            audioTracks.isNotEmpty() -> tabLayout.getTabAt(0)?.select() ?: showSpeedOptions()
            textTracks.isNotEmpty() -> tabLayout.getTabAt(0)?.select() ?: showSpeedOptions()
            else -> showSpeedOptions()
        }
    }

    private fun showVideoTracks() {
        val items = mutableListOf<TrackUiModel.Video>()

        // Auto
        items.add(
            TrackUiModel.Video(
                groupIndex = -1,
                trackIndex = -1,
                width = -1,
                height = -1,
                bitrate = -1,
                isSelected = selectedVideo == null && !isVideoNone,
                label = "Auto" // Optional: custom label if your UI model supports it
            )
        )

        // None (rare for video)
        items.add(
            TrackUiModel.Video(
                groupIndex = -2,
                trackIndex = -2,
                width = -1,
                height = -1,
                bitrate = -1,
                isSelected = isVideoNone,
                label = "None"
            )
        )

        items.addAll(videoTracks.map { it.copy(isSelected = it == selectedVideo) })

        val adapter = TrackAdapter<TrackUiModel.Video>(isRadio = false) { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    selectedVideo = null
                    isVideoNone = false
                }
                -2 -> {
                    selectedVideo = null
                    isVideoNone = true
                }
                else -> {
                    selectedVideo = selected
                    isVideoNone = false
                }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Video>)?.updateSelection(selected)
        }

        adapter.submit(items)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showAudioTracks() {
        val items = mutableListOf<TrackUiModel.Audio>()

        items.add(
            TrackUiModel.Audio(
                groupIndex = -1,
                trackIndex = -1,
                language = "Auto",
                channels = -1,
                bitrate = -1,
                isSelected = selectedAudio == null && !isAudioNone
            )
        )

        items.add(
            TrackUiModel.Audio(
                groupIndex = -2,
                trackIndex = -2,
                language = "None",
                channels = -1,
                bitrate = -1,
                isSelected = isAudioNone
            )
        )

        items.addAll(audioTracks.map { it.copy(isSelected = it == selectedAudio) })

        val adapter = TrackAdapter<TrackUiModel.Audio>(isRadio = true) { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    selectedAudio = null
                    isAudioNone = false
                }
                -2 -> {
                    selectedAudio = null
                    isAudioNone = true
                }
                else -> {
                    selectedAudio = selected
                    isAudioNone = false
                }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Audio>)?.updateSelection(selected)
        }

        adapter.submit(items)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showTextTracks() {
        val items = mutableListOf<TrackUiModel.Text>()

        // Auto
        items.add(
            TrackUiModel.Text(
                groupIndex = -1,
                trackIndex = -1,
                language = "Auto",
                isSelected = selectedText == null && !isTextNone
            )
        )

        // None
        items.add(
            TrackUiModel.Text(
                groupIndex = -2,
                trackIndex = -2,
                language = "None",
                isSelected = isTextNone
            )
        )

        // Real tracks only (exclude the "Off" placeholder from mapper)
        items.addAll(
            textTracks
                .filter { it.language != "Off" }
                .map { it.copy(isSelected = it == selectedText) }
        )

        val adapter = TrackAdapter<TrackUiModel.Text>(isRadio = true) { selected ->
            when (selected.groupIndex) {
                -1 -> {
                    selectedText = null
                    isTextNone = false
                }
                -2 -> {
                    selectedText = null
                    isTextNone = true
                }
                else -> {
                    selectedText = selected
                    isTextNone = false
                }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Text>)?.updateSelection(selected)
        }

        adapter.submit(items)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showSpeedOptions() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val items = speeds.map { speed ->
            TrackUiModel.Speed(speed = speed, isSelected = speed == selectedSpeed)
        }

        val adapter = TrackAdapter<TrackUiModel.Speed>(isRadio = true) { selected ->
            selectedSpeed = selected.speed
            (currentAdapter as? TrackAdapter<TrackUiModel.Speed>)?.updateSelection(selected)
        }

        adapter.submit(items)
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

            Timber.d("Settings applied - Speed: ${selectedSpeed}x, TextNone: $isTextNone")
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply player settings")
        }
    }
}
