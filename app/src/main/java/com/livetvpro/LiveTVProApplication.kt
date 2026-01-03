// File: app/src/main/java/com/livetvpro/LiveTVProApplication.kt
package com.livetvpro

import android.app.Application
import android.util.Log
import com.livetvpro.utils.ListenerManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {
    
    @Inject
    lateinit var listenerManager: ListenerManager

    override fun onCreate() {
        super.onCreate()

        // Set up crash handler for debugging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LiveTVPro", "UNCAUGHT EXCEPTION on thread: ${thread.name}", throwable)
            throwable.printStackTrace()
        }

        Log.d("LiveTVPro", "Application Started")
        
        // ListenerManager will initialize itself automatically via its init block 
        // simply by being injected here.
        Log.d("LiveTVPro", "ListenerManager injected and initializing...")
    }
}

