package com.livetvpro.ui.player.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.activity.ComponentDialog
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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

    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null
    private var selectedSpeed: Float = 1.0f

    private var selectedVideoQualities = mutableSetOf<TrackUiModel.Video>()

    private var isVideoNone = false
    private var isAudioNone = false
    private var isTextNone  = false

    private var isVideoAuto = true
    private var isAudioAuto = true
    private var isTextAuto  = true

    private var videoTracks = listOf<TrackUiModel.Video>()
    private var audioTracks = listOf<TrackUiModel.Audio>()
    private var textTracks  = listOf<TrackUiModel.Text>()

    private var currentAdapter: TrackAdapter<*>? = null
    private var tracksListener: Player.Listener? = null

    // Stable tab list — built once and reused by the single persistent listener
    private val tabs = mutableListOf<TabEntry>()
    private data class TabEntry(val label: String, val show: () -> Unit)

    // Single tab listener attached once — never re-attached
    private val tabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            tabs.getOrNull(tab?.position ?: return)?.show?.invoke()
        }
        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_player_settings)

        val displayMetrics = context.resources.displayMetrics
        window?.setLayout(
            (displayMetrics.widthPixels * 0.85).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        recyclerView = findViewById(R.id.recyclerView)
        tabLayout    = findViewById(R.id.tabLayout)
        btnCancel    = findViewById(R.id.btnCancel)
        btnApply     = findViewById(R.id.btnApply)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dismiss() }
        btnCancel.setOnClickListener { dismiss() }
        btnApply.setOnClickListener { applySelections(); dismiss() }

        recyclerView.layoutManager = LinearLayoutManager(context)

        // Attach the listener exactly ONCE — it stays for the dialog's lifetime
        tabLayout.addOnTabSelectedListener(tabSelectedListener)

        loadTracks()

        val allEmpty = videoTracks.isEmpty() && audioTracks.isEmpty() && textTracks.isEmpty()
        if (allEmpty) {
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (tracks.groups.isNotEmpty()) {
                        player.removeListener(this)
                        tracksListener = null
                        recyclerView.post {
                            loadTracks()
                            rebuildTabs()
                        }
                    }
                }
            }
            tracksListener = listener
            player.addListener(listener)
        }

        rebuildTabs()
    }

    override fun onStop() {
        super.onStop()
        tracksListener?.let { player.removeListener(it) }
        tracksListener = null
    }

    private fun loadTracks() {
        try {
            val params = player.trackSelectionParameters

            val disabledVideo = params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
            val hasVideoOver  = player.currentTracks.groups.any {
                it.type == C.TRACK_TYPE_VIDEO && params.overrides.containsKey(it.mediaTrackGroup)
            }
            isVideoNone = disabledVideo
            isVideoAuto = !disabledVideo && !hasVideoOver

            val disabledAudio = params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
            val hasAudioOver  = player.currentTracks.groups.any {
                it.type == C.TRACK_TYPE_AUDIO && params.overrides.containsKey(it.mediaTrackGroup)
            }
            isAudioNone = disabledAudio
            isAudioAuto = !disabledAudio && !hasAudioOver

            val disabledText = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val hasTextOver  = player.currentTracks.groups.any {
                it.type == C.TRACK_TYPE_TEXT && params.overrides.containsKey(it.mediaTrackGroup)
            }
            isTextNone = disabledText
            isTextAuto = !disabledText && !hasTextOver

            videoTracks = PlayerTrackMapper.videoTracks(player)
            audioTracks = PlayerTrackMapper.audioTracks(player)
            textTracks  = PlayerTrackMapper.textTracks(player)

            selectedVideoQualities.clear()
            if (!isVideoAuto && !isVideoNone)
                selectedVideoQualities.addAll(videoTracks.filter { it.isSelected })

            selectedAudio = if (!isAudioAuto && !isAudioNone) audioTracks.firstOrNull { it.isSelected } else null
            selectedText  = if (!isTextAuto  && !isTextNone)  textTracks.firstOrNull  { it.isSelected } else null
            selectedSpeed = player.playbackParameters.speed

            Timber.d("Tracks — video:${videoTracks.size} audio:${audioTracks.size} text:${textTracks.size}")
        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks")
        }
    }

    private fun rebuildTabs() {
        // Clear tabs list and all tab views — listener stays attached
        tabs.clear()
        tabLayout.removeAllTabs()

        if (videoTracks.isNotEmpty()) tabs.add(TabEntry("Video") { showVideoTracks() })
        if (audioTracks.isNotEmpty()) tabs.add(TabEntry("Audio") { showAudioTracks() })
        if (textTracks.isNotEmpty())  tabs.add(TabEntry("Text")  { showTextTracks()  })
        tabs.add(TabEntry("Speed") { showSpeedOptions() })

        // Adding tabs fires the listener — but tabs list is already populated so it's safe
        tabs.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.label)) }

        // Manually trigger first tab content (addTab selects first tab but listener
        // fires during addTab before our tabs list was ready in previous versions —
        // now tabs list IS ready so we just call show directly to be explicit)
        tabs.firstOrNull()?.show?.invoke()
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private fun showVideoTracks() {
        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            when (selected.groupIndex) {
                -1   -> { selectedVideoQualities.clear(); isVideoAuto = true;  isVideoNone = false }
                -2   -> { selectedVideoQualities.clear(); isVideoAuto = false; isVideoNone = true  }
                else -> {
                    isVideoAuto = false; isVideoNone = false
                    val key = selectedVideoQualities.find {
                        it.groupIndex == selected.groupIndex && it.trackIndex == selected.trackIndex
                    }
                    if (key != null) selectedVideoQualities.remove(key)
                    else selectedVideoQualities.add(selected)
                    if (selectedVideoQualities.isEmpty()) isVideoAuto = true
                }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Video>)?.submit(buildVideoList())
        }
        adapter.submit(buildVideoList())
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun buildVideoList(): List<TrackUiModel.Video> {
        val list = mutableListOf<TrackUiModel.Video>()
        list.add(TrackUiModel.Video(-1, -1, 0, 0, 0, isSelected = isVideoAuto,  isRadio = true))
        list.add(TrackUiModel.Video(-2, -2, 0, 0, 0, isSelected = isVideoNone, isRadio = true))
        val useRadio = videoTracks.size == 1
        list.addAll(videoTracks.map { t ->
            val checked = selectedVideoQualities.any {
                it.groupIndex == t.groupIndex && it.trackIndex == t.trackIndex
            }
            t.copy(isSelected = !isVideoAuto && !isVideoNone && checked, isRadio = useRadio)
        })
        return list
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private fun showAudioTracks() {
        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            when (selected.groupIndex) {
                -1   -> { selectedAudio = null; isAudioNone = false; isAudioAuto = true  }
                -2   -> { selectedAudio = null; isAudioNone = true;  isAudioAuto = false }
                else -> { selectedAudio = selected; isAudioNone = false; isAudioAuto = false }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Audio>)?.updateSelection(selected)
        }
        adapter.submit(buildAudioList())
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun buildAudioList(): List<TrackUiModel.Audio> {
        val list = mutableListOf<TrackUiModel.Audio>()
        list.add(TrackUiModel.Audio(-1, -1, "Auto", 0, 0, isSelected = isAudioAuto))
        list.add(TrackUiModel.Audio(-2, -2, "None", 0, 0, isSelected = isAudioNone))
        list.addAll(audioTracks.map { t ->
            t.copy(isSelected = !isAudioAuto && !isAudioNone && t.isSelected)
        })
        return list
    }

    // ── Text ──────────────────────────────────────────────────────────────────

    private fun showTextTracks() {
        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            when (selected.groupIndex) {
                -1   -> { selectedText = null; isTextNone = false; isTextAuto = true  }
                -2   -> { selectedText = null; isTextNone = true;  isTextAuto = false }
                else -> { selectedText = selected; isTextNone = false; isTextAuto = false }
            }
            (currentAdapter as? TrackAdapter<TrackUiModel.Text>)?.updateSelection(selected)
        }
        adapter.submit(buildTextList())
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    private fun buildTextList(): List<TrackUiModel.Text> {
        val list = mutableListOf<TrackUiModel.Text>()
        list.add(TrackUiModel.Text(-1, -1, "Auto", isSelected = isTextAuto))
        list.add(TrackUiModel.Text(-2, -2, "None", isSelected = isTextNone))
        list.addAll(textTracks.map { t ->
            t.copy(isSelected = !isTextAuto && !isTextNone && t.isSelected)
        })
        return list
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    private fun showSpeedOptions() {
        val speeds  = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val adapter = TrackAdapter<TrackUiModel.Speed> { selected ->
            selectedSpeed = selected.speed
            (currentAdapter as? TrackAdapter<TrackUiModel.Speed>)?.updateSelection(selected)
        }
        adapter.submit(speeds.map { TrackUiModel.Speed(it, isSelected = it == selectedSpeed) })
        recyclerView.adapter = adapter
        currentAdapter = adapter
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    private fun applySelections() {
        try {
            TrackSelectionApplier.applyMultipleVideo(
                player       = player,
                videoTracks  = if (isVideoAuto || isVideoNone) emptyList() else selectedVideoQualities.toList(),
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
