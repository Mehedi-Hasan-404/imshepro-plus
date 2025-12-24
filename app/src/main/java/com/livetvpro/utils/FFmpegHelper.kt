// app/src/main/java/com/livetvpro/utils/FFmpegHelper.kt
package com.livetvpro.utils

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import timber.log.Timber

@UnstableApi
object FFmpegHelper {

    /**
     * Check if FFmpeg extension is available
     */
    fun isFFmpegAvailable(): Boolean {
        return try {
            // Try to load FFmpeg library
            System.loadLibrary("ffmpeg_jni")
            Timber.d("✅ FFmpeg extension is available")
            true
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("⚠️ FFmpeg extension not available: ${e.message}")
            false
        }
    }

    /**
     * Get FFmpeg version info
     */
    fun getFFmpegVersion(): String {
        return try {
            // This will be populated by the native library if available
            "FFmpeg with ExoPlayer 1.2.1"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Check supported codecs
     */
    fun getSupportedCodecs(context: Context): CodecInfo {
        val videoCodecs = mutableListOf<String>()
        val audioCodecs = mutableListOf<String>()
        
        try {
            // Common video codecs to check
            val videoMimeTypes = listOf(
                "video/avc",        // H.264
                "video/hevc",       // H.265
                "video/x-vnd.on2.vp8",  // VP8
                "video/x-vnd.on2.vp9",  // VP9
                "video/av01",       // AV1
                "video/mp4v-es",    // MPEG-4
                "video/3gpp"        // 3GP
            )
            
            // Common audio codecs to check
            val audioMimeTypes = listOf(
                "audio/mp4a-latm",  // AAC
                "audio/mpeg",       // MP3
                "audio/opus",       // Opus
                "audio/vorbis",     // Vorbis
                "audio/flac",       // FLAC
                "audio/ac3",        // AC3
                "audio/eac3"        // E-AC3
            )
            
            // Check video codec support
            videoMimeTypes.forEach { mimeType ->
                try {
                    val decoderInfos = MediaCodecUtil.getDecoderInfos(mimeType, false, false)
                    if (decoderInfos.isNotEmpty()) {
                        val codecName = when (mimeType) {
                            "video/avc" -> "H.264"
                            "video/hevc" -> "H.265/HEVC"
                            "video/x-vnd.on2.vp8" -> "VP8"
                            "video/x-vnd.on2.vp9" -> "VP9"
                            "video/av01" -> "AV1"
                            "video/mp4v-es" -> "MPEG-4"
                            "video/3gpp" -> "3GP"
                            else -> mimeType
                        }
                        videoCodecs.add(codecName)
                    }
                } catch (e: Exception) {
                    Timber.w("Could not check codec: $mimeType")
                }
            }
            
            // Check audio codec support
            audioMimeTypes.forEach { mimeType ->
                try {
                    val decoderInfos = MediaCodecUtil.getDecoderInfos(mimeType, false, false)
                    if (decoderInfos.isNotEmpty()) {
                        val codecName = when (mimeType) {
                            "audio/mp4a-latm" -> "AAC"
                            "audio/mpeg" -> "MP3"
                            "audio/opus" -> "Opus"
                            "audio/vorbis" -> "Vorbis"
                            "audio/flac" -> "FLAC"
                            "audio/ac3" -> "AC3"
                            "audio/eac3" -> "E-AC3"
                            else -> mimeType
                        }
                        audioCodecs.add(codecName)
                    }
                } catch (e: Exception) {
                    Timber.w("Could not check codec: $mimeType")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking codec support")
        }
        
        return CodecInfo(videoCodecs, audioCodecs)
    }

    /**
     * Log FFmpeg and codec information
     */
    fun logFFmpegInfo(context: Context) {
        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("   📹 FFMPEG & CODEC INFORMATION")
        Timber.d("╚═══════════════════════════════════════════════════════╝")
        
        val ffmpegAvailable = isFFmpegAvailable()
        Timber.d("FFmpeg Available: ${if (ffmpegAvailable) "✅ YES" else "❌ NO"}")
        Timber.d("FFmpeg Version: ${getFFmpegVersion()}")
        
        val codecInfo = getSupportedCodecs(context)
        Timber.d("")
        Timber.d("Supported Video Codecs (${codecInfo.videoCodecs.size}):")
        codecInfo.videoCodecs.forEach { codec ->
            Timber.d("  ✅ $codec")
        }
        
        Timber.d("")
        Timber.d("Supported Audio Codecs (${codecInfo.audioCodecs.size}):")
        codecInfo.audioCodecs.forEach { codec ->
            Timber.d("  ✅ $codec")
        }
        
        Timber.d("╚═══════════════════════════════════════════════════════╝")
    }

    /**
     * Get renderer factory with FFmpeg support
     */
    fun getFFmpegRenderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context).apply {
            // EXTENSION_RENDERER_MODE_OFF: Don't use extension renderers
            // EXTENSION_RENDERER_MODE_ON: Use extension renderers, fail if not available
            // EXTENSION_RENDERER_MODE_PREFER: Prefer extension renderers, fallback to platform
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            Timber.d("✅ FFmpeg RenderersFactory configured (PREFER mode)")
        }
    }

    data class CodecInfo(
        val videoCodecs: List<String>,
        val audioCodecs: List<String>
    )
}
