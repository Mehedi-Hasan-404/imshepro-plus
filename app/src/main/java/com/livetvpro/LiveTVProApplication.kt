package com.livetvpro

import android.app.Application
import android.widget.Toast
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
                Timber.plant(Timber.DebugTree())
                Timber.d("============================================")
                Timber.d("Loading native library...")
                Timber.d("============================================")
                
                System.loadLibrary("native-lib")
                
                Timber.d("✅✅✅ Native library loaded successfully ✅✅✅")
                
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "❌❌❌ FATAL: Native library failed to load ❌❌❌")
                Timber.e("Error details: ${e.message}")
                Timber.e("Stack trace: ${e.stackTraceToString()}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Unexpected error loading native library")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            Timber.d("============================================")
            Timber.d("Application onCreate() started")
            Timber.d("============================================")
            
            // Always plant Timber tree
            if (Timber.treeCount() == 0) {
                Timber.plant(Timber.DebugTree())
            }

            Timber.d("Application Started - Debug Build: ${BuildConfig.DEBUG}")
            Timber.d("Package name: ${packageName}")
            Timber.d("Version: ${BuildConfig.VERSION_NAME}")

            // Test if Hilt injection worked
            try {
                Timber.d("Testing dataRepository injection...")
                val isLoaded = dataRepository.isDataLoaded()
                Timber.d("✅ DataRepository injection successful! isDataLoaded = $isLoaded")
                
                // Show toast on main thread
                applicationScope.launch {
                    Toast.makeText(this@LiveTVProApplication, "App initialized successfully", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Timber.e(e, "❌ DataRepository test failed")
                applicationScope.launch {
                    Toast.makeText(this@LiveTVProApplication, "Warning: Data repository error", Toast.LENGTH_LONG).show()
                }
            }

            // Fetch Remote Config (non-blocking)
            Timber.d("Starting background Remote Config fetch...")
            applicationScope.launch {
                try {
                    dataRepository.fetchRemoteConfig()
                    Timber.d("✅ Background Remote Config fetch completed")
                } catch (e: Exception) {
                    Timber.e(e, "❌ Background Remote Config fetch failed (non-critical)")
                }
            }
            
            Timber.d("============================================")
            Timber.d("Application onCreate() completed successfully")
            Timber.d("============================================")
            
        } catch (e: Exception) {
            Timber.e(e, "❌❌❌ FATAL: Application onCreate() crashed ❌❌❌")
            Timber.e("Error: ${e.message}")
            Timber.e("Stack trace: ${e.stackTraceToString()}")
            
            // Try to show error to user
            try {
                applicationScope.launch {
                    Toast.makeText(this@LiveTVProApplication, "FATAL ERROR: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (toastError: Exception) {
                Timber.e(toastError, "Could not even show error toast")
            }
        }
    }
}
