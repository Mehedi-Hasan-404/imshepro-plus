package com.livetvpro.ui.player.settings

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters

object TrackSelectionApplier {

    fun apply(
        player: Player,
        video: TrackUiModel.Video?,
        audio: TrackUiModel.Audio?,
        text: TrackUiModel.Text?
    ) {
        val builder = player.trackSelectionParameters.buildUpon()

        // VIDEO
        if (video == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            builder.setOverrideForType(
                TrackSelectionParameters.TrackSelectionOverride(
                    player.currentTracks.groups[video.groupIndex].mediaTrackGroup,
                    listOf(video.trackIndex)
                )
            )
        }

        // AUDIO
        audio?.let {
            builder.setOverrideForType(
                TrackSelectionParameters.TrackSelectionOverride(
                    player.currentTracks.groups[it.groupIndex].mediaTrackGroup,
                    listOf(it.trackIndex)
                )
            )
        }

        // TEXT
        if (text?.groupIndex == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(
                TrackSelectionParameters.TrackSelectionOverride(
                    player.currentTracks.groups[text.groupIndex].mediaTrackGroup,
                    listOf(text.trackIndex!!)
                )
            )
        }

        player.trackSelectionParameters = builder.build()
    }
}
