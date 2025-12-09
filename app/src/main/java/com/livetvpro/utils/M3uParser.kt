package com.livetvpro.utils

import com.livetvpro.data.models.Channel
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object M3uParser {

    data class M3uChannel(
        val name: String,
        val logoUrl: String,
        val streamUrl: String,
        val groupTitle: String = "",
        val userAgent: String? = null,
        val cookies: Map<String, String> = emptyMap(),
        val httpHeaders: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseKey: String? = null
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            if (m3uUrl.trim().startsWith("[") || m3uUrl.trim().startsWith("{")) {
                Timber.d("Detected JSON format playlist")
                return parseJsonPlaylist(m3uUrl)
            }
            
            val url = URL(m3uUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "LiveTVPro/1.0")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()
                connection.disconnect()

                val trimmedContent = content.trim()
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    Timber.d("Response is JSON format")
                    return parseJsonPlaylist(trimmedContent)
                }

                parseM3uContent(content)
            } else {
                Timber.e("Failed to fetch M3U: HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing M3U from URL: $m3uUrl")
            emptyList()
        }
    }

    fun parseJsonPlaylist(jsonContent: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        
        try {
            val jsonArray = JSONArray(jsonContent)
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                
                val name = item.optString("name", "Unknown Channel")
                val link = item.optString("link", "")
                val logo = item.optString("logo", "")
                val cookie = item.optString("cookie", "")
                val userAgent = item.optString("user-agent", null)
                val referer = item.optString("referer", null)
                val origin = item.optString("origin", null)
                
                if (link.isNotEmpty()) {
                    val headers = mutableMapOf<String, String>()
                    
                    if (cookie.isNotEmpty()) {
                        headers["Cookie"] = cookie
                        Timber.d("📋 Stored Cookie header: ${cookie.take(80)}...")
                    }
                    
                    referer?.let { headers["Referer"] = it }
                    origin?.let { headers["Origin"] = it }
                    
                    channels.add(
                        M3uChannel(
                            name = name,
                            logoUrl = logo,
                            streamUrl = link,
                            groupTitle = "",
                            userAgent = userAgent,
                            cookies = emptyMap(),
                            httpHeaders = headers
                        )
                    )
                    
                    Timber.d("✅ Parsed JSON channel: $name with Cookie header")
                }
            }
            
            Timber.d("Parsed ${channels.size} channels from JSON playlist")
        } catch (e: Exception) {
            Timber.e(e, "Error parsing JSON playlist")
        }
        
        return channels
    }

    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()

        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            Timber.e("Invalid M3U file format")
            return emptyList()
        }

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentUserAgent: String? = null
        var currentHeaders = mutableMapOf<String, String>()
        var currentDrmScheme: String? = null
        var currentDrmLicenseKey: String? = null

        for (i in lines.indices) {
            val line = lines[i].trim()

            when {
                line.startsWith("#EXTINF:") -> {
                    currentName = extractChannelName(line)
                    currentLogo = extractAttribute(line, "tvg-logo")
                    currentGroup = extractAttribute(line, "group-title")
                }
                
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = line.substringAfter("http-user-agent=").trim()
                    Timber.d("📡 Parsed user-agent: $currentUserAgent")
                }
                
                line.startsWith("#EXTVLCOPT:http-origin=") -> {
                    val origin = line.substringAfter("http-origin=").trim()
                    currentHeaders["Origin"] = origin
                    Timber.d("📡 Parsed origin: $origin")
                }
                
                line.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    val referrer = line.substringAfter("http-referrer=").trim()
                    currentHeaders["Referer"] = referrer
                    Timber.d("📡 Parsed referrer: $referrer")
                }
                
                line.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonStr = line.substringAfter("#EXTHTTP:").trim()
                        Timber.d("📋 Parsing #EXTHTTP: ${jsonStr.take(100)}...")
                        
                        val json = JSONObject(jsonStr)
                        
                        if (json.has("cookie")) {
                            val cookieStr = json.getString("cookie")
                            currentHeaders["Cookie"] = cookieStr
                            Timber.d("✅ Stored Cookie header: ${cookieStr.take(80)}...")
                        }
                        
                        json.keys().forEach { key ->
                            if (key != "cookie") {
                                val value = json.getString(key)
                                val headerName = key.split("-").joinToString("-") { 
                                    it.replaceFirstChar { c -> c.uppercase() } 
                                }
                                currentHeaders[headerName] = value
                                Timber.d("✅ Stored header: $headerName = ${value.take(50)}...")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error parsing #EXTHTTP line: $line")
                    }
                }
                
                // ✅ NEW: Parse #KODIPROP directives for DRM
                line.startsWith("#KODIPROP:") -> {
                    try {
                        val prop = line.substringAfter("#KODIPROP:").trim()
                        
                        when {
                            prop.startsWith("inputstream.adaptive.license_type=") -> {
                                currentDrmScheme = prop.substringAfter("inputstream.adaptive.license_type=").trim()
                                Timber.d("🔐 DRM Scheme: $currentDrmScheme")
                            }
                            prop.startsWith("inputstream.adaptive.license_key=") -> {
                                currentDrmLicenseKey = prop.substringAfter("inputstream.adaptive.license_key=").trim()
                                Timber.d("🔑 DRM License Key: ${currentDrmLicenseKey?.take(30)}...")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ Error parsing #KODIPROP: $line")
                    }
                }
                
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (currentName.isNotEmpty()) {
                        // ✅ Parse inline headers from URL (format: url|Header=value|Header=value)
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(line)
                        
                        // Merge all headers
                        val finalHeaders = currentHeaders.toMutableMap().apply {
                            putAll(inlineHeaders)
                        }
                        
                        // Use inline DRM if present, otherwise use KODIPROP DRM
                        val finalDrmScheme = inlineDrmInfo.first ?: currentDrmScheme
                        val finalDrmKey = inlineDrmInfo.second ?: currentDrmLicenseKey
                        
                        channels.add(
                            M3uChannel(
                                name = currentName,
                                logoUrl = currentLogo,
                                streamUrl = streamUrl,
                                groupTitle = currentGroup,
                                userAgent = currentUserAgent,
                                cookies = emptyMap(),
                                httpHeaders = finalHeaders,
                                drmScheme = finalDrmScheme,
                                drmLicenseKey = finalDrmKey
                            )
                        )
                        
                        Timber.d("✅ Added channel: $currentName")
                        if (finalHeaders.containsKey("Cookie")) {
                            Timber.d("   📋 Cookie: ${finalHeaders["Cookie"]?.take(80)}...")
                        }
                        if (finalDrmScheme != null) {
                            Timber.d("   🔐 DRM: $finalDrmScheme")
                        }
                    }
                    
                    // Reset for next channel
                    currentName = ""
                    currentLogo = ""
                    currentGroup = ""
                    currentUserAgent = null
                    currentHeaders = mutableMapOf()
                    currentDrmScheme = null
                    currentDrmLicenseKey = null
                }
            }
        }

        Timber.d("Parsed ${channels.size} channels from M3U")
        return channels
    }

    private fun extractChannelName(line: String): String {
        val lastCommaIndex = line.lastIndexOf(',')
        return if (lastCommaIndex != -1 && lastCommaIndex < line.length - 1) {
            line.substring(lastCommaIndex + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attributeName: String): String {
        val pattern = """$attributeName="([^"]*)"""".toRegex()
        val match = pattern.find(line)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * Parse inline headers and DRM from URL
     * Format: url|Header=value|drmScheme=clearkey|drmLicense=keyId:key
     * Returns: Triple(url, headers, Pair(drmScheme, drmLicense))
     */
    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Pair<String?, String?>> {
        val parts = urlLine.split("|")
        
        if (parts.size == 1) {
            return Triple(urlLine, emptyMap(), Pair(null, null))
        }
        
        val streamUrl = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmLicense: String? = null
        
        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            val separatorIndex = part.indexOf('=')
            
            if (separatorIndex > 0) {
                val key = part.substring(0, separatorIndex).trim()
                val value = part.substring(separatorIndex + 1).trim()
                
                when (key.lowercase()) {
                    "referer", "referrer" -> headers["Referer"] = value
                    "user-agent", "useragent" -> headers["User-Agent"] = value
                    "origin" -> headers["Origin"] = value
                    "cookie" -> headers["Cookie"] = value
                    "drmscheme" -> {
                        drmScheme = value.lowercase()
                        Timber.d("🔐 Inline DRM Scheme: $drmScheme")
                    }
                    "drmlicense" -> {
                        drmLicense = value
                        Timber.d("🔑 Inline DRM License: ${value.take(30)}...")
                    }
                    else -> headers[key] = value
                }
            }
        }
        
        return Triple(streamUrl, headers, Pair(drmScheme, drmLicense))
    }

    private fun generateChannelId(streamUrl: String, name: String): String {
        val combined = "$streamUrl|$name"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(combined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "m3u_${combined.hashCode().toString(16)}"
        }
    }

    fun convertToChannels(
        m3uChannels: List<M3uChannel>,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return m3uChannels.map { m3uChannel ->
            val streamUrlWithMetadata = buildStreamUrlWithMetadata(m3uChannel)
            
            Channel(
                id = generateChannelId(m3uChannel.streamUrl, m3uChannel.name),
                name = m3uChannel.name,
                logoUrl = m3uChannel.logoUrl.ifEmpty { 
                    "https://via.placeholder.com/150?text=${m3uChannel.name.take(2)}" 
                },
                streamUrl = streamUrlWithMetadata,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }

    /**
     * Build stream URL with all metadata (headers + DRM)
     * Format: url|Header=value|drmScheme=clearkey|drmLicense=keyId:key
     */
    private fun buildStreamUrlWithMetadata(m3uChannel: M3uChannel): String {
        val parts = mutableListOf(m3uChannel.streamUrl)
        
        // Add User-Agent
        m3uChannel.userAgent?.let {
            parts.add("User-Agent=$it")
        }
        
        // Add HTTP headers
        m3uChannel.httpHeaders.forEach { (key, value) ->
            parts.add("$key=$value")
        }
        
        // ✅ Add DRM information
        m3uChannel.drmScheme?.let { scheme ->
            parts.add("drmScheme=$scheme")
        }
        
        m3uChannel.drmLicenseKey?.let { license ->
            parts.add("drmLicense=$license")
        }
        
        val result = parts.joinToString("|")
        Timber.d("📦 Built stream URL: ${result.take(150)}...")
        return result
    }
}
