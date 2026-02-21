package com.livetvpro.utils

import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.ImageViewTarget
import com.bumptech.glide.request.target.Target

/**
 * Listener which updates the ImageView to be software rendered, because
 * SVG can't render on a hardware backed Canvas.
 *
 * Crash fix: previously used a hard `as ImageViewTarget` cast which would throw
 * ClassCastException if the target was ever not an ImageViewTarget.
 * Now uses a safe `as?` cast with a null check.
 */
class SvgSoftwareLayerSetter : RequestListener<PictureDrawable> {

    override fun onLoadFailed(
        e: GlideException?,
        model: Any?,
        target: Target<PictureDrawable>,
        isFirstResource: Boolean
    ): Boolean {
        (target as? ImageViewTarget<*>)?.view
            ?.setLayerType(ImageView.LAYER_TYPE_NONE, null)
        return false
    }

    override fun onResourceReady(
        resource: PictureDrawable,
        model: Any,
        target: Target<PictureDrawable>?,
        dataSource: DataSource,
        isFirstResource: Boolean
    ): Boolean {
        (target as? ImageViewTarget<*>)?.view
            ?.setLayerType(ImageView.LAYER_TYPE_SOFTWARE, null)
        return false
    }
}
