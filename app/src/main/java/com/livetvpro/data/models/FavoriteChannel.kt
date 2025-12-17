package com.livetvpro.data.models

data class FavoriteChannel(
    val id: String,
    val name: String,
    val url: String,      // This holds the M3U stream link
    val logoUrl: String?,
    val categoryName: String?
)
