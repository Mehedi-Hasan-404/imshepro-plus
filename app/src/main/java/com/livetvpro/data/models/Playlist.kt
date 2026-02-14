package com.livetvpro.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val isFile: Boolean = false,
    val filePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable {
    // Helper to get the source for display
    fun getSource(): String = if (isFile) filePath else url
}
