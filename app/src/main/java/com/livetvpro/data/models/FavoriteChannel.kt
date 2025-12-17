package com.livetvpro.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FavoriteChannel(
    val id: String,
    val name: String,
    val url: String,      // The stream link must be saved here
    val logoUrl: String?,
    val categoryName: String? = null
) : Parcelable
