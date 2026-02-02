package com.livetvpro.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.caverock.androidsvg.SVG

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
     * Load SVG image with proper circular crop support
     */
    private fun loadSvg(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?,
        isCircular: Boolean
    ) {
        // Set placeholder if provided
        if (placeholderResId != null) {
            imageView.setImageResource(placeholderResId)
        }
        
        if (isCircular) {
            // For circular SVGs, we need to load as bitmap and apply circular mask
            val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(imageView.context)
                .`as`(PictureDrawable::class.java)
                .listener(SvgSoftwareLayerSetter())
            
            requestBuilder.load(url).into(object : CustomTarget<PictureDrawable>() {
                override fun onResourceReady(
                    resource: PictureDrawable,
                    transition: Transition<in PictureDrawable>?
                ) {
                    // Convert PictureDrawable to Bitmap
                    val bitmap = pictureDrawableToBitmap(resource, imageView.width, imageView.height)
                    
                    if (bitmap != null) {
                        // Apply circular mask
                        val circularBitmap = getCircularBitmap(bitmap)
                        imageView.setImageBitmap(circularBitmap)
                    } else {
                        // Fallback to error image
                        if (errorResId != null) {
                            imageView.setImageResource(errorResId)
                        }
                    }
                }
                
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    // Cleanup if needed
                }
                
                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    if (errorResId != null) {
                        imageView.setImageResource(errorResId)
                    }
                }
            })
        } else {
            // For non-circular SVGs, use the standard approach
            val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(imageView.context)
                .`as`(PictureDrawable::class.java)
                .listener(SvgSoftwareLayerSetter())
            
            var options = RequestOptions()
            
            if (placeholderResId != null) {
                options = options.placeholder(placeholderResId)
            }
            
            if (errorResId != null) {
                options = options.error(errorResId)
            }
            
            requestBuilder
                .apply(options)
                .load(url)
                .into(imageView)
        }
    }
    
    /**
     * Convert PictureDrawable to Bitmap
     */
    private fun pictureDrawableToBitmap(
        pictureDrawable: PictureDrawable,
        width: Int,
        height: Int
    ): Bitmap? {
        return try {
            val picture = pictureDrawable.picture
            
            // Use ImageView dimensions, or default to picture size
            val bitmapWidth = if (width > 0) width else picture.width
            val bitmapHeight = if (height > 0) height else picture.height
            
            // Create bitmap with proper size
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )
            
            // Draw picture onto bitmap
            val canvas = Canvas(bitmap)
            canvas.drawPicture(picture)
            
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Apply circular mask to bitmap
     */
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        
        val canvas = Canvas(output)
        val paint = Paint()
        paint.isAntiAlias = true
        
        // Draw circle
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        
        // Apply source image using SRC_IN mode
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        
        // Center the source bitmap if it's not square
        val left = (size - bitmap.width) / 2f
        val top = (size - bitmap.height) / 2f
        canvas.drawBitmap(bitmap, left, top, paint)
        
        return output
    }
}
