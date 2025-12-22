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
        var currentLicenseUrl: String? = null

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
                    currentLicenseUrl = null
                    
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

                // Parse #EXTHTTP JSON format
                trimmedLine.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonPart = trimmedLine.substringAfter("#EXTHTTP:").trim()
                        
                        // Try to parse as JSON
                        if (jsonPart.startsWith("{")) {
                            val json = JSONObject(jsonPart)
                            
                            // Extract all headers from JSON
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
                            Timber.d("🌐 Parsed EXTHTTP headers: ${currentHeaders.keys}")
                        } else {
                            // Fallback to regex extraction
                            val cookieMatch = Regex(""""cookie"\s*:\s*"([^"]+)"""").find(jsonPart)
                            cookieMatch?.groups?.get(1)?.value?.let { currentHeaders["Cookie"] = it }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error parsing #EXTHTTP")
                    }
                }
                
                // --- DRM Parsing (KODIPROP) ---
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    currentDrmScheme = trimmedLine.substringAfter("=").trim().lowercase()
                    Timber.d("🔐 Found DRM Scheme: $currentDrmScheme")
                }
                
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmedLine.substringAfter("=").trim()
                    
                    when {
                        // Format 1: Simple KeyID:Key (hex)
                        keyValue.contains(":") && !keyValue.startsWith("http") && !keyValue.startsWith("{") -> {
                            val parts = keyValue.split(":", limit = 2)
                            if (parts.size == 2) {
                                currentDrmKeyId = parts[0].trim()
                                currentDrmKey = parts[1].trim()
                                Timber.d("🔑 Found DRM Keys (hex): ${currentDrmKeyId?.take(8)}...${currentDrmKey?.take(8)}...")
                            }
                        }
                        
                        // Format 2: URL-based license key
                        keyValue.startsWith("http") -> {
                            currentLicenseUrl = keyValue
                            Timber.d("🌐 Found DRM License URL: $keyValue")
                        }
                        
                        // Format 3: JWK JSON format
                        keyValue.startsWith("{") -> {
                            val (keyId, key) = parseJWKToKeyIdPair(keyValue)
                            if (keyId != null && key != null) {
                                currentDrmKeyId = keyId
                                currentDrmKey = key
                                Timber.d("🔑 Found DRM Keys (JWK): ${currentDrmKeyId?.take(8)}...${currentDrmKey?.take(8)}...")
                            }
                        }
                    }
                }
                
                // Parse other KODIPROP directives
                trimmedLine.startsWith("#KODIPROP:") -> {
                    // Handle other KODIPROP properties if needed
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
                        var finalDrmKeyId = currentDrmKeyId ?: inlineDrmInfo.second
                        var finalDrmKey = currentDrmKey ?: inlineDrmInfo.third
                        
                        // If we have a license URL, try to fetch keys
                        if (currentLicenseUrl != null && finalDrmKeyId == null) {
                            Timber.d("🔄 License URL detected - will need fetching: $currentLicenseUrl")
                            // Store URL for later fetching
                            finalDrmKeyId = "URL:$currentLicenseUrl"
                            finalDrmKey = "FETCH_REQUIRED"
                        }
                        
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
                        
                        val drmStatus = if (finalDrmScheme != null) {
                            "✅ DRM: $finalDrmScheme [${finalDrmKeyId?.take(8)}...${finalDrmKey?.take(8)}...]"
                        } else {
                            "🔓 No DRM"
                        }
                        Timber.d("✅ Parsed: $currentName | $drmStatus")
                    }
                }
            }
        }
        
        Timber.d("📊 Total channels parsed: ${channels.size}")
        Timber.d("📊 Channels with DRM: ${channels.count { it.drmScheme != null }}")
        
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

    /**
     * Parse JWK (JSON Web Key) format to KeyID:Key pair
     * Example input: { "keys":[ { "kty":"oct", "k":"base64key", "kid":"base64keyid" } ], "type":"temporary" }
     */
    private fun parseJWKToKeyIdPair(jwk: String): Pair<String?, String?> {
        return try {
            // Extract kid (Key ID) and k (Key) from JWK
            val kidMatch = Regex(""""kid"\s*:\s*"([^"]+)"""").find(jwk)
            val kMatch = Regex(""""k"\s*:\s*"([^"]+)"""").find(jwk)
            
            if (kidMatch != null && kMatch != null) {
                val kidBase64 = kidMatch.groupValues[1]
                val kBase64 = kMatch.groupValues[1]
                
                // Convert base64url to hex
                val kidHex = base64UrlToHex(kidBase64)
                val kHex = base64UrlToHex(kBase64)
                
                if (kidHex.isNotEmpty() && kHex.isNotEmpty()) {
                    Timber.d("🔐 JWK Converted - KID: ${kidHex.take(16)}..., Key: ${kHex.take(16)}...")
                    kidHex to kHex
                } else {
                    Timber.w("⚠️ JWK conversion resulted in empty strings")
                    null to null
                }
            } else {
                Timber.w("⚠️ JWK parsing failed: missing kid or k")
                null to null
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to parse JWK")
            null to null
        }
    }

    /**
     * Convert base64url encoded string to hexadecimal
     */
    private fun base64UrlToHex(base64Url: String): String {
        return try {
            // Convert base64url to standard base64
            var base64 = base64Url.replace('-', '+').replace('_', '/')
            
            // Add padding if needed
            while (base64.length % 4 != 0) {
                base64 += "="
            }
            
            // Decode base64 to bytes
            val bytes = Base64.decode(base64, Base64.NO_WRAP or Base64.URL_SAFE)
            
            // Convert bytes to hex
            val hex = bytes.joinToString("") { "%02x".format(it) }
            Timber.d("🔄 Base64→Hex: $base64Url → ${hex.take(16)}...")
            hex
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to convert base64url to hex: $base64Url")
            ""
        }
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
                // Normalize the delimiter in the parameters string
                val part = parts[i].replace("&", "|")
                val eqIndex = part.indexOf('=')
                if (eqIndex != -1) {
                    val key = part.substring(0, eqIndex).trim()
                    val value = part.substring(eqIndex + 1).trim()
                    
                    when (key.lowercase()) {
                        "drmscheme" -> {
                            drmScheme = value.lowercase()
                            Timber.d("🔐 Inline DRM Scheme: $drmScheme")
                        }
                        "drmlicense" -> {
                            // Handle "keyId:key" format
                            val keyParts = value.split(":", limit = 2)
                            if (keyParts.size == 2) {
                                drmKeyId = keyParts[0].trim()
                                drmKey = keyParts[1].trim()
                                Timber.d("🔑 Inline DRM Keys: ${drmKeyId?.take(8)}...${drmKey?.take(8)}...")
                            }
                        }
                        "user-agent", "useragent" -> headers["User-Agent"] = value
                        "referer", "referrer" -> headers["Referer"] = value
                        "origin" -> headers["Origin"] = value
                        "cookie" -> headers["Cookie"] = value
                        "x-forwarded-for" -> headers["X-Forwarded-For"] = value
                        else -> {
                            // Store other headers with proper casing
                            headers[key] = value
                        }
                    }
                }
            }
        }
        return Triple(url, headers, Triple(drmScheme, drmKeyId, drmKey))
    }

    /**
     * Fetch DRM license keys from a URL (for URL-based license keys)
     */
    suspend fun fetchLicenseKeysFromUrl(licenseUrl: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🌐 Fetching license from: $licenseUrl")
            val url = URL(licenseUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()
                
                // Try to parse response as JSON or simple key:value format
                if (response.trim().startsWith("{")) {
                    // JSON response
                    val json = JSONObject(response)
                    val keyId = json.optString("keyId", null) ?: json.optString("kid", null)
                    val key = json.optString("key", null) ?: json.optString("k", null)
                    Timber.d("✅ Fetched license keys successfully")
                    return@withContext keyId to key
                } else if (response.contains(":")) {
                    // Simple key:value format
                    val parts = response.trim().split(":", limit = 2)
                    if (parts.size == 2) {
                        Timber.d("✅ Fetched license keys successfully")
                        return@withContext parts[0].trim() to parts[1].trim()
                    }
                }
            }
            Timber.w("⚠️ Failed to fetch license keys: HTTP ${connection.responseCode}")
            null to null
        } catch (e: Exception) {
            Timber.e(e, "❌ Error fetching license keys from URL")
            null to null
        }
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
            
            // Debug log the final stream URL
            Timber.d("🎬 Channel: ${m3u.name}")
            Timber.d("   Stream URL: ${metaUrl.take(100)}...")
            if (m3u.drmScheme != null) {
                Timber.d("   DRM: ${m3u.drmScheme} | Keys: ${m3u.drmKeyId?.take(8)}...${m3u.drmKey?.take(8)}...")
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
        val parts = mutableListOf(m3u.streamUrl)
        
        // Add User-Agent if set
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        // Add all other HTTP headers
        m3u.httpHeaders.forEach { (k, v) -> parts.add("$k=$v") }
        
        // CRITICAL: Add DRM information ONLY if both scheme and keys are present
        if (m3u.drmScheme != null && m3u.drmKeyId != null && m3u.drmKey != null) {
            parts.add("drmScheme=${m3u.drmScheme}")
            parts.add("drmLicense=${m3u.drmKeyId}:${m3u.drmKey}")
            Timber.d("🔐 Adding DRM to URL: ${m3u.drmScheme} with keys")
        }
        
        val finalUrl = parts.joinToString("|")
        Timber.d("🔗 Built URL: ${finalUrl.take(150)}...")
        return finalUrl
    }
}
