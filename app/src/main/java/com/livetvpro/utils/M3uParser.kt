package com.livetvpro.utils

import com.livetvpro.data.models.Channel
import org.json.JSONArray
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
        val drmKeyId: String? = null,
        val drmKey: String? = null
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            Timber.d("📥 FETCHING M3U FROM: $m3uUrl")
            
            val trimmedUrl = m3uUrl.trim()
            if (trimmedUrl.startsWith("[") || trimmedUrl.startsWith("{")) {
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
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    return parseJsonPlaylist(trimmedContent)
                }

                parseM3uContent(content)
            } else {
                Timber.e("❌ Failed to fetch M3U: HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing M3U from URL")
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
                    if (cookie.isNotEmpty()) headers["Cookie"] = cookie
                    referer?.let { headers["Referer"] = it }
                    origin?.let { headers["Origin"] = it }
                    
                    channels.add(M3uChannel(
                        name = name,
                        logoUrl = logo,
                        streamUrl = link,
                        userAgent = userAgent,
                        httpHeaders = headers
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing JSON playlist")
        }
        return channels
    }

    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()

        if (lines.isEmpty()) return emptyList()

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentUserAgent: String? = null
        var currentHeaders = mutableMapOf<String, String>()
        var currentDrmScheme: String? = null
        var currentDrmKeyId: String? = null
        var currentDrmKey: String? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            if (trimmedLine.startsWith("#EXTM3U")) continue

            when {
                trimmedLine.startsWith("#EXTINF:") -> {
                    // Reset all data when a new channel starts
                    currentUserAgent = null
                    currentHeaders = mutableMapOf()
                    currentDrmScheme = null
                    currentDrmKeyId = null
                    currentDrmKey = null
                    
                    currentName = extractChannelName(trimmedLine)
                    currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                    currentGroup = extractAttribute(trimmedLine, "group-title")
                }
                
                // --- Custom Header Parsing (VLC options) ---
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    currentHeaders["Origin"] = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    currentHeaders["Referer"] = trimmedLine.substringAfter("=").trim()
                }

                // ✅ FIX: Handle the #EXTHTTP:{"cookie":"VALUE"} format
                trimmedLine.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonPart = trimmedLine.substringAfter("#EXTHTTP:").trim()
                        // Use a regex to safely extract the cookie value
                        val cookieMatch = Regex(""""cookie"\s*:\s*"([^"]+)"""").find(jsonPart) 
                        val cookie = cookieMatch?.groups?.get(1)?.value
                        
                        if (cookie != null) {
                            currentHeaders["Cookie"] = cookie
                            Timber.d("🍪 Found M3U Cookie Header")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error parsing #EXTHTTP: cookie")
                    }
                }
                
                // --- DRM Parsing (KODIPROP) ---
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    // Capture license_type, usually 'clearkey'
                    currentDrmScheme = trimmedLine.substringAfter("=").trim().lowercase()
                    Timber.d("🔐 Found DRM Scheme: $currentDrmScheme")
                }
                
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    // Capture license key in "KeyID:Key" format
                    val keyPair = trimmedLine.substringAfter("=").trim()
                    val parts = keyPair.split(":")
                    if (parts.size == 2) {
                        currentDrmKeyId = parts[0].trim()
                        currentDrmKey = parts[1].trim()
                        Timber.d("🔑 Found DRM Keys")
                    }
                }
                
                !trimmedLine.startsWith("#") -> {
                    // This is the URL line - Process and commit channel
                    if (currentName.isNotEmpty()) {
                        // Check if the URL line itself contains inline pipe-separated parameters
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(trimmedLine)
                        
                        // Merge all headers (M3U tags + inline)
                        val finalHeaders = currentHeaders.toMutableMap()
                        finalHeaders.putAll(inlineHeaders)
                        
                        // Use M3U tag DRM info if present, otherwise use inline info
                        val finalDrmScheme = currentDrmScheme ?: inlineDrmInfo.first
                        val finalDrmKeyId = currentDrmKeyId ?: inlineDrmInfo.second
                        val finalDrmKey = currentDrmKey ?: inlineDrmInfo.third
                        
                        channels.add(M3uChannel(
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = streamUrl,
                            groupTitle = currentGroup,
                            userAgent = currentUserAgent,
                            httpHeaders = finalHeaders,
                            drmScheme = finalDrmScheme,
                            drmKeyId = finalDrmKeyId,
                            drmKey = finalDrmKey
                        ))
                        
                        Timber.d("✅ Parsed channel: $currentName | DRM: $finalDrmScheme | URL: ${streamUrl.take(50)}...")
                    }
                    
                    // Note: Since we reset at #EXTINF, no need to reset here.
                }
            }
        }
        return channels
    }

    private fun extractChannelName(line: String): String {
        val lastComma = line.lastIndexOf(',')
        return if (lastComma != -1 && lastComma < line.length - 1) {
            line.substring(lastComma + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attribute: String): String {
        val pattern = """$attribute="([^"]*)"""".toRegex()
        val match = pattern.find(line)
        if (match != null) return match.groupValues[1]
        
        val unquotedPattern = """$attribute=([^ ]*)""".toRegex()
        val unquotedMatch = unquotedPattern.find(line)
        return unquotedMatch?.groupValues?.get(1) ?: ""
    }

    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Triple<String?, String?, String?>> {
        val parts = urlLine.split("|")
        val url = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null

        if (parts.size > 1) {
            for (i in 1 until parts.size) {
                // Normalize the delimiter in the parameters string only
                val part = parts[i].replace("&", "|")
                val eqIndex = part.indexOf('=')
                if (eqIndex != -1) {
                    val key = part.substring(0, eqIndex).trim()
                    val value = part.substring(eqIndex + 1).trim()
                    
                    when (key.lowercase()) {
                        "drmscheme" -> drmScheme = value
                        "drmlicense" -> {
                            // Handle "keyId:key" format
                            val keyParts = value.split(":")
                            if (keyParts.size == 2) {
                                drmKeyId = keyParts[0].trim()
                                drmKey = keyParts[1].trim()
                            }
                        }
                        "user-agent", "useragent" -> headers["User-Agent"] = value
                        "referer", "referrer" -> headers["Referer"] = value
                        "cookie" -> headers["Cookie"] = value
                        else -> headers[key] = value
                    }
                }
            }
        }
        return Triple(url, headers, Triple(drmScheme, drmKeyId, drmKey))
    }

    private fun generateChannelId(streamUrl: String, name: String): String {
        val combined = "$streamUrl|$name"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(combined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "m3u_${combined.hashCode()}"
        }
    }

    fun convertToChannels(
        m3uChannels: List<M3uChannel>,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return m3uChannels.map { m3u ->
            val metaUrl = buildStreamUrlWithMetadata(m3u)
            Channel(
                id = generateChannelId(m3u.streamUrl, m3u.name),
                name = m3u.name,
                logoUrl = m3u.logoUrl,
                streamUrl = metaUrl,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }

    private fun buildStreamUrlWithMetadata(m3u: M3uChannel): String {
        val parts = mutableListOf(m3u.streamUrl)
        
        // Add User-Agent if set
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        // Add all other HTTP headers
        m3u.httpHeaders.forEach { (k, v) -> parts.add("$k=$v") }
        
        // Add DRM Scheme
        m3u.drmScheme?.let { parts.add("drmScheme=$it") }
        
        // Add DRM License (KeyID:Key)
        if (m3u.drmKeyId != null && m3u.drmKey != null) {
            parts.add("drmLicense=${m3u.drmKeyId}:${m3u.drmKey}")
        }
        
        return parts.joinToString("|")
    }
}
