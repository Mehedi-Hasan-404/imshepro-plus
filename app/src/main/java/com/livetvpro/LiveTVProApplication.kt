package com.livetvpro

import android.app.Application
import com.livetvpro.data.repository.NativeDataRepository
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
    lateinit var dataRepository: NativeDataRepository
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        // Fetch Remote Config (non-blocking)
        applicationScope.launch {
            dataRepository.fetchRemoteConfig()
        }
    }
}
