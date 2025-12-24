// app/src/main/java/com/livetvpro/LiveTVProApplication.kt
package com.livetvpro

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.livetvpro.utils.FFmpegHelper
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@UnstableApi
@HiltAndroidApp
class LiveTVProApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set up crash handler for debugging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "UNCAUGHT EXCEPTION on thread: ${thread.name}")
            throwable.printStackTrace()
        }

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("   🚀 LiveTVPro Application Started")
        Timber.d("╚═══════════════════════════════════════════════════════╝")

        // Initialize and check FFmpeg support
        initializeFFmpeg()
    }

    private fun initializeFFmpeg() {
        try {
            // Check FFmpeg availability and log codec info
            FFmpegHelper.logFFmpegInfo(this)
            
            val ffmpegAvailable = FFmpegHelper.isFFmpegAvailable()
            if (ffmpegAvailable) {
                Timber.d("✅ FFmpeg extension loaded successfully")
                Timber.d("📹 Advanced codec support enabled:")
                Timber.d("   - H.265/HEVC decoding")
                Timber.d("   - VP9 decoding")
                Timber.d("   - Opus audio decoding")
                Timber.d("   - FLAC audio decoding")
                Timber.d("   - And more exotic codecs...")
            } else {
                Timber.w("⚠️ FFmpeg extension not available")
                Timber.w("📹 Will use platform decoders only")
                Timber.w("   Some advanced codecs may not work")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing FFmpeg")
            Timber.w("⚠️ Continuing without FFmpeg extension")
        }
    }
}
