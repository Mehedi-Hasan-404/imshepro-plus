package com.livetvpro.ui.player.settings

import androidx.media3.common.C
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

    private fun channelLabel(channelCount: Int): String {
        return when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "Surround 5.1"
            8 -> "Surround 7.1"
            else -> if (channelCount > 0) "${channelCount}ch" else ""
        }
    }

    fun videoTracks(player: Player): List<TrackUiModel.Video> {
        val result = mutableListOf<TrackUiModel.Video>()

        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)

                result.add(
                    TrackUiModel.Video(
                        groupIndex = groupIndex,
                        trackIndex = i,
                        width = format.width,
                        height = format.height,
                        bitrate = format.bitrate,
                        isSelected = group.isTrackSelected(i),
                        isRadio = false // Quality tracks typically use checkmarks/list style
                    )
                )
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

                result.add(
                    TrackUiModel.Audio(
                        groupIndex = groupIndex,
                        trackIndex = i,
                        language = languageDisplayName(format.language),
                        channels = format.channelCount,
                        bitrate = format.bitrate,
                        isSelected = group.isTrackSelected(i),
                        isRadio = true // Audio tracks usually use radio buttons
                    )
                )
            }
        }

        return result
    }

    fun textTracks(player: Player): List<TrackUiModel.Text> {
        val result = mutableListOf<TrackUiModel.Text>()

        // Add the initial "Off" option based on current player state
        result.add(
            TrackUiModel.Text(
                groupIndex = null,
                trackIndex = null,
                language = "Off",
                isSelected = !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT),
                isRadio = true
            )
        )

        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)

                result.add(
                    TrackUiModel.Text(
                        groupIndex = groupIndex,
                        trackIndex = i,
                        language = languageDisplayName(format.language),
                        isSelected = group.isTrackSelected(i),
                        isRadio = true
                    )
                )
            }
        }

        return result
    }
}
