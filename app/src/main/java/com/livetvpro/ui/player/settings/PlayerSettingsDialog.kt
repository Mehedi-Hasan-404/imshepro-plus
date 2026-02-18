package com.livetvpro.ui.player.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.activity.ComponentDialog
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
) : ComponentDialog(context) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnApply: MaterialButton

    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null
    private var selectedSpeed: Float = 1.0f

    private var selectedVideoQualities = mutableSetOf<TrackUiModel.Video>()

    private var isVideoNone = false
    private var isAudioNone = false
    private var isTextNone = false

    private var isVideoAuto = true
    private var isAudioAuto = true
    private var isTextAuto = true

    private var videoTracks = listOf<TrackUiModel.Video>()
    private var audioTracks = listOf<TrackUiModel.Audio>()
    private var textTracks = listOf<TrackUiModel.Text>()

    private var currentAdapter: TrackAdapter<*>? = null
    private var tabListenerEnabled = false

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
        setupButtons()
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
            val parameters = player.trackSelectionParameters

            val disabledVideo = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
            val videoOverrides = player.currentTracks.groups.filter {
                it.type == C.TRACK_TYPE_VIDEO && parameters.overrides.containsKey(it.mediaTrackGroup)
            }
            isVideoNone = disabledVideo
            isVideoAuto = !disabledVideo && videoOverrides.isEmpty()

            val disabledAudio = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
            val audioOverrides = player.currentTracks.groups.filter {
                it.type == C.TRACK_TYPE_AUDIO && parameters.overrides.containsKey(it.mediaTrackGroup)
            }
            isAudioNone = disabledAudio
            isAudioAuto = !disabledAudio && audioOverrides.isEmpty()

            val disabledText = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val textOverrides = player.currentTracks.groups.filter {
                it.type == C.TRACK_TYPE_TEXT && parameters.overrides.containsKey(it.mediaTrackGroup)
            }
            isTextNone = disabledText
            isTextAuto = !disabledText && textOverrides.isEmpty()

            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks = PlayerTrackMapper.textTracks(player)

            selectedVideoQualities.clear()
            if (!isVideoAuto && !isVideoNone) {
                selectedVideoQualities.addAll(videoTracks.filter { it.isSelected })
            }

            selectedAudio = if (!isAudioAuto && !isAudioNone) audioTracks.firstOrNull { it.isSelected } else null
            selectedText = if (!isTextAuto && !isTextNone) textTracks.filter { it.language != "Off" }.firstOrNull { it.isSelected } else null
            selectedSpeed = player.playbackParameters.speed

        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun setupTabs() {
        tabListenerEnabled = false

        if (videoTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Video"))
        if (audioTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Audio"))
        if (textTracks.isNotEmpty()) tabLayout.addTab(tabLayout.newTab().setText("Text"))
        tabLayout.addTab(tabLayout.newTab().setText("Speed"))

        tabListenerEnabled = true

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (!tabListenerEnabled) return
                when (tab?.text?.toString()) {
                    "Video" -> showVideoTracks()
                    "Audio" -> showAudioTracks()
                    "Text"  -> showTextTracks()
                    "Speed" -> showSpeedOptions()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupButtons() {
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
            textTracks.isNotEmpty()  -> showTextTracks()
            else                     -> showSpeedOptions()
        }
    }

    private fun showVideoTracks() {
        if (videoTracks.isEmpty()) return

        val list = mutableListOf<TrackUiModel.Video>()

        list.add(TrackUiModel.Video(
            groupIndex = -1, trackIndex = -1,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoAuto, isRadio = true
        ))
        list.add(TrackUiModel.Video(
            groupIndex = -2, trackIndex = -2,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoNone, isRadio = true
        ))

        val useRadio = videoTracks.size == 1
        list.addAll(videoTracks.map { track ->
            val isChecked = selectedVideoQualities.any {
                it.groupIndex == track.groupIndex && it.trackIndex == track.trackIndex
            }
            track.copy(isSelected = !isVideoAuto && !isVideoNone && isChecked, isRadio = useRadio)
        })

        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            when (selected.groupIndex) {
                -1 -> { selectedVideoQualities.clear(); isVideoAuto = true; isVideoNone = false }
                -2 -> { selectedVideoQualities.clear(); isVideoAuto = false; isVideoNone = true }
                else -> {
                    isVideoAuto = false
                    isVideoNone = false
                    val existing = selectedVideoQualities.find {
                        it.groupIndex == selected.groupIndex && it.trackIndex == selected.trackIndex
                    }
                    if (existing != null) selectedVideoQualities.remove(existing)
                    else selectedVideoQualities.add(selected)
                    if (selectedVideoQualities.isEmpty()) isVideoAuto = true
                }
            }
            refreshVideoList()
        }

        adapter.submit(list)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun refreshVideoList() {
        val list = mutableListOf<TrackUiModel.Video>()

        list.add(TrackUiModel.Video(
            groupIndex = -1, trackIndex = -1,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoAuto, isRadio = true
        ))
        list.add(TrackUiModel.Video(
            groupIndex = -2, trackIndex = -2,
            width = 0, height = 0, bitrate = 0,
            isSelected = isVideoNone, isRadio = true
        ))

        val useRadio = videoTracks.size == 1
        list.addAll(videoTracks.map { track ->
            val isChecked = selectedVideoQualities.any {
                it.groupIndex == track.groupIndex && it.trackIndex == track.trackIndex
            }
            track.copy(isSelected = !isVideoAuto && !isVideoNone && isChecked, isRadio = useRadio)
        })

        (currentAdapter as? TrackAdapter<TrackUiModel.Video>)?.submit(list)
    }

    private fun showAudioTracks() {
        if (audioTracks.isEmpty()) return

        val list = mutableListOf<TrackUiModel.Audio>()

        list.add(TrackUiModel.Audio(
            groupIndex = -1, trackIndex = -1, language = "Auto", channels = 0, bitrate = 0,
            isSelected = isAudioAuto
        ))
        list.add(TrackUiModel.Audio(
            groupIndex = -2, trackIndex = -2, language = "None", channels = 0, bitrate = 0,
            isSelected = isAudioNone
        ))
        list.addAll(audioTracks.map { track ->
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

        adapter.submit(list)
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun showTextTracks() {
        if (textTracks.isEmpty()) return

        val list = mutableListOf<TrackUiModel.Text>()

        list.add(TrackUiModel.Text(
            groupIndex = -1, trackIndex = -1, language = "Auto",
            isSelected = isTextAuto
        ))
        list.add(TrackUiModel.Text(
            groupIndex = -2, trackIndex = -2, language = "None",
            isSelected = isTextNone
        ))
        list.addAll(textTracks.filter { it.language != "Off" }.map { track ->
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

        adapter.submit(list)
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
            val videoList = if (isVideoAuto || isVideoNone) emptyList() else selectedVideoQualities.toList()

            TrackSelectionApplier.applyMultipleVideo(
                player       = player,
                videoTracks  = videoList,
                audio        = selectedAudio,
                text         = selectedText,
                disableVideo = isVideoNone,
                disableAudio = isAudioNone,
                disableText  = isTextNone
            )

            player.setPlaybackSpeed(selectedSpeed)
        } catch (e: Exception) {
            Timber.e(e, "Error applying selections")
        }
    }
}
