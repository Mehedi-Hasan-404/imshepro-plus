// app/src/main/java/com/livetvpro/LiveTVProApplication.kt
package com.livetvpro

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

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

        Timber.d("LiveTVPro Application Started")
    }
}
