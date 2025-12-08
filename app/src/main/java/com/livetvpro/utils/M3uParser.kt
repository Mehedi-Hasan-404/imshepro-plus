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
        val httpHeaders: Map<String, String> = emptyMap()
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            val trimmedUrl = m3uUrl.trim()
            // Check if it's a JSON array first (raw text check)
            if (trimmedUrl.startsWith("[") || trimmedUrl.startsWith("{")) {
                Timber.d("Detected JSON format playlist from string content")
                return parseJsonPlaylist(trimmedUrl)
            }
            
            val url = URL(trimmedUrl)
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
                // Check if response content is JSON
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

    /**
     * Parse standard M3U content, including #EXTHTTP and #EXTVLCOPT tags
     */
    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.isEmpty()) {
            Timber.e("Empty M3U file")
            return emptyList()
        }

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentUserAgent: String? = null
        var currentCookies = mutableMapOf<String, String>()
        var currentHeaders = mutableMapOf<String, String>()

        // Helper function to save the current channel and reset state
        fun saveChannel(urlLine: String) {
            if (currentName.isNotEmpty()) {
                // Parse any inline headers in the URL itself (e.g. url|User-Agent=...)
                val (cleanUrl, inlineHeaders) = parseInlineHeaders(urlLine)
                
                // Combine headers
                val finalHeaders = currentHeaders.toMutableMap()
                finalHeaders.putAll(inlineHeaders)
                
                // Build the channel object
                channels.add(
                    M3uChannel(
                        name = currentName,
                        logoUrl = currentLogo,
                        streamUrl = cleanUrl,
                        groupTitle = currentGroup,
                        userAgent = currentUserAgent,
                        cookies = currentCookies.toMap(),
                        httpHeaders = finalHeaders
                    )
                )
            }
            // Reset state
            currentName = ""
            currentLogo = ""
            currentGroup = ""
            currentUserAgent = null
            currentCookies = mutableMapOf()
            currentHeaders = mutableMapOf()
        }

        for (line in lines) {
            when {
                // 1. Channel Info
                line.startsWith("#EXTINF:") -> {
                    // If we hit a new EXTINF but haven't saved previous (rare case of missing URL), reset
                    if (currentName.isNotEmpty()) {
                        // Optionally log warning or reset
                    }
                    currentName = extractChannelName(line)
                    currentLogo = extractAttribute(line, "tvg-logo")
                    currentGroup = extractAttribute(line, "group-title")
                }
                
                // 2. User Agent (VLC Option)
                line.startsWith("#EXTVLCOPT", ignoreCase = true) && line.contains("http-user-agent", ignoreCase = true) -> {
                    val ua = line.substringAfter("http-user-agent=", "").trim()
                    if (ua.isNotEmpty()) {
                        currentUserAgent = ua
                        Timber.d("Found User-Agent: $ua")
                    }
                }
                
                // 3. HTTP Headers/Cookies (JSON format)
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    try {
                        val jsonStr = line.substringAfter("#EXTHTTP:").trim()
                        val json = JSONObject(jsonStr)
                        
                        // Handle Cookie
                        if (json.has("cookie")) {
                            val rawCookie = json.getString("cookie")
                            // Store the raw cookie string directly under "Cookie"
                            // Note: If you have multiple cookies separated by semicolon, 
                            // you might want to parse them, but for headers, sending the full string usually works.
                            currentCookies["Cookie"] = rawCookie
                        }
                        
                        // Handle other headers in the JSON object
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (!key.equals("cookie", ignoreCase = true)) {
                                currentHeaders[key] = json.getString(key)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e("Error parsing #EXTHTTP JSON: ${e.message}")
                    }
                }
                
                // 4. Stream URL (Lines not starting with #)
                !line.startsWith("#") -> {
                    saveChannel(line)
                }
            }
        }

        Timber.d("Parsed ${channels.size} channels from M3U")
        return channels
    }

    /**
     * Parse JSON playlist format (Legacy support)
     */
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
                    val cookies = mutableMapOf<String, String>()
                    
                    if (cookie.isNotEmpty()) {
                        cookies["Cookie"] = cookie
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
                            cookies = cookies,
                            httpHeaders = headers
                        )
                    )
                }
            }
            Timber.d("Parsed ${channels.size} channels from JSON playlist")
        } catch (e: Exception) {
            Timber.e(e, "Error parsing JSON playlist")
        }
        
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

    private fun parseInlineHeaders(urlLine: String): Pair<String, Map<String, String>> {
        val parts = urlLine.split("|")
        if (parts.size == 1) {
            return Pair(urlLine.trim(), emptyMap())
        }
        
        val streamUrl = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        
        for (i in 1 until parts.size) {
            val headerPart = parts[i].trim()
            val separatorIndex = headerPart.indexOf('=')
            
            if (separatorIndex > 0) {
                val headerName = headerPart.substring(0, separatorIndex).trim()
                val headerValue = headerPart.substring(separatorIndex + 1).trim()
                
                // Normalize common headers
                when (headerName.lowercase()) {
                    "user-agent", "useragent" -> headers["User-Agent"] = headerValue
                    "referer", "referrer" -> headers["Referer"] = headerValue
                    "cookie" -> headers["Cookie"] = headerValue
                    "origin" -> headers["Origin"] = headerValue
                    else -> headers[headerName] = headerValue
                }
            }
        }
        
        return Pair(streamUrl, headers)
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
            // Build stream URL with inline headers for compatibility with Player logic
            val streamUrlWithHeaders = buildStreamUrlWithHeaders(m3uChannel)
            
            Channel(
                id = generateChannelId(m3uChannel.streamUrl, m3uChannel.name),
                name = m3uChannel.name,
                logoUrl = m3uChannel.logoUrl.ifEmpty { 
                    "https://via.placeholder.com/150?text=${m3uChannel.name.take(2)}" 
                },
                streamUrl = streamUrlWithHeaders,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }

    /**
     * Re-packs headers into the URL string using the pipe delimiter |
     * This allows us to store everything in the single 'streamUrl' string field
     * of the Channel data model, which the PlayerActivity then unpacks.
     */
    private fun buildStreamUrlWithHeaders(m3uChannel: M3uChannel): String {
        val parts = mutableListOf(m3uChannel.streamUrl)
        
        // 1. Add User Agent
        m3uChannel.userAgent?.let {
            parts.add("User-Agent=$it")
        }
        
        // 2. Add Cookies
        if (m3uChannel.cookies.isNotEmpty()) {
            // Join all cookies into one "Cookie" header value if multiple exist
            val cookieValue = m3uChannel.cookies.entries.joinToString(";") { 
                if (it.key == "Cookie") it.value else "${it.key}=${it.value}" 
            }
            parts.add("Cookie=$cookieValue")
        }
        
        // 3. Add other headers
        m3uChannel.httpHeaders.forEach { (key, value) ->
            // Avoid duplicating User-Agent or Cookie if already added
            if (!key.equals("User-Agent", ignoreCase = true) && !key.equals("Cookie", ignoreCase = true)) {
                parts.add("$key=$value")
            }
        }
        
        return parts.joinToString("|")
    }
}
