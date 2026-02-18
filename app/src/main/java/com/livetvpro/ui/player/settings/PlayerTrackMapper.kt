package com.livetvpro.ui.player.settings

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import java.util.Locale

object PlayerTrackMapper {

    private fun languageDisplayName(code: String?): String {
        if (code == null || code == "und" || code.isBlank()) return "Unknown"
        return try {
            val locale = Locale.forLanguageTag(code)
            val name = locale.getDisplayLanguage(Locale.ENGLISH)
            if (name.isNotBlank() && name != code) name else code.uppercase()
        } catch (e: Exception) {
            code.uppercase()
        }
    }

    fun videoTracks(player: Player): List<TrackUiModel.Video> {
        val result = mutableListOf<TrackUiModel.Video>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                result.add(TrackUiModel.Video(
                    groupIndex = groupIndex,
                    trackIndex = i,
                    width      = format.width,
                    height     = format.height,
                    bitrate    = format.bitrate,
                    isSelected = group.isTrackSelected(i),
                    isRadio    = false
                ))
            }
        }
        return result.sortedWith(
            compareByDescending<TrackUiModel.Video> { it.height }
                .thenByDescending { it.bitrate }
        )
    }

    fun audioTracks(player: Player): List<TrackUiModel.Audio> {
        val result = mutableListOf<TrackUiModel.Audio>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                result.add(TrackUiModel.Audio(
                    groupIndex = groupIndex,
                    trackIndex = i,
                    language   = languageDisplayName(format.language),
                    channels   = format.channelCount,
                    bitrate    = format.bitrate,
                    isSelected = group.isTrackSelected(i),
                    isRadio    = true
                ))
            }
        }
        return result
    }

    fun textTracks(player: Player): List<TrackUiModel.Text> {
        val result = mutableListOf<TrackUiModel.Text>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)

                // Skip embedded/forced CEA captions and tracks with no real language
                // that ExoPlayer adds by default even when there are no real subtitles
                val mimeType = format.sampleMimeType ?: ""
                val isCea = mimeType == MimeTypes.APPLICATION_CEA608
                        || mimeType == MimeTypes.APPLICATION_CEA708
                        || mimeType == MimeTypes.APPLICATION_MP4CEA608
                val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0

                // Only include non-forced, non-CEA subtitle tracks
                if (isCea || isForced) continue

                result.add(TrackUiModel.Text(
                    groupIndex = groupIndex,
                    trackIndex = i,
                    language   = languageDisplayName(format.language),
                    isSelected = group.isTrackSelected(i),
                    isRadio    = true
                ))
            }
        }
        return result
    }
}
