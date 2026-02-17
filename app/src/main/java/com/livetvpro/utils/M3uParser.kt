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
            
            // Handle JSON format directly
            if (trimmedUrl.startsWith("[") || trimmedUrl.startsWith("{")) {
                return parseJsonPlaylist(trimmedUrl)
            }
            
            android.util.Log.d("M3uParser", "Fetching playlist from: $trimmedUrl")
            
            val url = URL(trimmedUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000  // Increased to 30 seconds for slow servers
            connection.readTimeout = 30000      // Increased to 30 seconds
            
            // Enhanced headers for better Xtream Codes compatibility
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Connection", "keep-alive")
            
            // Follow redirects
            connection.instanceFollowRedirects = true
            HttpURLConnection.setFollowRedirects(true)
            
            val responseCode = connection.responseCode
            android.util.Log.d("M3uParser", "Response code: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Get content type for debugging
                val contentType = connection.contentType
                android.util.Log.d("M3uParser", "Content-Type: $contentType")
                
                // Read response
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()
                connection.disconnect()
                
                android.util.Log.d("M3uParser", "Content length: ${content.length} bytes")
                if (content.length > 0) {
                    android.util.Log.d("M3uParser", "First 200 chars: ${content.take(200)}")
                }
                
                if (content.isBlank()) {
                    android.util.Log.e("M3uParser", "Empty response from server")
                    return emptyList()
                }
                
                val trimmedContent = content.trim()
                
                // Check if it's JSON format
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    android.util.Log.d("M3uParser", "Detected JSON format")
                    return parseJsonPlaylist(trimmedContent)
                }
                
                // Check if it's HTML error page (common with auth failures)
                if (trimmedContent.startsWith("<!DOCTYPE", ignoreCase = true) || 
                    trimmedContent.startsWith("<html", ignoreCase = true) ||
                    trimmedContent.startsWith("<?xml", ignoreCase = true)) {
                    android.util.Log.e("M3uParser", "Server returned HTML/XML instead of M3U playlist")
                    android.util.Log.e("M3uParser", "Response preview: ${content.take(500)}")
                    return emptyList()
                }
                
                // Validate M3U format
                if (!trimmedContent.contains("#EXTM3U", ignoreCase = true) && 
                    !trimmedContent.contains("#EXTINF", ignoreCase = true) &&
                    !trimmedContent.startsWith("http", ignoreCase = true)) {
                    android.util.Log.w("M3uParser", "Content doesn't look like standard M3U format")
                    android.util.Log.w("M3uParser", "Content preview: ${content.take(300)}")
                    // Still try to parse it
                }
                
                // Parse M3U content
                val channels = parseM3uContent(content)
                android.util.Log.d("M3uParser", "Successfully parsed ${channels.size} channels")
                
                if (channels.isEmpty()) {
                    android.util.Log.w("M3uParser", "No channels found in playlist")
                }
                
                return channels
                
            } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                android.util.Log.e("M3uParser", "Authentication failed (401). Check username/password in URL")
                return emptyList()
                
            } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                android.util.Log.e("M3uParser", "Access forbidden (403). Server denied access")
                return emptyList()
                
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                android.util.Log.e("M3uParser", "Playlist not found (404). Check URL path")
                return emptyList()
                
            } else if (responseCode >= 500) {
                android.util.Log.e("M3uParser", "Server error ($responseCode). Server is having issues")
                return emptyList()
                
            } else {
                android.util.Log.e("M3uParser", "HTTP error: $responseCode")
                // Try to read error stream for more details
                try {
                    val errorStream = connection.errorStream
                    if (errorStream != null) {
                        val errorReader = BufferedReader(InputStreamReader(errorStream))
                        val errorContent = errorReader.readText()
                        errorReader.close()
                        android.util.Log.e("M3uParser", "Error response: ${errorContent.take(500)}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("M3uParser", "Could not read error stream", e)
                }
                return emptyList()
            }
            
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.e("M3uParser", "Cannot resolve host '${e.message}'. Check server address or network", e)
            return emptyList()
            
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("M3uParser", "Connection timeout. Server too slow or unreachable", e)
            return emptyList()
            
        } catch (e: java.net.ConnectException) {
            android.util.Log.e("M3uParser", "Connection refused. Check if server is running and port is correct", e)
            return emptyList()
            
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("M3uParser", "SSL/TLS error. Try using http:// instead of https://", e)
            return emptyList()
            
        } catch (e: java.io.IOException) {
            android.util.Log.e("M3uParser", "Network I/O error: ${e.message}", e)
            return emptyList()
            
        } catch (e: Exception) {
            android.util.Log.e("M3uParser", "Unexpected error parsing M3U from URL: ${e.message}", e)
            e.printStackTrace()
            return emptyList()
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
                
                val headers = mutableMapOf<String, String>()
                if (cookie.isNotEmpty()) headers["Cookie"] = cookie
                referer?.let { headers["Referer"] = it }
                origin?.let { headers["Origin"] = it }
                
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
        }
        return channels
    }

    private fun decodeProxyUrl(url: String): String {
        if (url.isEmpty()) return url
        
        try {
            // Proxysite.com format
            if (url.contains("proxysite.com/process.php")) {
                return decodeProxySiteUrl(url)
            }
            
            // HideMyAss proxy format
            if (url.contains("hidemyass.com") || url.contains("hma.com")) {
                return decodeHMAProxyUrl(url)
            }
            
            // Generic /process.php proxies
            if (url.contains("/process.php") && url.contains("d=")) {
                return decodeGenericProxyUrl(url)
            }
            
            // KProxy format
            if (url.contains("kproxy.com")) {
                return decodeKProxyUrl(url)
            }
            
            // Hide.me format
            if (url.contains("hide.me/proxy")) {
                return decodeHideMeProxyUrl(url)
            }
            
            // CroxyProxy format
            if (url.contains("croxyproxy.com")) {
                return decodeCroxyProxyUrl(url)
            }
            
            // Generic base64 in URL
            val base64Pattern = Regex("([?&])(url|u|target|dest|destination|link|redirect|goto)=([A-Za-z0-9+/=_-]{20,})")
            val base64Match = base64Pattern.find(url)
            if (base64Match != null) {
                try {
                    val encoded = base64Match.groupValues[3]
                    val decoded = try {
                        String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
                    } catch (e: Exception) {
                        String(Base64.decode(encoded, Base64.DEFAULT))
                    }
                    if (decoded.startsWith("http", ignoreCase = true)) {
                        return decoded
                    }
                } catch (e: Exception) {}
            }
            
        } catch (e: Exception) {
        }
        
        return url
    }
    
    private fun decodeProxySiteUrl(url: String): String {
        try {
            val dParamMatch = Regex("[?&]d=([^&]+)").find(url) ?: return url
            val encodedUrl = dParamMatch.groupValues[1]
            val urlDecoded = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            
            val base64Decoded = try {
                String(Base64.decode(urlDecoded, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                String(Base64.decode(urlDecoded, Base64.DEFAULT))
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
    
    private fun decodeHMAProxyUrl(url: String): String {
        try {
            val pattern = Regex("[?&](url|u|target)=([^&]+)")
            val match = pattern.find(url) ?: return url
            val encoded = match.groupValues[2]
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
            return if (decoded.startsWith("http", ignoreCase = true)) decoded else url
        } catch (e: Exception) {
            return url
        }
    }
    
    private fun decodeKProxyUrl(url: String): String {
        try {
            val pattern = Regex("kproxy\\.com/\\?([^&]+)")
            val match = pattern.find(url) ?: return url
            val encoded = match.groupValues[1]
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
            return if (decoded.startsWith("http", ignoreCase = true)) decoded else url
        } catch (e: Exception) {
            return url
        }
    }
    
    private fun decodeHideMeProxyUrl(url: String): String {
        try {
            val pattern = Regex("[?&]u=([^&]+)")
            val match = pattern.find(url) ?: return url
            val encoded = match.groupValues[1]
            
            val decoded = try {
                String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }
            
            return if (decoded.startsWith("http", ignoreCase = true)) decoded else url
        } catch (e: Exception) {
            return url
        }
    }
    
    private fun decodeCroxyProxyUrl(url: String): String {
        try {
            val pattern = Regex("croxyproxy\\.com/\\?url=([^&]+)")
            val match = pattern.find(url) ?: return url
            val encoded = match.groupValues[1]
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
            return if (decoded.startsWith("http", ignoreCase = true)) decoded else url
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
                        // If it's a URL, it's a license server URL (for Widevine/PlayReady)
                        keyValue.startsWith("http://", ignoreCase = true) || 
                        keyValue.startsWith("https://", ignoreCase = true) -> {
                            // This is a license server URL, store it in drmKey (which becomes drmLicenseUrl)
                            currentDrmKey = keyValue
                            // Don't set currentDrmKeyId for URL-based licenses
                            currentDrmKeyId = null
                        }
                        // For ClearKey: keyId:key format
                        keyValue.contains(":") && !keyValue.startsWith("{") -> {
                            val parts = keyValue.split(":", limit = 2)
                            if (parts.size == 2) {
                                currentDrmKeyId = parts[0].trim()
                                currentDrmKey = parts[1].trim()
                            }
                        }
                        // For ClearKey: JWK format
                        keyValue.startsWith("{") -> {
                            val (keyId, key) = parseJWKToKeyIdPair(keyValue)
                            if (keyId != null && key != null) {
                                currentDrmKeyId = keyId
                                currentDrmKey = key
                            }
                        }
                    }
                }
                
                trimmedLine.startsWith("#KODIPROP:") && trimmedLine.contains("user-agent=", ignoreCase = true) -> {
                    val uaMatch = Regex("#KODIPROP:.*user-agent=([^\\s|]+)", RegexOption.IGNORE_CASE).find(trimmedLine)
                    if (uaMatch != null) {
                        currentUserAgent = uaMatch.groupValues[1].trim()
                    }
                }
                
                trimmedLine.startsWith("#KODIPROP:") && trimmedLine.contains("referer=", ignoreCase = true) -> {
                    val refMatch = Regex("#KODIPROP:.*referer=([^\\s|]+)", RegexOption.IGNORE_CASE).find(trimmedLine)
                    if (refMatch != null) {
                        currentHeaders["Referer"] = refMatch.groupValues[1].trim()
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
                    }
                }
                
                !trimmedLine.startsWith("#") -> {
                    if (currentName.isNotEmpty()) {
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineMetadata(trimmedLine)
                        
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

    private fun parseInlineMetadata(urlLine: String): Triple<String, Map<String, String>, Triple<String?, String?, String?>> {
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        
        var cleanUrl = urlLine.trim()
        
        // Format 1: Embedded parameter with pipe separator: &param=%7Ckey=VALUE or &param=|key=VALUE
        val pipeParamPattern = Regex("[&?]([^=]+)=(?:%7C|\\|)([^=]+)=([^&]+)")
        val pipeParamMatch = pipeParamPattern.find(cleanUrl)
        if (pipeParamMatch != null) {
            try {
                val headerKey = pipeParamMatch.groupValues[2].trim()
                val rawValue = pipeParamMatch.groupValues[3]
                val decodedValue = java.net.URLDecoder.decode(rawValue, "UTF-8")
                
                when (headerKey.lowercase()) {
                    "cookie" -> headers["Cookie"] = decodedValue
                    "referer", "referrer" -> headers["Referer"] = decodedValue
                    "user-agent", "useragent" -> headers["User-Agent"] = decodedValue
                    "origin" -> headers["Origin"] = decodedValue
                    "authorization", "auth" -> headers["Authorization"] = decodedValue
                }
                
                cleanUrl = cleanUrl.replace(pipeParamMatch.value, "")
            } catch (e: Exception) {}
        }
        
        // Format 2: Base64 encoded metadata in URL parameter: ?token=BASE64DATA or &auth=BASE64DATA
        val base64Patterns = listOf("token", "auth", "key", "data", "meta", "params", "h", "headers", "b64", "encoded")
        for (paramName in base64Patterns) {
            val pattern = Regex("[&?]$paramName=([A-Za-z0-9+/=_-]{20,})")
            val match = pattern.find(cleanUrl)
            if (match != null) {
                try {
                    val encoded = match.groupValues[1]
                    val decoded = try {
                        String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
                    } catch (e: Exception) {
                        String(Base64.decode(encoded, Base64.DEFAULT))
                    }
                    
                    when {
                        decoded.startsWith("{") && decoded.endsWith("}") -> {
                            try {
                                val json = JSONObject(decoded)
                                json.keys().forEach { key ->
                                    headers[key] = json.optString(key, "")
                                }
                                cleanUrl = cleanUrl.replace(match.value, "")
                            } catch (e: Exception) {}
                        }
                        decoded.contains("=") -> {
                            val decodedParts = decoded.split(Regex("[&|;]"))
                            var foundHeaders = false
                            for (part in decodedParts) {
                                val eqIndex = part.indexOf('=')
                                if (eqIndex == -1) continue
                                val key = part.substring(0, eqIndex).trim()
                                val value = part.substring(eqIndex + 1).trim()
                                when (key.lowercase()) {
                                    "cookie" -> { headers["Cookie"] = value; foundHeaders = true }
                                    "referer", "referrer" -> { headers["Referer"] = value; foundHeaders = true }
                                    "user-agent", "useragent" -> { headers["User-Agent"] = value; foundHeaders = true }
                                    "origin" -> { headers["Origin"] = value; foundHeaders = true }
                                    "authorization", "auth" -> { headers["Authorization"] = value; foundHeaders = true }
                                }
                            }
                            if (foundHeaders) {
                                cleanUrl = cleanUrl.replace(match.value, "")
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        
        // Format 3: URL-encoded JSON in parameter: ?headers=%7B%22Cookie%22%3A%22value%22%7D
        val jsonPattern = Regex("[&?](headers?|hdr|h)=(%7B[^&]+|\\{[^&]+)")
        val jsonMatch = jsonPattern.find(cleanUrl)
        if (jsonMatch != null) {
            try {
                val encodedJson = jsonMatch.groupValues[2]
                val decodedJson = if (encodedJson.startsWith("%")) {
                    java.net.URLDecoder.decode(encodedJson, "UTF-8")
                } else {
                    encodedJson
                }
                val json = JSONObject(decodedJson)
                
                json.keys().forEach { key ->
                    headers[key] = json.optString(key, "")
                }
                
                cleanUrl = cleanUrl.replace(jsonMatch.value, "")
            } catch (e: Exception) {}
        }
        
        // Format 4: Multiple cookie parameters: &c1=value1&c2=value2&c3=value3
        val multipleCookies = mutableListOf<String>()
        val cookiePattern = Regex("[&?](c|cookie)\\d+=([^&]+)")
        var cookieMatch = cookiePattern.find(cleanUrl)
        while (cookieMatch != null) {
            try {
                val value = java.net.URLDecoder.decode(cookieMatch.groupValues[2], "UTF-8")
                multipleCookies.add(value)
                cleanUrl = cleanUrl.replace(cookieMatch.value, "")
            } catch (e: Exception) {}
            cookieMatch = cookiePattern.find(cleanUrl)
        }
        if (multipleCookies.isNotEmpty()) {
            headers["Cookie"] = multipleCookies.joinToString("; ")
        }
        
        // Format 5: Hash-based auth: #auth=value or #token=value
        val hashIndex = cleanUrl.indexOf('#')
        if (hashIndex != -1) {
            val hashPart = cleanUrl.substring(hashIndex + 1)
            val hashParts = hashPart.split('&')
            for (part in hashParts) {
                val eqIndex = part.indexOf('=')
                if (eqIndex == -1) continue
                val key = part.substring(0, eqIndex).trim()
                val value = part.substring(eqIndex + 1).trim()
                
                when (key.lowercase()) {
                    "auth", "token", "key", "bearer", "jwt" -> headers["Authorization"] = if (value.startsWith("Bearer ")) value else "Bearer $value"
                    "cookie" -> headers["Cookie"] = value
                    "apikey", "api-key", "api_key" -> headers["X-API-Key"] = value
                }
            }
            cleanUrl = cleanUrl.substring(0, hashIndex)
        }
        
        // Format 6: Colon-separated headers in URL: URL::Cookie=val::Referer=val
        val doubleColonPattern = Regex("::([^:=]+)=([^:]+)")
        var doubleColonMatch = doubleColonPattern.find(cleanUrl)
        while (doubleColonMatch != null) {
            try {
                val key = doubleColonMatch.groupValues[1].trim()
                val value = doubleColonMatch.groupValues[2].trim()
                when (key.lowercase()) {
                    "cookie" -> headers["Cookie"] = value
                    "referer", "referrer" -> headers["Referer"] = value
                    "user-agent", "useragent", "ua" -> headers["User-Agent"] = value
                    "origin" -> headers["Origin"] = value
                    "authorization", "auth" -> headers["Authorization"] = value
                }
                cleanUrl = cleanUrl.replace(doubleColonMatch.value, "")
            } catch (e: Exception) {}
            doubleColonMatch = doubleColonPattern.find(cleanUrl)
        }
        
        // Format 7: Semicolon-separated headers: URL;cookie=val;referer=val
        val semicolonIndex = cleanUrl.indexOf(';')
        if (semicolonIndex != -1 && !cleanUrl.contains('|')) {
            val url = cleanUrl.substring(0, semicolonIndex).trim()
            val paramsString = cleanUrl.substring(semicolonIndex + 1).trim()
            
            if (paramsString.isNotEmpty()) {
                val parts = paramsString.split(';')
                var foundHeaders = false
                for (part in parts) {
                    val eqIndex = part.indexOf('=')
                    if (eqIndex == -1) continue
                    val key = part.substring(0, eqIndex).trim()
                    val value = part.substring(eqIndex + 1).trim()
                    
                    when (key.lowercase()) {
                        "cookie" -> { headers["Cookie"] = value; foundHeaders = true }
                        "referer", "referrer" -> { headers["Referer"] = value; foundHeaders = true }
                        "user-agent", "useragent", "ua" -> { headers["User-Agent"] = value; foundHeaders = true }
                        "origin" -> { headers["Origin"] = value; foundHeaders = true }
                    }
                }
                if (foundHeaders) {
                    cleanUrl = url
                }
            }
        }
        
        // Format 8: Custom separator patterns: URL$header1=val$header2=val
        val customSeparators = listOf("$", "@@", "##", "%%")
        for (separator in customSeparators) {
            val sepIndex = cleanUrl.indexOf(separator)
            if (sepIndex != -1 && !cleanUrl.contains('|')) {
                val url = cleanUrl.substring(0, sepIndex).trim()
                val paramsString = cleanUrl.substring(sepIndex + separator.length).trim()
                
                if (paramsString.isNotEmpty()) {
                    val parts = paramsString.split(separator)
                    var foundHeaders = false
                    for (part in parts) {
                        val eqIndex = part.indexOf('=')
                        if (eqIndex == -1) continue
                        val key = part.substring(0, eqIndex).trim()
                        val value = part.substring(eqIndex + 1).trim()
                        
                        when (key.lowercase()) {
                            "cookie" -> { headers["Cookie"] = value; foundHeaders = true }
                            "referer", "referrer" -> { headers["Referer"] = value; foundHeaders = true }
                            "user-agent", "useragent", "ua" -> { headers["User-Agent"] = value; foundHeaders = true }
                            "origin" -> { headers["Origin"] = value; foundHeaders = true }
                            "authorization", "auth" -> { headers["Authorization"] = value; foundHeaders = true }
                        }
                    }
                    if (foundHeaders) {
                        cleanUrl = url
                        break
                    }
                }
            }
        }
        
        // Format 9: Common query parameters as headers
        val (urlAfterQuery, queryHeaders) = extractHeadersFromQueryParams(cleanUrl)
        headers.putAll(queryHeaders)
        cleanUrl = urlAfterQuery
        
        cleanUrl = cleanUrl.replace(Regex("[&?]$"), "")
        
        // Format 10: Traditional pipe separator: URL|key=value&key2=value2
        val pipeIndex = cleanUrl.indexOf('|')
        if (pipeIndex != -1) {
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
                        "drmscheme", "drm-scheme", "drm_scheme", "drm" -> drmScheme = normalizeDrmScheme(value)
                        "drmlicense", "drm-license", "drm_license", "license", "lic" -> {
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
                        "drmkey", "drm-key", "drm_key", "kid" -> drmKeyId = value
                        "key", "k" -> if (drmKeyId != null) drmKey = value
                        "user-agent", "useragent", "user_agent", "ua" -> headers["User-Agent"] = value
                        "referer", "referrer", "ref" -> headers["Referer"] = value
                        "origin", "org" -> headers["Origin"] = value
                        "cookie", "cookies" -> if (!headers.containsKey("Cookie")) headers["Cookie"] = value
                        "x-forwarded-for", "x_forwarded_for", "xff" -> headers["X-Forwarded-For"] = value
                        "authorization", "auth", "bearer", "token" -> headers["Authorization"] = if (value.startsWith("Bearer ", ignoreCase = true)) value else "Bearer $value"
                        "content-type", "content_type", "ct" -> headers["Content-Type"] = value
                        "accept", "acc" -> headers["Accept"] = value
                        "range" -> headers["Range"] = value
                        "host" -> headers["Host"] = value
                        "connection", "conn" -> headers["Connection"] = value
                        "cache-control", "cache_control" -> headers["Cache-Control"] = value
                        "pragma" -> headers["Pragma"] = value
                        "upgrade-insecure-requests" -> headers["Upgrade-Insecure-Requests"] = value
                        "sec-fetch-site" -> headers["Sec-Fetch-Site"] = value
                        "sec-fetch-mode" -> headers["Sec-Fetch-Mode"] = value
                        "sec-fetch-dest" -> headers["Sec-Fetch-Dest"] = value
                        "apikey", "api-key", "api_key" -> headers["X-API-Key"] = value
                        else -> if (key.startsWith("x-", ignoreCase = true) || key.startsWith("sec-", ignoreCase = true) || key.contains("-")) {
                            headers[key] = value
                        }
                    }
                }
            }
            
            return Triple(url, headers, Triple(drmScheme, drmKeyId, drmKey))
        }
        
        return Triple(cleanUrl, headers, Triple(drmScheme, drmKeyId, drmKey))
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
            lower.contains("clearkey") || lower == "org.w3.clearkey" || lower == "cenc" -> "clearkey"
            lower.contains("widevine") || lower == "com.widevine.alpha" -> "widevine"
            lower.contains("playready") || lower == "com.microsoft.playready" -> "playready"
            lower.contains("fairplay") || lower == "com.apple.fps" || lower == "fps" -> "fairplay"
            lower.contains("marlin") -> "marlin"
            lower.contains("primetime") || lower.contains("adobe") -> "primetime"
            lower.contains("verimatrix") -> "verimatrix"
            lower.contains("securemedia") -> "securemedia"
            lower.contains("irdeto") -> "irdeto"
            lower.contains("nagra") -> "nagra"
            else -> lower
        }
    }
    
    private fun extractHeadersFromQueryParams(url: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        var cleanUrl = url
        
        val commonHeaderParams = mapOf(
            "ua" to "User-Agent",
            "user-agent" to "User-Agent",
            "useragent" to "User-Agent",
            "ref" to "Referer",
            "referer" to "Referer",
            "referrer" to "Referer",
            "cookie" to "Cookie",
            "cookies" to "Cookie",
            "origin" to "Origin",
            "auth" to "Authorization",
            "authorization" to "Authorization",
            "bearer" to "Authorization",
            "token" to "Authorization",
            "jwt" to "Authorization",
            "xff" to "X-Forwarded-For",
            "x-forwarded-for" to "X-Forwarded-For",
            "range" to "Range",
            "accept" to "Accept",
            "apikey" to "X-API-Key",
            "api-key" to "X-API-Key",
            "api_key" to "X-API-Key"
        )
        
        for ((param, headerName) in commonHeaderParams) {
            val pattern = Regex("[&?]$param=([^&]+)", RegexOption.IGNORE_CASE)
            val match = pattern.find(cleanUrl)
            if (match != null) {
                try {
                    val value = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                    
                    if (headerName == "Authorization") {
                        headers[headerName] = when {
                            value.startsWith("Bearer ", ignoreCase = true) -> value
                            value.contains(".") && value.split(".").size == 3 -> "Bearer $value"
                            else -> "Bearer $value"
                        }
                    } else {
                        headers[headerName] = value
                    }
                    
                    cleanUrl = cleanUrl.replace(match.value, "")
                } catch (e: Exception) {}
            }
        }
        
        cleanUrl = cleanUrl.replace(Regex("[&?]+$"), "").replace(Regex("\\?&"), "?")
        
        return Pair(cleanUrl, headers)
    }
    
    private fun decodeJWT(token: String): Map<String, String>? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            
            val payload = parts[1]
            val paddedPayload = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = String(Base64.decode(paddedPayload.replace("-", "+").replace("_", "/"), Base64.DEFAULT))
            
            val json = JSONObject(decoded)
            val headers = mutableMapOf<String, String>()
            
            json.keys().forEach { key ->
                headers[key] = json.optString(key, "")
            }
            
            headers
        } catch (e: Exception) {
            null
        }
    }
    
    private fun detectStreamType(url: String): String {
        return when {
            url.contains(".m3u8", ignoreCase = true) || url.contains("/hls/", ignoreCase = true) -> "HLS"
            url.contains(".mpd", ignoreCase = true) || url.contains("/dash/", ignoreCase = true) -> "DASH"
            url.contains(".ism", ignoreCase = true) || url.contains("manifest", ignoreCase = true) -> "SMOOTH"
            url.contains(".flv", ignoreCase = true) -> "FLV"
            url.contains("rtmp://", ignoreCase = true) || url.contains("rtmps://", ignoreCase = true) -> "RTMP"
            url.contains(".ts", ignoreCase = true) -> "TS"
            url.contains(".mp4", ignoreCase = true) -> "MP4"
            url.contains(".mkv", ignoreCase = true) -> "MKV"
            else -> "UNKNOWN"
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
    categoryName = categoryName,
    groupTitle = m3u.groupTitle  
)
        }
    }

    private fun buildStreamUrlWithMetadata(m3u: M3uChannel): String {
        val parts = mutableListOf<String>()
        
        parts.add(m3u.streamUrl)
        
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        
        m3u.httpHeaders.forEach { (key, value) -> 
            parts.add("$key=$value")
        }
        
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
        
        return if (parts.size > 1) {
            parts.joinToString("|")
        } else {
            parts[0]
        }
    }
}
