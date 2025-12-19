package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.livetvpro.databinding.DialogPlayerSettingsBinding
import timber.log.Timber

class PlayerSettingsDialog(
    context: Context,
    private val player: ExoPlayer
) : Dialog(context) {

    private lateinit var binding: DialogPlayerSettingsBinding
    
    // Store current selections
    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null
    
    // Track lists
    private var videoTracks = listOf<TrackUiModel.Video>()
    private var audioTracks = listOf<TrackUiModel.Audio>()
    private var textTracks = listOf<TrackUiModel.Text>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        binding = DialogPlayerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure dialog window
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        setupViews()
        loadTracks()
        showVideoTracks() // Show video tracks by default
    }

    private fun setupViews() {
        // Setup RecyclerView
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        
        // Close button
        binding.btnClose.setOnClickListener { dismiss() }

        // Setup tabs
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Video"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Audio"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Text"))

        // Tab selection listener
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showVideoTracks()
                    1 -> showAudioTracks()
                    2 -> showTextTracks()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Cancel button
        binding.btnCancel.setOnClickListener { dismiss() }

        // Apply button
        binding.btnApply.setOnClickListener {
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

            // Find currently selected tracks
            selectedVideo = videoTracks.firstOrNull { it.isSelected }
            selectedAudio = audioTracks.firstOrNull { it.isSelected }
            selectedText = textTracks.firstOrNull { it.isSelected }
            
            Timber.d("Loaded ${videoTracks.size} video, ${audioTracks.size} audio, ${textTracks.size} text tracks")
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
            showVideoTracks() // Refresh to update radio buttons
        }
        
        // Update selection state
        val updatedTracks = videoTracks.map { track ->
            track.copy(isSelected = track == selectedVideo)
        }
        
        adapter.submit(updatedTracks)
        binding.recyclerView.adapter = adapter
    }

    private fun showAudioTracks() {
        if (audioTracks.isEmpty()) {
            Timber.w("No audio tracks available")
            return
        }
        
        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            selectedAudio = selected
            showAudioTracks() // Refresh
        }
        
        val updatedTracks = audioTracks.map { track ->
            track.copy(isSelected = track == selectedAudio)
        }
        
        adapter.submit(updatedTracks)
        binding.recyclerView.adapter = adapter
    }

    private fun showTextTracks() {
        if (textTracks.isEmpty()) {
            Timber.w("No text tracks available")
            return
        }
        
        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            selectedText = selected
            showTextTracks() // Refresh
        }
        
        val updatedTracks = textTracks.map { track ->
            track.copy(isSelected = track == selectedText)
        }
        
        adapter.submit(updatedTracks)
        binding.recyclerView.adapter = adapter
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
