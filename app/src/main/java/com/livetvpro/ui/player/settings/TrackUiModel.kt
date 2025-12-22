package com.livetvpro.ui.player.settings

sealed class TrackUiModel {
    abstract val isSelected: Boolean
    abstract val isRadio: Boolean

    data class Video(
        val groupIndex: Int,
        val trackIndex: Int,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        override val isSelected: Boolean,
        override val isRadio: Boolean = false  // CHECKBOXES for multiple quality selection
    ) : TrackUiModel()

    data class Audio(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String,
        val channels: Int,
        val bitrate: Int,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // RADIO BUTTONS for single audio track
    ) : TrackUiModel()

    data class Text(
        val groupIndex: Int?,
        val trackIndex: Int?,
        val language: String,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // RADIO BUTTONS for single subtitle track
    ) : TrackUiModel()

    data class Speed(
        val speed: Float,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // RADIO BUTTONS for single speed selection
    ) : TrackUiModel()
}

