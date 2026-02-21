package com.livetvpro.utils

import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
        
        // Check if URL is SVG
        if (url.endsWith(".svg", ignoreCase = true)) {
            loadSvg(imageView, url, placeholderResId, errorResId, isCircular)
        } else {
            // Load regular image
            var request = Glide.with(imageView.context)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(DrawableTransitionOptions.withCrossFade())
            
            if (placeholderResId != null) {
                request = request.placeholder(placeholderResId)
            }
            
            if (errorResId != null) {
                request = request.error(errorResId)
            }
            
            if (isCircular) {
                request = request.circleCrop()
            }
            
            request.into(imageView)
        }
    }
    
    /**
     * Load SVG image
     */
    private fun loadSvg(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?,
        isCircular: Boolean
    ) {
        val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(imageView.context)
            .`as`(PictureDrawable::class.java)
            .listener(SvgSoftwareLayerSetter())
        
        var options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
        
        if (placeholderResId != null) {
            options = options.placeholder(placeholderResId)
        }
        
        if (errorResId != null) {
            options = options.error(errorResId)
        }
        
        if (isCircular) {
            options = options.circleCrop()
        }
        
        requestBuilder
            .apply(options)
            .load(url)
            .into(imageView)
    }
}
