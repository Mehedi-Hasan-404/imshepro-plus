package com.livetvpro

import android.app.Application
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LiveTVProApplication : Application() {

    @Inject
    lateinit var dataRepository: NativeDataRepository
    
    @Inject
    lateinit var themeManager: ThemeManager
    
    @Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        
        com.livetvpro.utils.FloatingPlayerManager.initialize(preferencesManager)
        
        themeManager.applyTheme()
        
        applicationScope.launch {
            try {
                dataRepository.fetchRemoteConfig()
            } catch (e: Exception) {
            }
        }
    }
}
