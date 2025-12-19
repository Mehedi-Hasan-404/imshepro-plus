package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import timber.log.Timber

class PlayerSettingsDialog(
    context: Context,
    private val player: ExoPlayer
) : Dialog(context) {

    private lateinit var recyclerVideo: RecyclerView
    private lateinit var recyclerAudio: RecyclerView
    private lateinit var recyclerText: RecyclerView
    private lateinit var columnText: View
    private lateinit var dividerText: View
    private lateinit var btnApply: Button
    private lateinit var btnClose: ImageButton

    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_player_settings)

        // Make dialog background transparent to show our CardView rounded corners
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        // MAXIMIZE WIDTH: Critical for Landscape mode 3-column layout
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.90).toInt(), // 90% Width
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        initViews()
        loadAndBindTracks()
    }

    private fun initViews() {
        recyclerVideo = findViewById(R.id.recyclerVideo)
        recyclerAudio = findViewById(R.id.recyclerAudio)
        recyclerText = findViewById(R.id.recyclerText)
        
        columnText = findViewById(R.id.columnText)
        dividerText = findViewById(R.id.dividerText)
        
        btnApply = findViewById(R.id.btnApply)
        btnClose = findViewById(R.id.btnClose)

        recyclerVideo.layoutManager = LinearLayoutManager(context)
        recyclerAudio.layoutManager = LinearLayoutManager(context)
        recyclerText.layoutManager = LinearLayoutManager(context)

        btnClose.setOnClickListener { dismiss() }
        btnApply.setOnClickListener {
            applySelections()
            dismiss()
        }
    }

    private fun loadAndBindTracks() {
        try {
            // 1. Load Data
            val videoTracks = PlayerTrackMapper.videoTracks(player)
            val audioTracks = PlayerTrackMapper.audioTracks(player)
            val textTracks = PlayerTrackMapper.textTracks(player)

            // 2. Set Initial Selections
            selectedVideo = videoTracks.firstOrNull { it.isSelected }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }
            selectedText = textTracks.firstOrNull { it.isSelected }

            // 3. Bind Video Adapter
            val videoAdapter = TrackAdapter<TrackUiModel.Video> { selected ->
                selectedVideo = selected
                // Refresh list to update radio button UI
                (recyclerVideo.adapter as TrackAdapter<TrackUiModel.Video>).submit(
                    videoTracks.map { it.copy(isSelected = it == selected) }
                )
            }
            videoAdapter.submit(videoTracks)
            recyclerVideo.adapter = videoAdapter

            // 4. Bind Audio Adapter
            val audioAdapter = TrackAdapter<TrackUiModel.Audio> { selected ->
                selectedAudio = selected
                (recyclerAudio.adapter as TrackAdapter<TrackUiModel.Audio>).submit(
                    audioTracks.map { it.copy(isSelected = it == selected) }
                )
            }
            audioAdapter.submit(audioTracks)
            recyclerAudio.adapter = audioAdapter

            // 5. Bind Text Adapter (Conditional)
            if (textTracks.isNotEmpty() && textTracks.any { it.language != "Off" }) {
                columnText.visibility = View.VISIBLE
                dividerText.visibility = View.VISIBLE
                
                val textAdapter = TrackAdapter<TrackUiModel.Text> { selected ->
                    selectedText = selected
                    (recyclerText.adapter as TrackAdapter<TrackUiModel.Text>).submit(
                        textTracks.map { it.copy(isSelected = it == selected) }
                    )
                }
                textAdapter.submit(textTracks)
                recyclerText.adapter = textAdapter
            } else {
                columnText.visibility = View.GONE
                dividerText.visibility = View.GONE
            }

        } catch (e: Exception) {
            Timber.e(e, "Error loading tracks in settings dialog")
        }
    }

    private fun applySelections() {
        TrackSelectionApplier.apply(
            player = player,
            video = selectedVideo,
            audio = selectedAudio,
            text = selectedText
        )
    }
}

