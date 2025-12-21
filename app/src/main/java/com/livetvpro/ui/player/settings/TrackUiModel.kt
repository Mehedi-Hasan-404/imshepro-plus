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
        // Auto (-1) and None (-2) use radio buttons, quality tracks use checkboxes
        override val isRadio: Boolean = (groupIndex < 0)
    ) : TrackUiModel()

    data class Audio(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String,
        val channels: Int,
        val bitrate: Int,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // Always radio buttons
    ) : TrackUiModel()

    data class Text(
        val groupIndex: Int?,
        val trackIndex: Int?,
        val language: String,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // Always radio buttons
    ) : TrackUiModel()

    data class Speed(
        val speed: Float,
        override val isSelected: Boolean,
        override val isRadio: Boolean = true  // Always radio buttons
    ) : TrackUiModel()
}
