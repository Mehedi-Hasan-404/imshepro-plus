package com.livetvpro.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions

/**
 * Extension functions for Glide to handle both regular images and SVG files
 */
object GlideExtensions {

    /**
     * Load image with automatic SVG detection and handling
     */
    fun loadImage(
        imageView: ImageView,
        url: String?,
        placeholderResId: Int? = null,
        errorResId: Int? = null,
        isCircular: Boolean = false
    ) {
        if (url.isNullOrEmpty()) {
            if (placeholderResId != null) {
                imageView.setImageResource(placeholderResId)
            }
            return
        }

        if (url.endsWith(".svg", ignoreCase = true)) {
            loadSvg(imageView, url, placeholderResId, errorResId, isCircular)
        } else {
            var request = Glide.with(imageView.context)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(DrawableTransitionOptions.withCrossFade())

            if (placeholderResId != null) request = request.placeholder(placeholderResId)
            if (errorResId != null) request = request.error(errorResId)
            if (isCircular) request = request.circleCrop()

            request.into(imageView)
        }
    }

    /**
     * Load SVG image.
     *
     * Root cause of no-circle bug: Glide's circleCrop() only works on Bitmap-backed drawables.
     * PictureDrawable (produced by the SVG pipeline) is not a Bitmap, so circleCrop() is silently
     * ignored. Fix: when circular is needed, decode SVG → PictureDrawable → Bitmap, then
     * let Glide apply circleCrop() normally on the Bitmap pipeline.
     */
    private fun loadSvg(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?,
        isCircular: Boolean
    ) {
        if (isCircular) {
            // Decode SVG as Bitmap so circleCrop() works correctly
            var request = Glide.with(imageView.context)
                .asBitmap()
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .transition(BitmapTransitionOptions.withCrossFade())

            if (placeholderResId != null) request = request.placeholder(placeholderResId)
            if (errorResId != null) request = request.error(errorResId)

            request.into(imageView)
        } else {
            // Non-circular SVG — use the PictureDrawable pipeline as before
            val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(imageView.context)
                .`as`(PictureDrawable::class.java)
                .listener(SvgSoftwareLayerSetter())

            var options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)

            if (placeholderResId != null) options = options.placeholder(placeholderResId)
            if (errorResId != null) options = options.error(errorResId)

            requestBuilder
                .apply(options)
                .load(url)
                .into(imageView)
        }
    }
}
