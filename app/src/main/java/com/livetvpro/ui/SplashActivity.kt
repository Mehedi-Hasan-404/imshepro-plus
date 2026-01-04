package com.livetvpro.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetvpro.MainActivity
import com.livetvpro.data.repository.DataRepository
import com.livetvpro.utils.RemoteConfigManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager
    
    @Inject
    lateinit var dataRepository: DataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            // 1. Fetch Remote Config URL
            remoteConfigManager.fetchAndActivate()
            
            // 2. Download and Parse JSON File
            val dataJob = async { dataRepository.refreshData() }
            dataJob.await()

            // 3. Minimum Splash Duration (1.5s)
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < 1500) {
                delay(1500 - elapsedTime)
            }

            // 4. Start Main Activity
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}

