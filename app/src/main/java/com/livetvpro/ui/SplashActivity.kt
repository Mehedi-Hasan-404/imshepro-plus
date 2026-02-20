package com.livetvpro.ui

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.livetvpro.BuildConfig
import com.livetvpro.MainActivity
import com.livetvpro.R
import com.livetvpro.data.repository.NativeDataRepository
import com.livetvpro.utils.DeviceUtils
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    @Inject lateinit var dataRepository: NativeDataRepository
    @Inject lateinit var listenerManager: NativeListenerManager

    private lateinit var splashScreen: View
    private lateinit var signalLoader: View
    private lateinit var bar1: View
    private lateinit var bar2: View
    private lateinit var bar3: View
    private lateinit var bar4: View
    private lateinit var bar5: View
    private lateinit var errorText: TextView
    private lateinit var buttonsRow: LinearLayout
    private lateinit var retryButton: MaterialButton
    private lateinit var versionText: TextView

    private lateinit var updateScreen: View
    private lateinit var btnPrimaryAction: MaterialButton
    private lateinit var btnDownloadWebsite: MaterialButton
    private lateinit var btnUpdateLater: MaterialButton
    private lateinit var updateProgress: ProgressBar
    private lateinit var tvProgress: TextView

    private val barAnimators = mutableListOf<android.animation.Animator>()

    private var downloadCancelled = false
    private var isDownloading = false
    private var downloadedApk: File? = null
    private var cachedWebUrl: String = ""

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }

    // Modern replacement for deprecated startActivityForResult
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = downloadedApk
        if (apk != null && apk.exists()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                launchInstaller(apk)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // layout-television is auto-selected on Android TV via UI_MODE_TYPE_TELEVISION.
        // Fire TV (older Fire OS) reports UI_MODE_TYPE_NORMAL so we force the TV layout manually.
        if (DeviceUtils.isTvDevice) {
            setContentView(R.layout.activity_splash_tv)
        } else {
            setContentView(R.layout.activity_splash)
        }

        val bergenSans = ResourcesCompat.getFont(this, R.font.bergen_sans)

        splashScreen = findViewById(R.id.splash_screen)
        signalLoader = findViewById(R.id.signal_loader)
        bar1 = findViewById(R.id.bar1)
        bar2 = findViewById(R.id.bar2)
        bar3 = findViewById(R.id.bar3)
        bar4 = findViewById(R.id.bar4)
        bar5 = findViewById(R.id.bar5)
        errorText = findViewById(R.id.error_text)
        buttonsRow = findViewById(R.id.buttons_row)
        retryButton = findViewById(R.id.retry_button)
        versionText = findViewById(R.id.version_text)

        updateScreen = findViewById(R.id.update_screen)
        btnPrimaryAction = findViewById(R.id.btn_primary_action)
        btnDownloadWebsite = findViewById(R.id.btn_download_website)
        btnUpdateLater = findViewById(R.id.btn_update_later)
        updateProgress = findViewById(R.id.update_progress)
        tvProgress = findViewById(R.id.tv_progress)

        errorText.typeface = bergenSans
        retryButton.typeface = bergenSans
        versionText.typeface = bergenSans
        tvProgress.typeface = bergenSans
        btnPrimaryAction.typeface = bergenSans
        btnDownloadWebsite.typeface = bergenSans
        btnUpdateLater.typeface = bergenSans

        versionText.text = "VERSION ${BuildConfig.VERSION_NAME}"

        retryButton.setOnClickListener { startFetch() }

        btnUpdateLater.setOnClickListener { finishAndRemoveTask() }
        btnDownloadWebsite.setOnClickListener {
            val url = listenerManager.getWebUrl().ifBlank { cachedWebUrl }
            if (url.isNotBlank()) openUrl(url)
        }
        btnPrimaryAction.setOnClickListener {
            when {
                isDownloading -> cancelDownload()
                downloadedApk?.exists() == true -> installApk(downloadedApk!!)
                else -> startDownload()
            }
        }

        // On TV, APK sideloading is not supported — hide the in-app update button
        if (DeviceUtils.isTvDevice) {
            btnPrimaryAction.visibility = View.GONE
        }

        // Modern replacement for deprecated onBackPressed override
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (updateScreen.visibility == View.VISIBLE) {
                    finishAndRemoveTask()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        startFetch()
    }

    private fun startFetch() {
        showLoading()
        lifecycleScope.launch {
            val success = fetchData()
            if (success) {
                // Cache the web URL now that native data is loaded
                val url = listenerManager.getWebUrl()
                if (url.isNotBlank()) cachedWebUrl = url
                if (isUpdateRequired()) {
                    showUpdateScreen()
                } else {
                    navigateToMain()
                }
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

    private fun isUpdateRequired(): Boolean {
        return try {
            val remote = listenerManager.getAppVersion().trim()
            if (remote.isEmpty()) return false
            compareVersions(remote, BuildConfig.VERSION_NAME.trim()) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val diff = (p1.getOrElse(i) { 0 }) - (p2.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun showLoading() {
        splashScreen.visibility = View.VISIBLE
        updateScreen.visibility = View.GONE
        signalLoader.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        buttonsRow.visibility = View.GONE
        startBarAnimations()
    }

    private fun showRetry(message: String) {
        stopBarAnimations()
        signalLoader.visibility = View.GONE
        errorText.text = message
        errorText.visibility = View.VISIBLE
        buttonsRow.visibility = View.VISIBLE
    }

    private fun showUpdateScreen() {
        stopBarAnimations()
        splashScreen.visibility = View.GONE
        updateScreen.visibility = View.VISIBLE
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun startBarAnimations() {
        val bars = listOf(bar1, bar2, bar3, bar4, bar5)
        val animRes = listOf(
            R.anim.signal_bar1, R.anim.signal_bar2, R.anim.signal_bar3,
            R.anim.signal_bar4, R.anim.signal_bar5
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

    override fun onStop() {
        super.onStop()
        // Stop animations when activity goes to background to avoid leaking against detached views
        stopBarAnimations()
    }

    private fun startDownload() {
        val url = listenerManager.getDownloadUrl()
        if (url.isBlank()) return
        isDownloading = true
        downloadCancelled = false
        btnPrimaryAction.text = "CANCEL"
        updateProgress.progress = 0
        updateProgress.visibility = View.VISIBLE
        tvProgress.text = "Preparing…"
        tvProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { downloadApk(url) }
            isDownloading = false
            if (result != null) {
                downloadedApk = result
                updateProgress.visibility = View.INVISIBLE
                tvProgress.visibility = View.INVISIBLE
                btnPrimaryAction.text = "INSTALL"
                installApk(result)
            } else {
                updateProgress.visibility = View.INVISIBLE
                tvProgress.visibility = View.INVISIBLE
                btnPrimaryAction.text = "UPDATE APP"
            }
        }
    }

    private fun cancelDownload() {
        downloadCancelled = true
        isDownloading = false
        btnPrimaryAction.text = "UPDATE APP"
        updateProgress.visibility = View.INVISIBLE
        tvProgress.visibility = View.INVISIBLE
    }

    @SuppressLint("SetTextI18n")
    private suspend fun downloadApk(url: String): File? {
        return try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
            val apkFile = File(dir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val connection = (URL(url).openConnection()).also {
                it.connectTimeout = 15_000
                it.readTimeout = 30_000
                it.connect()
            }
            val totalBytes = connection.contentLength.toLong()

            connection.getInputStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (downloadCancelled) { apkFile.delete(); return null }
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                        val dlMb = "%.1f".format(downloaded / 1_048_576.0)
                        val totMb = if (totalBytes > 0) "%.1f".format(totalBytes / 1_048_576.0) else "?"
                        withContext(Dispatchers.Main) {
                            updateProgress.progress = pct
                            tvProgress.text = "$dlMb MB / $totMb MB    $pct%"
                        }
                    }
                }
            }
            apkFile
        } catch (e: Exception) {
            null
        }
    }

    private fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    installPermissionLauncher.launch(intent)
                    return
                }
            }
            launchInstaller(apkFile)
        } catch (e: Exception) { }
    }

    private fun launchInstaller(apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
    }
}
