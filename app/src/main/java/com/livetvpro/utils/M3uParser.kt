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

        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("              📡 PARSING M3U CONTENT")
        Timber.d("╚═══════════════════════════════════════════════════════╝")

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            if (trimmedLine.startsWith("#EXTM3U")) continue

            when {
                // --- DRM Parsing (KODIPROP) - MUST BE BEFORE #EXTINF ---
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val rawScheme = trimmedLine.substringAfter("=").trim().lowercase()
                    currentDrmScheme = normalizeDrmScheme(rawScheme)
                    Timber.d("🔐 DRM Scheme: $currentDrmScheme (raw: $rawScheme)")
                }
                
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmedLine.substringAfter("=").trim()
                    
                    when {
                        // Format 1: Simple KeyID:Key (hex) - MOST COMMON
                        keyValue.contains(":") && !keyValue.startsWith("http") && !keyValue.startsWith("{") -> {
                            val parts = keyValue.split(":", limit = 2)
                            if (parts.size == 2) {
                                currentDrmKeyId = parts[0].trim()
                                currentDrmKey = parts[1].trim()
                                Timber.d("🔑 DRM Keys (HEX):")
                                Timber.d("   KeyID: ${currentDrmKeyId?.take(16)}... (${currentDrmKeyId?.length} chars)")
                                Timber.d("   Key:   ${currentDrmKey?.take(16)}... (${currentDrmKey?.length} chars)")
                            }
                        }
                        
                        // Format 2: URL-based license key
                        keyValue.startsWith("http") -> {
                            currentLicenseUrl = keyValue
                            Timber.d("🌐 DRM License URL: $keyValue")
                        }
                        
                        // Format 3: JWK JSON format
                        keyValue.startsWith("{") -> {
                            val (keyId, key) = parseJWKToKeyIdPair(keyValue)
                            if (keyId != null && key != null) {
                                currentDrmKeyId = keyId
                                currentDrmKey = key
                                Timber.d("🔑 DRM Keys (JWK):")
                                Timber.d("   KeyID: ${currentDrmKeyId?.take(16)}...")
                                Timber.d("   Key:   ${currentDrmKey?.take(16)}...")
                            }
                        }
                    }
                }
                
                // Parse other KODIPROP directives
                trimmedLine.startsWith("#KODIPROP:") -> {
                    // Handle other KODIPROP properties if needed
                    Timber.d("📋 KODIPROP: ${trimmedLine.take(50)}...")
                }
                
                trimmedLine.startsWith("#EXTINF:") -> {
                    // DON'T reset DRM info here - only reset headers/user-agent
                    currentUserAgent = null
                    currentHeaders = mutableMapOf()
                    currentLicenseUrl = null
                    
                    currentName = extractChannelName(trimmedLine)
                    currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                    currentGroup = extractAttribute(trimmedLine, "group-title")
                    
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("📺 Channel: $currentName")
                    if (currentDrmScheme != null) {
                        Timber.d("   🔐 DRM Pending: $currentDrmScheme")
                    }
                }
                
                // --- Custom Header Parsing (VLC options) ---
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("=").trim()
                    Timber.d("   🖥️  User-Agent: ${currentUserAgent?.take(40)}...")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    currentHeaders["Origin"] = trimmedLine.substringAfter("=").trim()
                    Timber.d("   🌐 Origin: ${currentHeaders["Origin"]}")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    currentHeaders["Referer"] = trimmedLine.substringAfter("=").trim()
                    Timber.d("   🌐 Referer: ${currentHeaders["Referer"]?.take(40)}...")
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
                            Timber.d("   🌐 EXTHTTP headers: ${currentHeaders.keys}")
                        } else {
                            val cookieMatch = Regex(""""cookie"\s*:\s*"([^"]+)"""").find(jsonPart)
                            cookieMatch?.groups?.get(1)?.value?.let { 
                                currentHeaders["Cookie"] = it 
                                Timber.d("   🍪 Cookie: ${it.take(30)}...")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error parsing #EXTHTTP")
                    }
                }
                
                !trimmedLine.startsWith("#") -> {
                    // This is the URL line - Process and commit channel
                    if (currentName.isNotEmpty()) {
                        // FIX 1: Better handling of inline parameters
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(trimmedLine)
                        
                        // Merge all headers (M3U tags + inline)
                        val finalHeaders = currentHeaders.toMutableMap()
                        finalHeaders.putAll(inlineHeaders)
                        
                        // FIX 2: Prioritize inline DRM info if both exist (inline is more specific)
                        val finalDrmScheme = inlineDrmInfo.first ?: currentDrmScheme
                        val finalDrmKeyId = inlineDrmInfo.second ?: currentDrmKeyId
                        val finalDrmKey = inlineDrmInfo.third ?: currentDrmKey
                        
                        // If we have a license URL, try to fetch keys
                        var licenseKeyId = finalDrmKeyId
                        var licenseKey = finalDrmKey
                        
                        if (currentLicenseUrl != null && licenseKeyId == null) {
                            Timber.d("   📄 License URL detected: $currentLicenseUrl")
                            licenseKeyId = "URL:$currentLicenseUrl"
                            licenseKey = "FETCH_REQUIRED"
                        }
                        
                        // FIX 3: Validate DRM configuration before adding
                        val hasDrm = finalDrmScheme != null
                        val hasKeys = licenseKeyId != null && licenseKey != null
                        
                        if (hasDrm && !hasKeys) {
                            Timber.w("   ⚠️ DRM scheme present but NO KEYS - stream may fail")
                        }
                        
                        channels.add(M3uChannel(
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = streamUrl,
                            groupTitle = currentGroup,
                            userAgent = currentUserAgent,
                            httpHeaders = finalHeaders,
                            drmScheme = finalDrmScheme,
                            drmKeyId = licenseKeyId,
                            drmKey = licenseKey
                        ))
                        
                        // Log final status
                        val drmStatus = if (finalDrmScheme != null) {
                            if (hasKeys) {
                                "✅ DRM: $finalDrmScheme (with keys)"
                            } else {
                                "⚠️ DRM: $finalDrmScheme (NO KEYS)"
                            }
                        } else {
                            "🔓 No DRM"
                        }
                        Timber.d("   $drmStatus")
                        if (hasKeys && finalDrmScheme != null) {
                            Timber.d("   🔑 KeyID: ${licenseKeyId?.take(16)}...")
                            Timber.d("   🔑 Key: ${licenseKey?.take(16)}...")
                        }
                        Timber.d("   📍 URL: ${streamUrl.take(60)}...")
                        
                        // NOW reset DRM info for next channel
                        currentDrmScheme = null
                        currentDrmKeyId = null
                        currentDrmKey = null
                        currentLicenseUrl = null
                    }
                }
            }
        }
        
        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("📊 PARSING SUMMARY")
        Timber.d("   Total channels: ${channels.size}")
        Timber.d("   With DRM: ${channels.count { it.drmScheme != null }}")
        Timber.d("   DRM with keys: ${channels.count { it.drmScheme != null && it.drmKeyId != null && it.drmKey != null }}")
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

    /**
     * Normalize DRM scheme names to standard formats
     */
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

    /**
     * Parse JWK (JSON Web Key) format to KeyID:Key pair
     * Example input: { "keys":[ { "kty":"oct", "k":"base64key", "kid":"base64keyid" } ], "type":"temporary" }
     */
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
            var base64 = base64Url.replace('-', '+').replace('_', '/')
            
            while (base64.length % 4 != 0) {
                base64 += "="
            }
            
            val bytes = Base64.decode(base64, Base64.NO_WRAP or Base64.URL_SAFE)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            Timber.d("🔄 Base64→Hex: $base64Url → ${hex.take(16)}...")
            hex
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to convert base64url to hex: $base64Url")
            ""
        }
    }

    // FIX 4: Better inline parameter parsing with proper delimiter handling
    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Triple<String?, String?, String?>> {
        // First check if there's a pipe separator
        val pipeIndex = urlLine.indexOf('|')
        if (pipeIndex == -1) {
            // No inline parameters
            return Triple(urlLine.trim(), emptyMap(), Triple(null, null, null))
        }
        
        val url = urlLine.substring(0, pipeIndex).trim()
        val paramsString = urlLine.substring(pipeIndex + 1).trim()
        
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null

        if (paramsString.isNotEmpty()) {
            // Split by & or | (both are valid separators)
            val parts = paramsString.split(Regex("[&|]"))
            
            Timber.d("   📦 Parsing inline parameters (${parts.size} parts)")
            
            for (part in parts) {
                val trimmedPart = part.trim()
                if (trimmedPart.isEmpty()) continue
                
                val eqIndex = trimmedPart.indexOf('=')
                if (eqIndex == -1) {
                    Timber.w("      ⚠️ Invalid parameter (no =): $trimmedPart")
                    continue
                }
                
                val key = trimmedPart.substring(0, eqIndex).trim()
                val value = trimmedPart.substring(eqIndex + 1).trim()
                
                when (key.lowercase()) {
                    "drmscheme" -> {
                        drmScheme = normalizeDrmScheme(value)
                        Timber.d("      🔐 Inline DRM Scheme: $drmScheme")
                    }
                    "drmlicense" -> {
                        // Handle KeyID:Key format
                        val colonIndex = value.indexOf(':')
                        if (colonIndex != -1) {
                            drmKeyId = value.substring(0, colonIndex).trim()
                            drmKey = value.substring(colonIndex + 1).trim()
                            Timber.d("      🔑 Inline DRM Keys:")
                            Timber.d("         KeyID: ${drmKeyId?.take(16)}... (${drmKeyId?.length} chars)")
                            Timber.d("         Key:   ${drmKey?.take(16)}... (${drmKey?.length} chars)")
                        } else {
                            Timber.w("      ⚠️ Invalid drmLicense format (no :): $value")
                        }
                    }
                    "user-agent", "useragent" -> {
                        headers["User-Agent"] = value
                        Timber.d("      🖥️ User-Agent: ${value.take(30)}...")
                    }
                    "referer", "referrer" -> {
                        headers["Referer"] = value
                        Timber.d("      🌐 Referer: ${value.take(40)}...")
                    }
                    "origin" -> {
                        headers["Origin"] = value
                        Timber.d("      🌐 Origin: $value")
                    }
                    "cookie" -> {
                        headers["Cookie"] = value
                        Timber.d("      🍪 Cookie: ${value.take(30)}...")
                    }
                    "x-forwarded-for" -> {
                        headers["X-Forwarded-For"] = value
                        Timber.d("      📡 X-Forwarded-For: $value")
                    }
                    else -> {
                        headers[key] = value
                        Timber.d("      🔧 Custom: $key")
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
                
                if (response.trim().startsWith("{")) {
                    val json = JSONObject(response)
                    val keyId = json.optString("keyId", null) ?: json.optString("kid", null)
                    val key = json.optString("key", null) ?: json.optString("k", null)
                    Timber.d("✅ Fetched license keys successfully")
                    return@withContext keyId to key
                } else if (response.contains(":")) {
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
        Timber.d("╔═══════════════════════════════════════════════════════╗")
        Timber.d("   🔄 CONVERTING TO CHANNELS")
        Timber.d("╚═══════════════════════════════════════════════════════╝")
        
        return m3uChannels.mapIndexed { index, m3u ->
            val metaUrl = buildStreamUrlWithMetadata(m3u)
            
            Timber.d("${index + 1}. ${m3u.name}")
            if (m3u.drmScheme != null) {
                Timber.d("   🔐 DRM: ${m3u.drmScheme}")
                if (m3u.drmKeyId != null && m3u.drmKey != null) {
                    Timber.d("   🔑 KeyID: ${m3u.drmKeyId.take(16)}...")
                    Timber.d("   🔑 Key: ${m3u.drmKey.take(16)}...")
                } else {
                    Timber.w("   ⚠️ DRM scheme present but NO KEYS")
                }
            }
            if (m3u.httpHeaders.isNotEmpty()) {
                Timber.d("   📦 Headers: ${m3u.httpHeaders.keys.joinToString(", ")}")
            }
            Timber.d("   📍 Final URL: ${metaUrl.take(80)}...")
            
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

    // FIX 5: Simplified buildStreamUrlWithMetadata - preserve exact format
    private fun buildStreamUrlWithMetadata(m3u: M3uChannel): String {
        val parts = mutableListOf<String>()
        
        // Start with base URL
        parts.add(m3u.streamUrl)
        
        // Add user agent if present
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        // Add HTTP headers
        m3u.httpHeaders.forEach { (key, value) -> 
            parts.add("$key=$value")
        }
        
        // FIX: Add DRM information in the exact format the player expects
        if (m3u.drmScheme != null) {
            parts.add("drmScheme=${m3u.drmScheme}")
            
            // Add license keys if available
            if (m3u.drmKeyId != null && m3u.drmKey != null) {
                parts.add("drmLicense=${m3u.drmKeyId}:${m3u.drmKey}")
                Timber.d("   ✅ DRM info added to URL: ${m3u.drmScheme} with keys")
            } else {
                Timber.w("   ⚠️ DRM scheme '${m3u.drmScheme}' added but NO KEYS - playback will likely fail")
            }
        }
        
        // Join all parts with pipe separator
        return parts.joinToString("|")
    }
}
