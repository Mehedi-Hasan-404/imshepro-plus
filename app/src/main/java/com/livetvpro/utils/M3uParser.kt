package com.livetvpro.utils

import android.util.Base64
import com.livetvpro.data.models.Channel
import org.json.JSONArray
import org.json.JSONObject
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
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseJsonPlaylist(jsonContent: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        try {
            val trimmed = jsonContent.trim()
            
            val jsonArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                val jsonObject = JSONObject(trimmed)
                when {
                    jsonObject.has("channels") -> jsonObject.getJSONArray("channels")
                    jsonObject.has("items") -> jsonObject.getJSONArray("items")
                    jsonObject.has("data") -> jsonObject.getJSONArray("data")
                    else -> return emptyList()
                }
            } else {
                return emptyList()
            }
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                
                val name = item.optString("name", "Unknown Channel")
                var link = item.optString("link", "") 
                    .ifEmpty { item.optString("url", "") }
                    .ifEmpty { item.optString("stream", "") }
                    .ifEmpty { item.optString("streamUrl", "") }
                
                val logo = item.optString("logo", "")
                    .ifEmpty { item.optString("logoUrl", "") }
                    .ifEmpty { item.optString("icon", "") }
                
                val category = item.optString("category", "")
                    .ifEmpty { item.optString("group", "") }
                
                val cookie = item.optString("cookie", "")
                val userAgent = item.optString("user-agent", null) 
                    ?: item.optString("user_agent", null)
                    ?: item.optString("userAgent", null)
                val referer = item.optString("referer", null) 
                    ?: item.optString("referrer", null)
                val origin = item.optString("origin", null)
                
                // CRITICAL FIX: Build headers BEFORE decoding proxy URL
                val headers = mutableMapOf<String, String>()
                if (cookie.isNotEmpty()) {
                    headers["Cookie"] = cookie
                }
                referer?.let { 
                    headers["Referer"] = it
                }
                origin?.let { 
                    headers["Origin"] = it
                }
                
                // CRITICAL FIX: Decode proxy URL AFTER extracting headers
                // This ensures authentication is preserved
                link = decodeProxyUrl(link)
                
                var drmScheme = item.optString("drmScheme", null)
                    ?: item.optString("drm_scheme", null)
                    ?: item.optString("drm", null)
                
                var drmKeyId: String? = null
                var drmKey: String? = null
                
                val drmLicense = item.optString("drmLicense", null)
                    ?: item.optString("drm_license", null)
                    ?: item.optString("license", null)
                
                if (drmLicense != null && drmLicense.isNotEmpty()) {
                    if (drmLicense.startsWith("http://", ignoreCase = true) || 
                        drmLicense.startsWith("https://", ignoreCase = true)) {
                        drmKeyId = drmLicense
                        drmKey = "LICENSE_URL"
                    } 
                    else if (drmLicense.contains(":")) {
                        val parts = drmLicense.split(":", limit = 2)
                        if (parts.size == 2) {
                            drmKeyId = parts[0].trim()
                            drmKey = parts[1].trim()
                        }
                    }
                }
                
                if (drmScheme != null) {
                    drmScheme = normalizeDrmScheme(drmScheme)
                }
                
                if (link.isNotEmpty()) {
                    channels.add(M3uChannel(
                        name = name,
                        logoUrl = logo,
                        streamUrl = link,
                        groupTitle = category,
                        userAgent = userAgent,
                        httpHeaders = headers,
                        drmScheme = drmScheme,
                        drmKeyId = drmKeyId,
                        drmKey = drmKey
                    ))
                }
            }
            
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error parsing JSON playlist")
        }
        return channels
    }

    /**
     * IMPROVED: Decode proxy URLs with better error handling
     */
    private fun decodeProxyUrl(url: String): String {
        if (url.isEmpty()) return url
        
        try {
            // Check for proxysite.com URLs
            if (url.contains("proxysite.com/process.php")) {
                return decodeProxySiteUrl(url)
            }
            
            // Check for other proxy patterns
            if (url.contains("/process.php") && url.contains("d=")) {
                return decodeGenericProxyUrl(url)
            }
            
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error decoding proxy URL: $url")
        }
        
        return url
    }
    
    /**
     * NEW: Dedicated proxysite.com decoder
     */
    private fun decodeProxySiteUrl(url: String): String {
        try {
            // Extract the 'd' parameter
            val dParamMatch = Regex("[?&]d=([^&]+)").find(url) ?: return url
            val encodedUrl = dParamMatch.groupValues[1]
            
            // URL decode
            val urlDecoded = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            
            // Base64 decode (proxysite uses URL-safe base64)
            val base64Decoded = try {
                String(Base64.decode(urlDecoded, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                // Try standard base64 if URL-safe fails
                String(Base64.decode(urlDecoded, Base64.DEFAULT))
            }
            
            // Extract URL if it starts with http
            return if (base64Decoded.startsWith("http", ignoreCase = true)) {
                timber.log.Timber.d("Decoded proxysite URL: $base64Decoded")
                base64Decoded
            } else {
                timber.log.Timber.w("Decoded value doesn't look like URL: $base64Decoded")
                url // Return original if decode doesn't look valid
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to decode proxysite URL")
            return url
        }
    }
    
    /**
     * NEW: Generic proxy decoder for other proxy services
     */
    private fun decodeGenericProxyUrl(url: String): String {
        try {
            val dParamMatch = Regex("[?&]d=([^&]+)").find(url) ?: return url
            val encodedUrl = dParamMatch.groupValues[1]
            val decoded = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            
            val base64Decoded = try {
                String(Base64.decode(decoded, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                String(Base64.decode(decoded, Base64.DEFAULT))
            }
            
            return if (base64Decoded.startsWith("http", ignoreCase = true)) {
                base64Decoded
            } else {
                url
            }
        } catch (e: Exception) {
            return url
        }
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
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val rawScheme = trimmedLine.substringAfter("=").trim().lowercase()
                    currentDrmScheme = normalizeDrmScheme(rawScheme)
                }
                
                trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmedLine.substringAfter("=").trim()
                    
                    when {
                        keyValue.startsWith("http://", ignoreCase = true) || 
                        keyValue.startsWith("https://", ignoreCase = true) -> {
                            currentDrmKeyId = keyValue
                            currentDrmKey = "LICENSE_URL"
                        }
                        keyValue.contains(":") && !keyValue.startsWith("{") -> {
                            val parts = keyValue.split(":", limit = 2)
                            if (parts.size == 2) {
                                currentDrmKeyId = parts[0].trim()
                                currentDrmKey = parts[1].trim()
                            }
                        }
                        keyValue.startsWith("{") -> {
                            val (keyId, key) = parseJWKToKeyIdPair(keyValue)
                            if (keyId != null && key != null) {
                                currentDrmKeyId = keyId
                                currentDrmKey = key
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
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    currentHeaders["Origin"] = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    currentHeaders["Referer"] = trimmedLine.substringAfter("=").trim()
                }

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
                        timber.log.Timber.e(e, "Error parsing EXTHTTP")
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
                        
                        currentDrmScheme = null
                        currentDrmKeyId = null
                        currentDrmKey = null
                    }
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
            ""
        }
    }

    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Triple<String?, String?, String?>> {
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        
        // CRITICAL FIX: Handle JioStar-style embedded cookies in URL parameters
        // Format: &xxx=%7Ccookie=VALUE where %7C is URL-encoded pipe (|)
        var cleanUrl = urlLine.trim()
        
        // Check for inline cookie in URL parameter (JioStar format)
        val cookieParamMatch = Regex("[&?]xxx=(%7C|\\|)cookie=([^&]+)").find(cleanUrl)
        if (cookieParamMatch != null) {
            val cookieValue = java.net.URLDecoder.decode(cookieParamMatch.groupValues[2], "UTF-8")
            headers["Cookie"] = cookieValue
            // Remove the xxx parameter from URL
            cleanUrl = cleanUrl.replace(cookieParamMatch.value, "")
            timber.log.Timber.d("Extracted inline cookie from URL: $cookieValue")
        }
        
        // Now check for traditional pipe-separated metadata
        val pipeIndex = cleanUrl.indexOf('|')
        if (pipeIndex == -1) {
            return Triple(cleanUrl, headers, Triple(null, null, null))
        }
        
        val url = cleanUrl.substring(0, pipeIndex).trim()
        val paramsString = cleanUrl.substring(pipeIndex + 1).trim()

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
        val parts = mutableListOf<String>()
        
        // Base URL (already clean from parseInlineHeadersAndDrm)
        parts.add(m3u.streamUrl)
        
        // User agent
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        // HTTP headers (Cookie is now properly extracted)
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
        
        // IMPORTANT: Only use pipe separator if there are metadata parts
        return if (parts.size > 1) {
            parts.joinToString("|")
        } else {
            parts[0] // Just the URL, no metadata
        }
    }
}
