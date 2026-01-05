package com.livetvpro

import android.app.Application
import com.livetvpro.utils.RemoteConfigManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {

    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager

    // Load native library globally
    companion object {
        init {
            try {
                System.loadLibrary("native-lib")
                Timber.d("✅ Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "❌ Native library failed to load")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("Application Started")

        // Initialize Remote Config (non-blocking)
        remoteConfigManager.fetchAndActivate()
    }
}
