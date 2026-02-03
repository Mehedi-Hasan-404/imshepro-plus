package com.livetvpro.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Extension functions for Glide to handle both regular images and SVG files
 * ✅ ULTRA-SAFE VERSION: Maximum error handling to prevent all crashes
 */
object GlideExtensions {
    
    private const val TAG = "GlideExtensions"
    private const val DEFAULT_SIZE = 200
    
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
        try {
            // Validate context first
            val context = imageView.context
            if (context == null) {
                android.util.Log.w(TAG, "ImageView context is null")
                setPlaceholder(imageView, placeholderResId)
                return
            }
            
            // Check if activity is destroyed
            if (context is android.app.Activity) {
                if (context.isDestroyed) {
                    android.util.Log.w(TAG, "Activity is destroyed")
                    return
                }
                if (context.isFinishing) {
                    android.util.Log.w(TAG, "Activity is finishing")
                    return
                }
            }
            
            if (url.isNullOrEmpty()) {
                setPlaceholder(imageView, placeholderResId)
                return
            }
            
            // Check if URL is SVG
            if (url.endsWith(".svg", ignoreCase = true)) {
                loadSvg(imageView, url, placeholderResId, errorResId, isCircular)
            } else {
                // Load regular image
                loadRegularImage(imageView, url, placeholderResId, errorResId, isCircular)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error in loadImage", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Load regular (non-SVG) images
     */
    private fun loadRegularImage(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?,
        isCircular: Boolean
    ) {
        try {
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
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading regular image: $url", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Load SVG image with proper circular crop support
     * ✅ ULTRA-SAFE: Multiple layers of protection against crashes
     */
    private fun loadSvg(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?,
        isCircular: Boolean
    ) {
        try {
            // Validate context again
            val context = imageView.context
            if (context == null || (context is android.app.Activity && (context.isDestroyed || context.isFinishing))) {
                android.util.Log.w(TAG, "Invalid context for SVG loading")
                return
            }
            
            // Set placeholder if provided
            setPlaceholder(imageView, placeholderResId)
            
            if (isCircular) {
                // Load SVG for circular transformation
                loadCircularSvg(imageView, url, errorResId)
            } else {
                // Load SVG normally
                loadNormalSvg(imageView, url, placeholderResId, errorResId)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error in loadSvg: $url", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Load circular SVG with maximum safety
     */
    private fun loadCircularSvg(
        imageView: ImageView,
        url: String,
        errorResId: Int?
    ) {
        try {
            val context = imageView.context ?: return
            
            val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(context)
                .`as`(PictureDrawable::class.java)
                .listener(SvgSoftwareLayerSetter())
            
            requestBuilder.load(url).into(object : CustomTarget<PictureDrawable>(DEFAULT_SIZE, DEFAULT_SIZE) {
                override fun onResourceReady(
                    resource: PictureDrawable,
                    transition: Transition<in PictureDrawable>?
                ) {
                    try {
                        // Check if view still has valid context
                        if (imageView.context == null) {
                            android.util.Log.w(TAG, "ImageView context became null during load")
                            return
                        }
                        
                        // Use handler to post to main thread safely
                        imageView.handler?.post {
                            processCircularSvg(imageView, resource, errorResId)
                        } ?: run {
                            // Fallback if handler is null
                            processCircularSvg(imageView, resource, errorResId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error in onResourceReady", e)
                        setErrorImage(imageView, errorResId)
                    }
                }
                
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    // Cleanup if needed
                }
                
                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    android.util.Log.w(TAG, "Failed to load SVG: $url")
                    setErrorImage(imageView, errorResId)
                }
            })
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading circular SVG: $url", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Process SVG into circular bitmap
     */
    private fun processCircularSvg(
        imageView: ImageView,
        resource: PictureDrawable,
        errorResId: Int?
    ) {
        try {
            // Get dimensions with multiple fallbacks
            val width = getValidDimension(
                imageView.width,
                imageView.measuredWidth,
                imageView.layoutParams?.width,
                resource.picture.width
            )
            
            val height = getValidDimension(
                imageView.height,
                imageView.measuredHeight,
                imageView.layoutParams?.height,
                resource.picture.height
            )
            
            android.util.Log.d(TAG, "Processing circular SVG with dimensions: ${width}x${height}")
            
            // Convert to bitmap
            val bitmap = pictureDrawableToBitmap(resource, width, height)
            
            if (bitmap != null && !bitmap.isRecycled) {
                // Apply circular mask
                val circularBitmap = getCircularBitmap(bitmap)
                
                if (circularBitmap != null && !circularBitmap.isRecycled) {
                    imageView.setImageBitmap(circularBitmap)
                    
                    // Clean up original bitmap if different
                    if (bitmap != circularBitmap && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } else {
                    android.util.Log.w(TAG, "Circular bitmap creation failed")
                    setErrorImage(imageView, errorResId)
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            } else {
                android.util.Log.w(TAG, "Bitmap conversion failed")
                setErrorImage(imageView, errorResId)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error processing circular SVG", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Load non-circular SVG
     */
    private fun loadNormalSvg(
        imageView: ImageView,
        url: String,
        placeholderResId: Int?,
        errorResId: Int?
    ) {
        try {
            val context = imageView.context ?: return
            
            val requestBuilder: RequestBuilder<PictureDrawable> = Glide.with(context)
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
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading normal SVG: $url", e)
            setErrorImage(imageView, errorResId)
        }
    }
    
    /**
     * Get valid dimension with multiple fallbacks
     */
    private fun getValidDimension(vararg dimensions: Int?): Int {
        for (dim in dimensions) {
            if (dim != null && dim > 0) {
                return dim
            }
        }
        return DEFAULT_SIZE
    }
    
    /**
     * Convert PictureDrawable to Bitmap with safety checks
     */
    private fun pictureDrawableToBitmap(
        pictureDrawable: PictureDrawable,
        width: Int,
        height: Int
    ): Bitmap? {
        return try {
            val picture = pictureDrawable.picture
            
            // Ensure dimensions are valid
            val bitmapWidth = maxOf(1, width)
            val bitmapHeight = maxOf(1, height)
            
            android.util.Log.d(TAG, "Creating bitmap: ${bitmapWidth}x${bitmapHeight}")
            
            // Create bitmap with validated size
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )
            
            // Draw picture onto bitmap
            val canvas = Canvas(bitmap)
            
            // Scale picture to fit bitmap if needed
            if (picture.width > 0 && picture.height > 0) {
                val scaleX = bitmapWidth.toFloat() / picture.width
                val scaleY = bitmapHeight.toFloat() / picture.height
                canvas.scale(scaleX, scaleY)
            }
            
            canvas.drawPicture(picture)
            
            bitmap
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(TAG, "OutOfMemoryError creating bitmap", e)
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error converting PictureDrawable to Bitmap", e)
            null
        }
    }
    
    /**
     * Apply circular mask to bitmap with safety checks
     */
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            if (bitmap.isRecycled) {
                android.util.Log.w(TAG, "Source bitmap is recycled")
                return null
            }
            
            val size = minOf(bitmap.width, bitmap.height)
            
            if (size <= 0) {
                android.util.Log.w(TAG, "Invalid size for circular bitmap: $size")
                return null
            }
            
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            
            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
            }
            
            // Draw circle
            val radius = size / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            
            // Apply source image using SRC_IN mode
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            
            // Center the source bitmap if it's not square
            val left = (size - bitmap.width) / 2f
            val top = (size - bitmap.height) / 2f
            canvas.drawBitmap(bitmap, left, top, paint)
            
            output
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(TAG, "OutOfMemoryError creating circular bitmap", e)
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error creating circular bitmap", e)
            null
        }
    }
    
    /**
     * Safely set placeholder image
     */
    private fun setPlaceholder(imageView: ImageView, placeholderResId: Int?) {
        try {
            if (placeholderResId != null) {
                imageView.setImageResource(placeholderResId)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting placeholder", e)
        }
    }
    
    /**
     * Safely set error image
     */
    private fun setErrorImage(imageView: ImageView, errorResId: Int?) {
        try {
            if (errorResId != null) {
                imageView.setImageResource(errorResId)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting error image", e)
        }
    }
}
