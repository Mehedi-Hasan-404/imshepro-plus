package com.livetvpro

import android.app.Application
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.data.repository.NativeDataRepository
import com.livetvpro.utils.DeviceUtils
import com.livetvpro.utils.FloatingPlayerManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {

    @Inject lateinit var dataRepository: NativeDataRepository
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var preferencesManager: PreferencesManager

    // App-scoped coroutine scope — lives for the lifetime of the process, no need to cancel
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        init {
            try {
                System.loadLibrary("native-lib")
            } catch (e: UnsatisfiedLinkError) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Detect device type once — available globally via DeviceUtils throughout the app
        DeviceUtils.init(this)

        FloatingPlayerManager.initialize(preferencesManager)

        themeManager.applyTheme()

        applicationScope.launch {
            try {
                // Try to restore cached data into native memory immediately (fast, no network)
                dataRepository.refreshData()
                // Then fetch fresh config in background for next time
                dataRepository.fetchRemoteConfig()
            } catch (e: Exception) {
            }
        }
    }
}
