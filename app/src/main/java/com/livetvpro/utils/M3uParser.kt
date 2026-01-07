package com.livetvpro.utils

import android.util.Base64
import com.livetvpro.data.models.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val drmKeyId: String? = null,
        val drmKey: String? = null
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            Timber.d("📥 FETCHING M3U FROM: $m3uUrl")
            
            val trimmedUrl = m3uUrl.trim()
            
            // Check if it's JSON format (starts with [ or {)
            if (trimmedUrl.startsWith("[") || trimmedUrl.startsWith("{")) {
                Timber.d("✅ Direct JSON content detected")
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
                
                // Check if fetched content is JSON
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    Timber.d("✅ JSON content detected from URL")
                    return parseJsonPlaylist(trimmedContent)
                }

                // Otherwise parse as M3U
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

    /**
     * Parse JSON playlist format
     * Supports formats:
     * 1. Array of objects with: name, link, logo, cookie, user-agent, referer, origin, category
     * 2. Object with channels array
     */
    fun parseJsonPlaylist(jsonContent: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        try {
            Timber.d("╔═══════════════════════════════════════════════════════╗")
            Timber.d("              🔍 PARSING JSON PLAYLIST")
            Timber.d("╚═══════════════════════════════════════════════════════╝")
            
            val trimmed = jsonContent.trim()
            
            // Determine if it's an array or object
            val jsonArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                val jsonObject = JSONObject(trimmed)
                // Try to find an array within the object
                when {
                    jsonObject.has("channels") -> jsonObject.getJSONArray("channels")
                    jsonObject.has("items") -> jsonObject.getJSONArray("items")
                    jsonObject.has("data") -> jsonObject.getJSONArray("data")
                    else -> {
                        Timber.e("❌ JSON object doesn't contain channels array")
                        return emptyList()
                    }
                }
            } else {
                Timber.e("❌ Invalid JSON format")
                return emptyList()
            }
            
            Timber.d("📊 Found ${jsonArray.length()} channels in JSON")
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                
                // Extract basic info
                val name = item.optString("name", "Unknown Channel")
                val link = item.optString("link", "")
                val logo = item.optString("logo", "")
                val category = item.optString("category", "")
                
                // Extract headers
                val cookie = item.optString("cookie", "")
                val userAgent = item.optString("user-agent", null) 
                    ?: item.optString("user_agent", null)
                    ?: item.optString("userAgent", null)
                val referer = item.optString("referer", null) 
                    ?: item.optString("referrer", null)
                val origin = item.optString("origin", null)
                
                // Build headers map
                val headers = mutableMapOf<String, String>()
                if (cookie.isNotEmpty()) {
                    headers["Cookie"] = cookie
                    Timber.d("   🍪 Cookie: ${cookie.take(40)}...")
                }
                referer?.let { 
                    headers["Referer"] = it
                    Timber.d("   🌐 Referer: ${it.take(40)}...")
                }
                origin?.let { 
                    headers["Origin"] = it
                    Timber.d("   🌐 Origin: $it")
                }
                
                if (link.isNotEmpty()) {
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("${i + 1}. $name")
                    if (category.isNotEmpty()) Timber.d("   📁 Category: $category")
                    if (userAgent != null) Timber.d("   🖥️ User-Agent: ${userAgent.take(30)}...")
                    if (headers.isNotEmpty()) Timber.d("   📦 Headers: ${headers.keys.joinToString(", ")}")
                    Timber.d("   📍 URL: ${link.take(60)}...")
                    
                    channels.add(M3uChannel(
                        name = name,
                        logoUrl = logo,
                        streamUrl = link,
                        groupTitle = category,
                        userAgent = userAgent,
                        httpHeaders = headers
                    ))
                } else {
                    Timber.w("⚠️ Skipping channel '$name' - no link provided")
                }
            }
            
            Timber.d("╔═══════════════════════════════════════════════════════╗")
            Timber.d("📊 JSON PARSING SUMMARY")
            Timber.d("   Total channels: ${channels.size}")
            Timber.d("   With cookies: ${channels.count { it.httpHeaders.containsKey("Cookie") }}")
            Timber.d("   With User-Agent: ${channels.count { it.userAgent != null }}")
            Timber.d("   With headers: ${channels.count { it.httpHeaders.isNotEmpty() }}")
            Timber.d("╚═══════════════════════════════════════════════════════╝")
            
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing JSON playlist")
            Timber.e("   Content preview: ${jsonContent.take(200)}")
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

        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("              📡 PARSING M3U CONTENT")
        Timber.d("╚═══════════════════════════════════════════════════════╝")

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            if (trimmedLine.startsWith("#EXTM3U")) continue

            when {
                // DRM Parsing (KODIPROP)
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val rawScheme = trimmedLine.substringAfter("=").trim().lowercase()
                    currentDrmScheme = normalizeDrmScheme(rawScheme)
                    Timber.d("🔐 DRM Scheme: $currentDrmScheme")
                }
                
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmedLine.substringAfter("=").trim()
                    
                    when {
                        keyValue.startsWith("http://", ignoreCase = true) || 
                        keyValue.startsWith("https://", ignoreCase = true) -> {
                            currentDrmKeyId = keyValue
                            currentDrmKey = "LICENSE_URL"
                            Timber.d("🌐 DRM License URL: ${keyValue.take(60)}...")
                        }
                        keyValue.contains(":") && !keyValue.startsWith("{") -> {
                            val parts = keyValue.split(":", limit = 2)
                            if (parts.size == 2) {
                                currentDrmKeyId = parts[0].trim()
                                currentDrmKey = parts[1].trim()
                                Timber.d("🔑 DRM Keys: KeyID=${currentDrmKeyId?.take(16)}...")
                            }
                        }
                        keyValue.startsWith("{") -> {
                            val (keyId, key) = parseJWKToKeyIdPair(keyValue)
                            if (keyId != null && key != null) {
                                currentDrmKeyId = keyId
                                currentDrmKey = key
                                Timber.d("🔑 DRM Keys (JWK): KeyID=${keyId.take(16)}...")
                            }
                        }
                    }
                }
                
                trimmedLine.startsWith("#EXTINF:") -> {
                    currentUserAgent = null
                    currentHeaders = mutableMapOf()
                    
                    currentName = extractChannelName(trimmedLine)
                    currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                    currentGroup = extractAttribute(trimmedLine, "group-title")
                    
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("📺 Channel: $currentName")
                }
                
                // Custom Header Parsing (VLC options)
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("=").trim()
                    Timber.d("   🖥️ User-Agent: ${currentUserAgent?.take(40)}...")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    currentHeaders["Origin"] = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    currentHeaders["Referer"] = trimmedLine.substringAfter("=").trim()
                }

                // Parse #EXTHTTP JSON format
                trimmedLine.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonPart = trimmedLine.substringAfter("#EXTHTTP:").trim()
                        
                        if (jsonPart.startsWith("{")) {
                            val json = JSONObject(jsonPart)
                            json.keys().forEach { key ->
                                val value = json.optString(key, "")
                                when (key.lowercase()) {
                                    "cookie" -> currentHeaders["Cookie"] = value
                                    "user-agent" -> currentUserAgent = value
                                    "referer", "referrer" -> currentHeaders["Referer"] = value
                                    "origin" -> currentHeaders["Origin"] = value
                                    else -> currentHeaders[key] = value
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error parsing #EXTHTTP")
                    }
                }
                
                !trimmedLine.startsWith("#") -> {
                    if (currentName.isNotEmpty()) {
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(trimmedLine)
                        
                        val finalHeaders = currentHeaders.toMutableMap()
                        finalHeaders.putAll(inlineHeaders)
                        
                        val finalDrmScheme = inlineDrmInfo.first ?: currentDrmScheme
                        val finalDrmKeyId = inlineDrmInfo.second ?: currentDrmKeyId
                        val finalDrmKey = inlineDrmInfo.third ?: currentDrmKey
                        
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
                        
                        if (finalHeaders.isNotEmpty()) {
                            Timber.d("   📦 Final Headers: ${finalHeaders.keys.joinToString(", ")}")
                        }
                        
                        currentDrmScheme = null
                        currentDrmKeyId = null
                        currentDrmKey = null
                    }
                }
            }
        }
        
        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("📊 M3U PARSING SUMMARY")
        Timber.d("   Total channels: ${channels.size}")
        Timber.d("   With DRM: ${channels.count { it.drmScheme != null }}")
        Timber.d("   With headers: ${channels.count { it.httpHeaders.isNotEmpty() }}")
        Timber.d("╚═══════════════════════════════════════════════════════╝")
        
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

    private fun normalizeDrmScheme(scheme: String): String {
        val lower = scheme.lowercase()
        return when {
            lower.contains("clearkey") || lower == "org.w3.clearkey" -> "clearkey"
            lower.contains("widevine") -> "widevine"
            lower.contains("playready") -> "playready"
            lower.contains("fairplay") -> "fairplay"
            else -> lower
        }
    }

    private fun parseJWKToKeyIdPair(jwk: String): Pair<String?, String?> {
        return try {
            val kidMatch = Regex(""""kid"\s*:\s*"([^"]+)"""").find(jwk)
            val kMatch = Regex(""""k"\s*:\s*"([^"]+)"""").find(jwk)
            
            if (kidMatch != null && kMatch != null) {
                val kidBase64 = kidMatch.groupValues[1]
                val kBase64 = kMatch.groupValues[1]
                
                val kidHex = base64UrlToHex(kidBase64)
                val kHex = base64UrlToHex(kBase64)
                
                if (kidHex.isNotEmpty() && kHex.isNotEmpty()) {
                    kidHex to kHex
                } else {
                    null to null
                }
            } else {
                null to null
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to parse JWK")
            null to null
        }
    }

    private fun base64UrlToHex(base64Url: String): String {
        return try {
            var base64 = base64Url.replace('-', '+').replace('_', '/')
            val paddingNeeded = (4 - (base64.length % 4)) % 4
            base64 += "=".repeat(paddingNeeded)
            
            val bytes = try {
                Base64.decode(base64, Base64.NO_WRAP)
            } catch (e: Exception) {
                Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP)
            }
            
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to convert base64url to hex")
            ""
        }
    }

    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Triple<String?, String?, String?>> {
        val pipeIndex = urlLine.indexOf('|')
        if (pipeIndex == -1) {
            return Triple(urlLine.trim(), emptyMap(), Triple(null, null, null))
        }
        
        val url = urlLine.substring(0, pipeIndex).trim()
        val paramsString = urlLine.substring(pipeIndex + 1).trim()
        
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null

        if (paramsString.isNotEmpty()) {
            val parts = paramsString.split(Regex("[&|]"))
            
            for (part in parts) {
                val trimmedPart = part.trim()
                if (trimmedPart.isEmpty()) continue
                
                val eqIndex = trimmedPart.indexOf('=')
                if (eqIndex == -1) continue
                
                val key = trimmedPart.substring(0, eqIndex).trim()
                val value = trimmedPart.substring(eqIndex + 1).trim()
                
                when (key.lowercase()) {
                    "drmscheme" -> drmScheme = normalizeDrmScheme(value)
                    "drmlicense" -> {
                        if (value.startsWith("http://", ignoreCase = true) || 
                            value.startsWith("https://", ignoreCase = true)) {
                            drmKeyId = value
                            drmKey = "LICENSE_URL"
                        } else {
                            val colonIndex = value.indexOf(':')
                            if (colonIndex != -1) {
                                drmKeyId = value.substring(0, colonIndex).trim()
                                drmKey = value.substring(colonIndex + 1).trim()
                            }
                        }
                    }
                    "user-agent", "useragent" -> headers["User-Agent"] = value
                    "referer", "referrer" -> headers["Referer"] = value
                    "origin" -> headers["Origin"] = value
                    "cookie" -> headers["Cookie"] = value
                    "x-forwarded-for" -> headers["X-Forwarded-For"] = value
                    else -> headers[key] = value
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
        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("   🔄 CONVERTING TO CHANNELS")
        Timber.d("╚═══════════════════════════════════════════════════════╝")
        
        return m3uChannels.mapIndexed { index, m3u ->
            val metaUrl = buildStreamUrlWithMetadata(m3u)
            
            Timber.d("${index + 1}. ${m3u.name}")
            if (m3u.drmScheme != null) {
                Timber.d("   🔐 DRM: ${m3u.drmScheme}")
            }
            if (m3u.httpHeaders.isNotEmpty()) {
                Timber.d("   📦 Headers: ${m3u.httpHeaders.keys.joinToString(", ")}")
            }
            if (m3u.userAgent != null) {
                Timber.d("   🖥️ User-Agent: ${m3u.userAgent.take(30)}...")
            }
            
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
        val parts = mutableListOf<String>()
        
        // Base URL
        parts.add(m3u.streamUrl)
        
        // User agent
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        // HTTP headers
        m3u.httpHeaders.forEach { (key, value) -> 
            parts.add("$key=$value")
        }
        
        // DRM information
        if (m3u.drmScheme != null) {
            parts.add("drmScheme=${m3u.drmScheme}")
            
            if (m3u.drmKeyId != null && m3u.drmKey != null) {
                if (m3u.drmKeyId.startsWith("http://", ignoreCase = true) || 
                    m3u.drmKeyId.startsWith("https://", ignoreCase = true)) {
                    parts.add("drmLicense=${m3u.drmKeyId}")
                } else {
                    parts.add("drmLicense=${m3u.drmKeyId}:${m3u.drmKey}")
                }
            }
        }
        
        return parts.joinToString("|")
    }
}
