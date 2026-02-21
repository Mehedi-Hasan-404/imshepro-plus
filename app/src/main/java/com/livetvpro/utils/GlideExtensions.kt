package com.livetvpro.utils

import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation

/**
 * Image loading utility using Coil.
 * SVG support and allowHardware(false) are configured on the singleton ImageLoader
 * in LiveTVProApplication — no need to build a custom loader here.
 */
object GlideExtensions {

    fun loadImage(
        imageView: ImageView,
        url: String?,
        placeholderResId: Int? = null,
        errorResId: Int? = null,
        isCircular: Boolean = false
    ) {
        if (url.isNullOrEmpty()) {
            if (placeholderResId != null) imageView.setImageResource(placeholderResId)
            return
        }

        // Skip reload entirely if this exact URL is already displayed.
        // This prevents images flashing through placeholder on tab-switch, because
        // Coil would otherwise re-issue the request even when the bitmap is cached.
        if (imageView.tag == url) return
        imageView.tag = url

        // Uses the singleton ImageLoader set in LiveTVProApplication (has SvgDecoder + allowHardware=false)
        imageView.load(url) {
            diskCachePolicy(CachePolicy.ENABLED)
            memoryCachePolicy(CachePolicy.ENABLED)
            if (placeholderResId != null) placeholder(placeholderResId)
            if (errorResId != null) error(errorResId)
            if (isCircular) transformations(CircleCropTransformation())
        }
    }
}
