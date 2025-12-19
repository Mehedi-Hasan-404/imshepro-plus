package com.livetvpro.ui.player.settings

sealed class TrackUiModel {
    abstract val isSelected: Boolean

    data class Video(
        val groupIndex: Int,
        val trackIndex: Int,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        override val isSelected: Boolean
    ) : TrackUiModel()

    data class Audio(
        val groupIndex: Int,
        val trackIndex: Int,
        val language: String,
        val channels: Int,
        val bitrate: Int,
        override val isSelected: Boolean
    ) : TrackUiModel()

    data class Text(
        val groupIndex: Int?,
        val trackIndex: Int?,
        val language: String,
        override val isSelected: Boolean
    ) : TrackUiModel()
}
