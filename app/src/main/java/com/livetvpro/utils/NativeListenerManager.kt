package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeListenerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun nativeShouldShowLink(pageType: String, uniqueId: String?): Boolean
    private external fun nativeGetDirectLinkUrl(): String
    private external fun nativeResetSessions()
    private external fun nativeIsConfigValid(): Boolean
    private external fun nativeGetContactUrl(): String
    private external fun nativeGetCricLiveUrl(): String
    private external fun nativeGetFootLiveUrl(): String
    private external fun nativeGetEmailUs(): String
    private external fun nativeGetWebUrl(): String
    private external fun nativeGetMessage(): String
    private external fun nativeGetMessageUrl(): String

    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        return try {
            val shouldShow = nativeShouldShowLink(pageType, uniqueId)
            if (shouldShow) {
                val url = nativeGetDirectLinkUrl()
                if (url.isNotEmpty()) {
                    openDirectLink(url)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun openDirectLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    fun resetSessions() {
        try {
            nativeResetSessions()
        } catch (e: Exception) {
        }
    }

    fun isConfigValid(): Boolean {
        return try {
            nativeIsConfigValid()
        } catch (e: Exception) {
            false
        }
    }

    fun getContactUrl(): String = try { nativeGetContactUrl() } catch (e: Exception) { "" }
    fun getCricLiveUrl(): String = try { nativeGetCricLiveUrl() } catch (e: Exception) { "" }
    fun getFootLiveUrl(): String = try { nativeGetFootLiveUrl() } catch (e: Exception) { "" }
    fun getEmailUs(): String = try { nativeGetEmailUs() } catch (e: Exception) { "" }
    fun getWebUrl(): String = try { nativeGetWebUrl() } catch (e: Exception) { "" }
    fun getMessage(): String = try { nativeGetMessage() } catch (e: Exception) { "" }
    fun getMessageUrl(): String = try { nativeGetMessageUrl() } catch (e: Exception) { "" }
}
