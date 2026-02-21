package com.livetvpro.utils

import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation

/**
 * Image loading utility using Coil.
 * Coil has native SVG support via coil-svg and circleCrop works correctly on all image types
 * including SVGs — unlike Glide where SVG → PictureDrawable broke circleCrop.
 */
object GlideExtensions {

    // Single shared ImageLoader with SVG support
    private var _imageLoader: ImageLoader? = null

    private fun getImageLoader(imageView: ImageView): ImageLoader {
        return _imageLoader ?: ImageLoader.Builder(imageView.context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
            .also { _imageLoader = it }
    }

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

        imageView.load(url, getImageLoader(imageView)) {
            diskCachePolicy(CachePolicy.ENABLED)
            memoryCachePolicy(CachePolicy.ENABLED)
            if (placeholderResId != null) placeholder(placeholderResId)
            if (errorResId != null) error(errorResId)
            if (isCircular) transformations(CircleCropTransformation())
        }
    }
}
