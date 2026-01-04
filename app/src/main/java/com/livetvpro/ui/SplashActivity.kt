// app/src/main/java/com/livetvpro/ui/SplashActivity.kt
package com.livetvpro.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetvpro.MainActivity
import com.livetvpro.utils.RemoteConfigManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // No setContentView() needed if you just want a blank/theme background.
        // If you want a logo, create a layout file (activity_splash.xml) and set it here.

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            // 1. Fetch the Base URL from Firebase
            // This ensures the URL is ready BEFORE the main app starts
            remoteConfigManager.fetchAndActivate()

            // 2. Wait minimum time (e.g. 1.5 seconds) for branding visibility
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < 1500) {
                delay(1500 - elapsedTime)
            }

            // 3. Launch Main Activity
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish() // Prevent user from going back to splash
        }
    }
}
