package com.livetvpro.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetvpro.MainActivity
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var dataRepository: NativeDataRepository
    
    // Native method to get URL from native memory
    private external fun nativeGetConfigUrl(): String

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            // Wait a moment for Application to fetch config
            delay(500)
            
            // Get URL from native memory (stored by Application)
            val dataUrl = nativeGetConfigUrl()
            
            if (dataUrl.isEmpty()) {
                Timber.e("❌ Config URL not ready")
                // Retry or show error
                delay(1000)
            }
            
            // Download data
            val dataJob = async { dataRepository.refreshData(dataUrl) }
            dataJob.await()

            // Minimum Splash Duration (1.5s)
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < 1500) {
                delay(1500 - elapsedTime)
            }

            // Start Main Activity
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
