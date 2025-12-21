package com.livetvpro.ui.player.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.livetvpro.R
import com.livetvpro.databinding.DialogPlayerSettingsBinding

class PlayerSettingsDialog(
    context: Context,
    private val player: Player
) : Dialog(context, R.style.Theme_LiveTVPro) {

    private lateinit var binding: DialogPlayerSettingsBinding
    
    private var selectedVideo: TrackUiModel.Video? = null
    private var selectedAudio: TrackUiModel.Audio? = null
    private var selectedText: TrackUiModel.Text? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPlayerSettingsBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        setupTabs()
        setupButtons()
        
        // Show Video tab by default
        showVideoTracks()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Quality"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Audio"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Subtitles"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showVideoTracks()
                    1 -> showAudioTracks()
                    2 -> showTextTracks()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnApply.setOnClickListener {
            applySelection()
            dismiss()
        }
    }

    private fun showVideoTracks() {
        val tracks = PlayerTrackMapper.videoTracks(player)
        
        // Add "Auto" option
        val autoOption = TrackUiModel.Video(
            groupIndex = -1,
            trackIndex = -1,
            width = 0,
            height = 0,
            bitrate = 0,
            isSelected = selectedVideo == null
        )
        
        val allTracks = listOf(autoOption) + tracks
        
        val adapter = TrackAdapter<TrackUiModel.Video> { selected ->
            selectedVideo = if (selected.groupIndex == -1) null else selected
            (binding.recyclerView.adapter as? TrackAdapter<TrackUiModel.Video>)?.updateSelection(selected)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        adapter.submit(allTracks)
    }

    private fun showAudioTracks() {
        val tracks = PlayerTrackMapper.audioTracks(player)
        
        // Add "Auto" option
        val autoOption = TrackUiModel.Audio(
            groupIndex = -1,
            trackIndex = -1,
            language = "Auto",
            channels = 0,
            bitrate = 0,
            isSelected = selectedAudio == null
        )
        
        val allTracks = listOf(autoOption) + tracks
        
        val adapter = TrackAdapter<TrackUiModel.Audio> { selected ->
            selectedAudio = if (selected.groupIndex == -1) null else selected
            (binding.recyclerView.adapter as? TrackAdapter<TrackUiModel.Audio>)?.updateSelection(selected)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        adapter.submit(allTracks)
    }

    private fun showTextTracks() {
        val tracks = PlayerTrackMapper.textTracks(player)
        
        val adapter = TrackAdapter<TrackUiModel.Text> { selected ->
            selectedText = if (selected.language == "Off") null else selected
            (binding.recyclerView.adapter as? TrackAdapter<TrackUiModel.Text>)?.updateSelection(selected)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        adapter.submit(tracks)
    }

    private fun applySelection() {
        TrackSelectionApplier.apply(
            player = player,
            video = selectedVideo,
            audio = selectedAudio,
            text = selectedText,
            disableText = selectedText == null
        )
    }
}
