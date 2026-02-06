package com.livetvpro.data.repository

import android.content.Context
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.models.EventCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeDataRepository @Inject constructor(
    @ApplicationContext private val a: Context,
    private val b: OkHttpClient,
    private val c: Gson
) {
    companion object {
        private var d = false
        init {
            try {
                System.loadLibrary("native-lib")
                d = true
            } catch (e: UnsatisfiedLinkError) {
                d = false
            } catch (e: Exception) {
                d = false
            }
        }
    }

    private external fun e(): Boolean
    private external fun f(): String
    private external fun g(h: String)
    private external fun i(): String
    private external fun j(k: String): Boolean
    private external fun l(): String
    private external fun m(): String
    private external fun n(): String
    private external fun o(): Boolean
    private external fun p(): String
    private external fun q(): String

    private fun r(): Boolean {
        return try {
            if (!d) return true
            e()
        } catch (s: Throwable) {
            true
        }
    }

    private fun t(): String {
        return try {
            if (!d) return "data_file_url"
            f()
        } catch (u: Throwable) {
            "data_file_url"
        }
    }

    private fun v(w: String) {
        try {
            if (!d) return
            g(w)
        } catch (x: Throwable) {
        }
    }

    private fun y(): String {
        return try {
            if (!d) return ""
            i()
        } catch (z: Throwable) {
            ""
        }
    }

    private fun aa(ab: String): Boolean {
        return try {
            if (!d) return false
            j(ab)
        } catch (ac: Throwable) {
            false
        }
    }

    private fun ad(): String {
        return try {
            if (!d) return "[]"
            l()
        } catch (ae: Throwable) {
            "[]"
        }
    }

    private fun af(): String {
        return try {
            if (!d) return "[]"
            m()
        } catch (ag: Throwable) {
            "[]"
        }
    }

    private fun ah(): String {
        return try {
            if (!d) return "[]"
            n()
        } catch (ai: Throwable) {
            "[]"
        }
    }

    private fun aj(): Boolean {
        return try {
            if (!d) return false
            o()
        } catch (ak: Throwable) {
            false
        }
    }

    private fun al(): String {
        return try {
            if (!d) return "[]"
            p()
        } catch (am: Throwable) {
            "[]"
        }
    }

    private fun an(): String {
        return try {
            if (!d) return "[]"
            q()
        } catch (ao: Throwable) {
            "[]"
        }
    }

    private val ap = Mutex()
    private val aq = Firebase.remoteConfig

    init {
        try {
            val ar = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (av()) 0L else 3600L
            }
            aq.setConfigSettingsAsync(ar)
            
            try {
                val as = t()
                aq.setDefaultsAsync(mapOf(as to ""))
            } catch (at: Exception) {
            }
        } catch (au: Exception) {
        }
    }

    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            val aw = aq.fetchAndActivate().await()
            val ax = t()
            val ay = aq.getString(ax)
            
            if (ay.isNotEmpty()) {
                v(ay)
                return@withContext true
            } else {
                return@withContext false
            }
        } catch (az: Exception) {
            return@withContext false
        }
    }

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        ap.withLock {
            try {
                if (!r()) {
                    return@withContext false
                }
                
                val ba = y()
                if (ba.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(a, "Configuration URL not found", Toast.LENGTH_LONG).show()
                    }
                    return@withContext false
                }
                
                val bb = Request.Builder().url(ba).build()
                b.newCall(bb).execute().use { bc ->
                    if (!bc.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(a, "Server error: ${bc.code}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                    
                    val bd = bc.body?.string()
                    if (bd.isNullOrBlank()) {
                        return@withContext false
                    }
                    
                    val be = aa(bd)
                    if (be) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(a, "Data loaded successfully", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext true
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(a, "Failed to process data", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                }
            } catch (bf: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(a, "Network error: ${bf.message}", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
        }
    }

    fun getCategories(): List<Category> {
        return try {
            val bg = ad()
            if (bg.isEmpty() || bg == "[]") return emptyList()
            c.fromJson(bg, Array<Category>::class.java).toList()
        } catch (bh: Exception) {
            emptyList()
        }
    }

    fun getChannels(): List<Channel> {
        return try {
            val bi = af()
            if (bi.isEmpty() || bi == "[]") return emptyList()
            c.fromJson(bi, Array<Channel>::class.java).toList()
        } catch (bj: Exception) {
            emptyList()
        }
    }

    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val bk = ah()
            if (bk.isEmpty() || bk == "[]") return emptyList()
            c.fromJson(bk, Array<LiveEvent>::class.java).toList()
        } catch (bl: Exception) {
            emptyList()
        }
    }

    fun getEventCategories(): List<EventCategory> {
        return try {
            val bm = al()
            if (bm.isEmpty() || bm == "[]") return emptyList()
            c.fromJson(bm, Array<EventCategory>::class.java).toList()
        } catch (bn: Exception) {
            emptyList()
        }
    }

    fun getSports(): List<Channel> {
        return try {
            val bo = an()
            if (bo.isEmpty() || bo == "[]") return emptyList()
            c.fromJson(bo, Array<Channel>::class.java).toList()
        } catch (bp: Exception) {
            emptyList()
        }
    }

    fun isDataLoaded(): Boolean {
        return aj()
    }

    private fun av(): Boolean {
        return a.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
