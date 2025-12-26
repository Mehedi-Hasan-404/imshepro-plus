// app/src/main/java/com/livetvpro/LiveTVProApplication.kt
package com.livetvpro

import android.app.Application
import com.livetvpro.utils.ListenerManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {
    
    @Inject
    lateinit var listenerManager: ListenerManager
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        Timber.d("LiveTVPro Application Started")
        
        // Initialize listener manager
        applicationScope.launch {
            try {
                listenerManager.initialize()
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize listener manager")
            }
        }
    }
}
