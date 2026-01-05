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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Timber.plant(Timber.DebugTree())
        Timber.d("============================================")
        Timber.d("SplashActivity onCreate() started")
        Timber.d("============================================")
        
        try {
            Timber.d("Step 1: Checking if native library is loaded...")
            
            // Test native library
            try {
                val testResult = dataRepository.isDataLoaded()
                Timber.d("✅ Native library is working! isDataLoaded = $testResult")
                showToast("Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "❌ FATAL: Native library not loaded!")
                showErrorDialog("Native Library Error", "Native library failed to load: ${e.message}")
                return
            } catch (e: Exception) {
                Timber.e(e, "❌ Error testing native library")
                showErrorDialog("Native Library Error", "Error: ${e.message}")
                return
            }
            
            Timber.d("Step 2: Starting coroutine...")
            lifecycleScope.launch {
                try {
                    val startTime = System.currentTimeMillis()
                    Timber.d("Step 3: Inside coroutine, startTime = $startTime")

                    // 1. Fetch Remote Config
                    Timber.d("Step 4: Fetching Remote Config...")
                    try {
                        dataRepository.fetchRemoteConfig()
                        Timber.d("✅ Remote Config fetch completed")
                        showToast("Remote config loaded")
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error fetching Remote Config (continuing anyway)")
                        showToast("Remote config failed: ${e.message}")
                    }
                    
                    // 2. Download and parse data
                    Timber.d("Step 5: Downloading data...")
                    val dataJob = async { 
                        try {
                            val result = dataRepository.refreshData()
                            Timber.d("✅ Data refresh result: $result")
                            result
                        } catch (e: Exception) {
                            Timber.e(e, "❌ Error refreshing data")
                            false
                        }
                    }
                    
                    val dataLoaded = dataJob.await()
                    Timber.d("Step 6: Data loaded = $dataLoaded")
                    
                    if (!dataLoaded) {
                        Timber.w("⚠️ Data failed to load, but continuing anyway")
                        showToast("Warning: Data not loaded")
                    } else {
                        showToast("Data loaded successfully")
                    }

                    // 3. Minimum Splash Duration
                    Timber.d("Step 7: Calculating minimum splash duration...")
                    val elapsedTime = System.currentTimeMillis() - startTime
                    Timber.d("Elapsed time: ${elapsedTime}ms")
                    
                    if (elapsedTime < 1500) {
                        val delayTime = 1500 - elapsedTime
                        Timber.d("Waiting ${delayTime}ms more...")
                        delay(delayTime)
                    }

                    // 4. Start Main Activity
                    Timber.d("Step 8: Starting MainActivity...")
                    try {
                        val intent = Intent(this@SplashActivity, MainActivity::class.java)
                        startActivity(intent)
                        Timber.d("✅ MainActivity started successfully")
                        finish()
                    } catch (e: Exception) {
                        Timber.e(e, "❌ FATAL: Failed to start MainActivity")
                        showErrorDialog("Navigation Error", "Failed to start main screen: ${e.message}")
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "❌ FATAL: Coroutine crashed")
                    showErrorDialog("Startup Error", "App failed to start: ${e.message}\n\n${e.stackTraceToString()}")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "❌ FATAL: onCreate crashed")
            showErrorDialog("Critical Error", "App crashed on startup: ${e.message}\n\n${e.stackTraceToString()}")
        }
    }
    
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
                .setNegativeButton("Exit") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }
}
