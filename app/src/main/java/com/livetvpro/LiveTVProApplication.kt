// app/src/main/java/com/livetvpro/LiveTVProApplication.kt
package com.livetvpro

import android.app.Application
import android.util.Log
import com.livetvpro.utils.RemoteConfigManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {

    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LiveTVPro", "UNCAUGHT EXCEPTION on thread: ${thread.name}", throwable)
            throwable.printStackTrace()
        }

        Log.d("LiveTVPro", "🚀 Application Started")

        initializeRemoteConfig()
    }

    private fun initializeRemoteConfig() {
        applicationScope.launch {
            try {
                Log.d("LiveTVPro", "⏳ Initializing Firebase Remote Config...")

                val success = remoteConfigManager.fetchAndActivate()

                if (success) {
                    val baseUrl = remoteConfigManager.getBaseUrl()
                    Log.d("LiveTVPro", "✅ Remote Config ready")
                    Log.d("LiveTVPro", "📡 Base URL: $baseUrl")
                } else {
                    Log.w("LiveTVPro", "⚠️ Using cached/default Remote Config")
                }

            } catch (e: Exception) {
                Log.e("LiveTVPro", "❌ Failed to initialize Remote Config", e)
            }
        }
    }
}
