package com.livetvpro.ui.player.settings

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters

object TrackSelectionApplier {

    fun apply(
        player: Player,
        video: TrackUiModel.Video?,
        audio: TrackUiModel.Audio?,
        text: TrackUiModel.Text?,
        disableVideo: Boolean = false,
        disableAudio: Boolean = false,
        disableText: Boolean = true
    ) {
        val builder = player.trackSelectionParameters.buildUpon()

        // VIDEO
        if (disableVideo) {
            // Disable video track
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        } else if (video == null) {
            // Auto - clear overrides and enable
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        } else {
            // Specific track
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            builder.setOverrideForType(
                TrackSelectionOverride(
                    player.currentTracks.groups[video.groupIndex].mediaTrackGroup,
                    listOf(video.trackIndex)
                )
            )
        }

        // AUDIO
        if (disableAudio) {
            // Disable audio track
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        } else if (audio == null) {
            // Auto - clear overrides and enable
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        } else {
            // Specific track
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            builder.setOverrideForType(
                TrackSelectionOverride(
                    player.currentTracks.groups[audio.groupIndex].mediaTrackGroup,
                    listOf(audio.trackIndex)
                )
            )
        }

        // TEXT
        if (disableText) {
            // Disable text track (None)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (text == null) {
            // Auto - enable but don't force a specific track
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        } else if (text.groupIndex != null && text.trackIndex != null) {
            // Specific track
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(
                TrackSelectionOverride(
                    player.currentTracks.groups[text.groupIndex].mediaTrackGroup,
                    listOf(text.trackIndex)
                )
            )
        }

        player.trackSelectionParameters = builder.build()
    }
}
