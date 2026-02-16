package com.livetvpro.ui

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.livetvpro.BuildConfig
import com.livetvpro.MainActivity
import com.livetvpro.R
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject
    lateinit var dataRepository: NativeDataRepository

    private lateinit var signalLoader: View
    private lateinit var bar1: View
    private lateinit var bar2: View
    private lateinit var bar3: View
    private lateinit var bar4: View
    private lateinit var bar5: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var versionText: TextView

    private val barAnimators = mutableListOf<android.animation.Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        signalLoader = findViewById(R.id.signal_loader)
        bar1 = findViewById(R.id.bar1)
        bar2 = findViewById(R.id.bar2)
        bar3 = findViewById(R.id.bar3)
        bar4 = findViewById(R.id.bar4)
        bar5 = findViewById(R.id.bar5)
        errorText = findViewById(R.id.error_text)
        retryButton = findViewById(R.id.retry_button)
        versionText = findViewById(R.id.version_text)

        versionText.text = "VERSION ${BuildConfig.VERSION_NAME}"

        retryButton.setOnClickListener { startFetch() }

        startFetch()
    }

    private fun startFetch() {
        showLoading()
        lifecycleScope.launch {
            val success = fetchData()
            if (success) {
                navigateToMain()
            } else {
                showRetry("Connection error")
            }
        }
    }

    private suspend fun fetchData(): Boolean {
        return try {
            val configFetched = dataRepository.fetchRemoteConfig()
            if (!configFetched) return false
            dataRepository.refreshData()
        } catch (e: Exception) {
            false
        }
    }

    private fun showLoading() {
        signalLoader.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        startBarAnimations()
    }

    private fun showRetry(message: String) {
        stopBarAnimations()
        signalLoader.visibility = View.GONE
        errorText.text = message
        errorText.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
    }

    private fun startBarAnimations() {
        val bars = listOf(bar1, bar2, bar3, bar4, bar5)
        val animRes = listOf(
            R.anim.signal_bar1,
            R.anim.signal_bar2,
            R.anim.signal_bar3,
            R.anim.signal_bar4,
            R.anim.signal_bar5
        )
        barAnimators.clear()
        bars.forEachIndexed { i, bar ->
            val anim = AnimatorInflater.loadAnimator(this, animRes[i])
            anim.setTarget(bar)
            anim.start()
            barAnimators.add(anim)
        }
    }

    private fun stopBarAnimations() {
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
        listOf(bar1, bar2, bar3, bar4, bar5).forEach { it.scaleY = 1f }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
