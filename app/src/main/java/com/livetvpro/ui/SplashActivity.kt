package com.livetvpro.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetvpro.MainActivity
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var dataRepository: NativeDataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Timber.d("============================================")
        Timber.d("SplashActivity onCreate() started")
        Timber.d("============================================")
        
        try {
            // Test native library
            try {
                val testResult = dataRepository.isDataLoaded()
                Timber.d("✅ Native library is working! isDataLoaded = $testResult")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error testing native library")
                // Don't fail - continue anyway
            }
            
            lifecycleScope.launch {
                try {
                    val startTime = System.currentTimeMillis()

                    // 1. Fetch Remote Config
                    Timber.d("📡 Fetching Remote Config...")
                    try {
                        val configSuccess = dataRepository.fetchRemoteConfig()
                        if (configSuccess) {
                            Timber.d("✅ Remote Config loaded")
                        } else {
                            Timber.w("⚠️ Remote Config failed (continuing anyway)")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error fetching Remote Config (continuing anyway)")
                    }
                    
                    // 2. Download and parse data (non-blocking - let it fail gracefully)
                    Timber.d("📥 Attempting to download data...")
                    try {
                        val dataLoaded = dataRepository.refreshData()
                        if (dataLoaded) {
                            Timber.d("✅✅✅ Data loaded successfully")
                        } else {
                            Timber.w("⚠️⚠️⚠️ Data failed to load (app will continue with empty data)")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error refreshing data (app will continue)")
                    }

                    // 3. Minimum Splash Duration
                    val elapsedTime = System.currentTimeMillis() - startTime
                    if (elapsedTime < 1500) {
                        delay(1500 - elapsedTime)
                    }

                    // 4. Start Main Activity (ALWAYS - even if data failed)
                    Timber.d("🚀 Starting MainActivity...")
                    try {
                        val intent = Intent(this@SplashActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                        Timber.d("✅ MainActivity started successfully")
                    } catch (e: Exception) {
                        Timber.e(e, "❌ FATAL: Failed to start MainActivity")
                        showErrorDialog("Navigation Error", "Failed to start main screen: ${e.message}")
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "❌ FATAL: Splash coroutine crashed")
                    Timber.e("Stack trace: ${e.stackTraceToString()}")
                    
                    // Try to start MainActivity anyway
                    try {
                        val intent = Intent(this@SplashActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } catch (e2: Exception) {
                        showErrorDialog("Critical Error", "App failed to start: ${e.message}\n\n${e.stackTraceToString()}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "❌ FATAL: onCreate crashed")
            Timber.e("Stack trace: ${e.stackTraceToString()}")
            showErrorDialog("Critical Error", "App crashed on startup: ${e.message}\n\n${e.stackTraceToString()}")
        }
    }
    
    private fun showErrorDialog(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Copy Error") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Error", message)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Continue Anyway") { _, _ ->
                    try {
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot start app", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                .setCancelable(false)
                .show()
        }
    }
}
