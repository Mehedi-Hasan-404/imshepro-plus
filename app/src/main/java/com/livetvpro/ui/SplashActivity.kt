package com.livetvpro.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.livetvpro.MainActivity
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var dataRepository: NativeDataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            try {
                dataRepository.isDataLoaded()
            } catch (e: Exception) {
            }
            
            lifecycleScope.launch {
                try {
                    val startTime = System.currentTimeMillis()

                    try {
                        dataRepository.fetchRemoteConfig()
                    } catch (e: Exception) {
                    }
                    
                    try {
                        dataRepository.refreshData()
                    } catch (e: Exception) {
                    }

                    val elapsedTime = System.currentTimeMillis() - startTime
                    if (elapsedTime < 1500) {
                        delay(1500 - elapsedTime)
                    }

                    try {
                        val intent = Intent(this@SplashActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        showErrorDialog("Navigation Error", "Failed to start main screen: ${e.message}")
                    }
                    
                } catch (e: Exception) {
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
                }
                .setNegativeButton("Continue Anyway") { _, _ ->
                    try {
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        finish()
                    }
                }
                .setCancelable(false)
                .show()
        }
    }
}
