import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import java.util.*

object PlayerTrackMapper {

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
                        isSelected = group.isTrackSelected(i)
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
                        groupIndex,
                        i,
                        language = format.language ?: "Unknown",
                        channels = format.channelCount,
                        bitrate = format.bitrate,
                        isSelected = group.isTrackSelected(i)
                    )
                )
            }
        }

        return result
    }

    fun textTracks(player: Player): List<TrackUiModel.Text> {
        val result = mutableListOf<TrackUiModel.Text>()

        // OFF option
        result.add(
            TrackUiModel.Text(
                groupIndex = null,
                trackIndex = null,
                language = "Off",
                isSelected = !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
            )
        )

        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)

                result.add(
                    TrackUiModel.Text(
                        groupIndex,
                        i,
                        language = format.language ?: "Unknown",
                        isSelected = group.isTrackSelected(i)
                    )
                )
            }
        }

        return result
    }
}
