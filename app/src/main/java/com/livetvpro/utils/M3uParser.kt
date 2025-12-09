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
        val httpHeaders: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseKey: String? = null
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            Timber.d("🔥 FETCHING M3U FROM: $m3uUrl")
            
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

            Timber.d("Response Code: ${connection.responseCode}")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()
                connection.disconnect()

                Timber.d("✅ Downloaded M3U, size: ${content.length} bytes")
                Timber.d("First 200 chars: ${content.take(200)}")

                val trimmedContent = content.trim()
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    Timber.d("Response is JSON format")
                    return parseJsonPlaylist(trimmedContent)
                }

                parseM3uContent(content)
            } else {
                Timber.e("❌ Failed to fetch M3U: HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing M3U from URL: $m3uUrl")
            emptyList()
        }
    }

    fun parseJsonPlaylist(jsonContent: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        
        try {
            val jsonArray = JSONArray(jsonContent)
            Timber.d("Parsing JSON playlist with ${jsonArray.length()} items")
            
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
                            httpHeaders = headers
                        )
                    )
                    
                    Timber.d("✅ Parsed JSON channel: $name")
                }
            }
            
            Timber.d("✅ Parsed ${channels.size} channels from JSON playlist")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing JSON playlist")
        }
        
        return channels
    }

    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()

        Timber.d("🔍 Parsing M3U with ${lines.size} lines")

        if (lines.isEmpty() || !lines[0].trim().startsWith("#EXTM3U")) {
            Timber.e("❌ Invalid M3U file format - missing #EXTM3U header")
            Timber.e("First line: ${lines.firstOrNull()}")
            return emptyList()
        }

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentUserAgent: String? = null
        var currentHeaders = mutableMapOf<String, String>()
        var currentDrmScheme: String? = null
        var currentDrmLicenseKey: String? = null
        var lineNumber = 0

        for (line in lines) {
            lineNumber++
            val trimmedLine = line.trim()
            
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#EXTM3U")) {
                continue
            }

            when {
                trimmedLine.startsWith("#EXTINF:") -> {
                    currentName = extractChannelName(trimmedLine)
                    currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                    currentGroup = extractAttribute(trimmedLine, "group-title")
                    Timber.d("Line $lineNumber: Found channel name: $currentName")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("http-user-agent=").trim()
                    Timber.d("Line $lineNumber: User-Agent: ${currentUserAgent?.take(50)}")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    val origin = trimmedLine.substringAfter("http-origin=").trim()
                    currentHeaders["Origin"] = origin
                    Timber.d("Line $lineNumber: Origin: $origin")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    val referrer = trimmedLine.substringAfter("http-referrer=").trim()
                    currentHeaders["Referer"] = referrer
                    Timber.d("Line $lineNumber: Referer: $referrer")
                }
                
                trimmedLine.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonStr = trimmedLine.substringAfter("#EXTHTTP:").trim()
                        Timber.d("Line $lineNumber: Parsing #EXTHTTP JSON")
                        
                        val json = JSONObject(jsonStr)
                        
                        if (json.has("cookie")) {
                            val cookieStr = json.getString("cookie")
                            currentHeaders["Cookie"] = cookieStr
                            Timber.d("Line $lineNumber: Cookie stored (${cookieStr.length} chars)")
                        }
                        
                        json.keys().forEach { key ->
                            if (key != "cookie") {
                                val value = json.getString(key)
                                val headerName = key.split("-").joinToString("-") { 
                                    it.replaceFirstChar { c -> c.uppercase() } 
                                }
                                currentHeaders[headerName] = value
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Line $lineNumber: Error parsing #EXTHTTP")
                    }
                }
                
                trimmedLine.startsWith("#KODIPROP:") -> {
                    try {
                        val prop = trimmedLine.substringAfter("#KODIPROP:").trim()
                        
                        when {
                            prop.startsWith("inputstream.adaptive.license_type=") -> {
                                currentDrmScheme = prop.substringAfter("inputstream.adaptive.license_type=").trim()
                                Timber.d("Line $lineNumber: 🔐 DRM Scheme: $currentDrmScheme")
                            }
                            prop.startsWith("inputstream.adaptive.license_key=") -> {
                                currentDrmLicenseKey = prop.substringAfter("inputstream.adaptive.license_key=").trim()
                                Timber.d("Line $lineNumber: 🔑 DRM Key: ${currentDrmLicenseKey?.take(40)}...")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Line $lineNumber: Error parsing #KODIPROP")
                    }
                }
                
                !trimmedLine.startsWith("#") && trimmedLine.isNotEmpty() -> {
                    if (currentName.isNotEmpty()) {
                        Timber.d("Line $lineNumber: Stream URL found: ${trimmedLine.take(80)}...")
                        
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(trimmedLine)
                        
                        val finalHeaders = currentHeaders.toMutableMap().apply {
                            putAll(inlineHeaders)
                        }
                        
                        val finalDrmScheme = inlineDrmInfo.first ?: currentDrmScheme
                        val finalDrmKey = inlineDrmInfo.second ?: currentDrmLicenseKey
                        
                        val channel = M3uChannel(
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = streamUrl,
                            groupTitle = currentGroup,
                            userAgent = currentUserAgent,
                            httpHeaders = finalHeaders,
                            drmScheme = finalDrmScheme,
                            drmLicenseKey = finalDrmKey
                        )
                        
                        channels.add(channel)
                        
                        Timber.d("✅ CHANNEL ADDED:")
                        Timber.d("   Name: $currentName")
                        Timber.d("   Group: $currentGroup")
                        Timber.d("   URL: ${streamUrl.take(80)}")
                        Timber.d("   User-Agent: ${currentUserAgent ?: "none"}")
                        Timber.d("   Headers: ${finalHeaders.keys.joinToString()}")
                        Timber.d("   DRM: ${finalDrmScheme ?: "none"}")
                    } else {
                        Timber.w("Line $lineNumber: Stream URL without channel name: ${trimmedLine.take(50)}")
                    }
                    
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

        Timber.d("🎯 TOTAL PARSED: ${channels.size} channels")
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

    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Pair<String?, String?>> {
        val parts = urlLine.split("|")
        
        if (parts.size == 1) {
            return Triple(urlLine, emptyMap(), Pair(null, null))
        }
        
        val streamUrl = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmLicense: String? = null
        
        Timber.d("   Parsing inline metadata (${parts.size - 1} parts)")
        
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
                    "drmscheme" -> drmScheme = value.lowercase()
                    "drmlicense" -> drmLicense = value
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
        Timber.d("🔄 Converting ${m3uChannels.size} M3U channels to Channel objects")
        
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

    private fun buildStreamUrlWithMetadata(m3uChannel: M3uChannel): String {
        val parts = mutableListOf(m3uChannel.streamUrl)
        
        m3uChannel.userAgent?.let {
            parts.add("User-Agent=$it")
        }
        
        m3uChannel.httpHeaders.forEach { (key, value) ->
            parts.add("$key=$value")
        }
        
        m3uChannel.drmScheme?.let { scheme ->
            parts.add("drmScheme=$scheme")
        }
        
        m3uChannel.drmLicenseKey?.let { license ->
            parts.add("drmLicense=$license")
        }
        
        return parts.joinToString("|")
    }
}
