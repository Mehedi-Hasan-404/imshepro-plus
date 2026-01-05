#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "NativeLib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ==================== ENCRYPTED STRINGS ====================
static const char* ENC_DATA_FILE_URL = "\x16\x06\x19\x06\x5f\x0c\x08\x0f\x0a\x5f\x1a\x1c\x0f";

static std::string decrypt(const char* encrypted, size_t length) {
    return "data_file_url"; 
}

// ==================== LISTENER CONFIG STATE ====================
struct ListenerConfigState {
    bool enableDirectLink;
    std::string directLinkUrl;
    std::vector<std::string> allowedPages;
    bool isInitialized;
} static listenerState = {false, "", {}, false};

static std::vector<std::string> triggeredSessions;

// ==================== DATA STORAGE ====================
struct AppData {
    std::string fullJson;  // Store complete JSON (or extracted data object)
    bool isLoaded;
} static appData = {"", false};

static std::string remoteConfigUrl = "";
static bool remoteConfigFetched = false;

// ==================== JSON EXTRACTION HELPERS ====================

// Extract the "data" object from wrapped response
static std::string extractDataObject(const std::string& json) {
    try {
        // Find "data" key
        size_t dataPos = json.find("\"data\"");
        if (dataPos == std::string::npos) {
            LOGD("No 'data' wrapper found, using full JSON");
            return json;
        }
        
        // Find the opening brace after "data"
        size_t startPos = json.find('{', dataPos);
        if (startPos == std::string::npos) {
            LOGD("No opening brace for 'data', using full JSON");
            return json;
        }
        
        // Find the matching closing brace
        int braceCount = 1;
        size_t endPos = startPos + 1;
        
        while (endPos < json.length() && braceCount > 0) {
            if (json[endPos] == '{') {
                braceCount++;
            } else if (json[endPos] == '}') {
                braceCount--;
            }
            endPos++;
        }
        
        if (braceCount != 0) {
            LOGD("Mismatched braces for 'data', using full JSON");
            return json;
        }
        
        // Extract the data object (including braces)
        std::string result = json.substr(startPos, endPos - startPos);
        LOGD("📦 Extracted data object: %zu characters", result.length());
        return result;
        
    } catch (...) {
        LOGE("Exception extracting data object, using full JSON");
        return json;
    }
}

// Extract a specific array from JSON by key
static std::string extractJsonArray(const std::string& json, const std::string& key) {
    try {
        // Find the key in the JSON
        std::string searchKey = "\"" + key + "\"";
        size_t keyPos = json.find(searchKey);
        
        if (keyPos == std::string::npos) {
            LOGE("Key '%s' not found in JSON", key.c_str());
            return "[]";
        }
        
        // Find the opening bracket after the key
        size_t startPos = json.find('[', keyPos);
        if (startPos == std::string::npos) {
            LOGE("Opening bracket not found for key '%s'", key.c_str());
            return "[]";
        }
        
        // Find the matching closing bracket
        int bracketCount = 1;
        size_t endPos = startPos + 1;
        
        while (endPos < json.length() && bracketCount > 0) {
            if (json[endPos] == '[') {
                bracketCount++;
            } else if (json[endPos] == ']') {
                bracketCount--;
            }
            endPos++;
        }
        
        if (bracketCount != 0) {
            LOGE("Mismatched brackets for key '%s'", key.c_str());
            return "[]";
        }
        
        // Extract the array (including brackets)
        std::string result = json.substr(startPos, endPos - startPos);
        LOGD("Extracted %s: %zu characters", key.c_str(), result.length());
        return result;
        
    } catch (...) {
        LOGE("Exception extracting key '%s'", key.c_str());
        return "[]";
    }
}

// Extract listener_config object and populate state
static void extractListenerConfig(const std::string& json) {
    try {
        // Find "listener_config" key
        size_t configPos = json.find("\"listener_config\"");
        if (configPos == std::string::npos) {
            LOGD("No listener_config found");
            listenerState.enableDirectLink = false;
            listenerState.directLinkUrl = "";
            listenerState.isInitialized = false;
            return;
        }
        
        // Check for enable_direct_link
        size_t enablePos = json.find("\"enable_direct_link\"", configPos);
        if (enablePos != std::string::npos) {
            size_t truePos = json.find("true", enablePos);
            size_t falsePos = json.find("false", enablePos);
            
            // Check which comes first after the key
            if (truePos != std::string::npos && (falsePos == std::string::npos || truePos < falsePos)) {
                listenerState.enableDirectLink = true;
                LOGD("✅ Direct link ENABLED");
            } else {
                listenerState.enableDirectLink = false;
                LOGD("❌ Direct link DISABLED");
            }
        }
        
        // Extract direct_link_url
        size_t urlKeyPos = json.find("\"direct_link_url\"", configPos);
        if (urlKeyPos != std::string::npos) {
            size_t urlStart = json.find("\"", urlKeyPos + 18);
            if (urlStart != std::string::npos) {
                urlStart++;
                size_t urlEnd = json.find("\"", urlStart);
                if (urlEnd != std::string::npos) {
                    listenerState.directLinkUrl = json.substr(urlStart, urlEnd - urlStart);
                    LOGD("📍 Direct link URL: %s", listenerState.directLinkUrl.c_str());
                }
            }
        }
        
        listenerState.isInitialized = true;
        
    } catch (...) {
        LOGE("Exception extracting listener_config");
        listenerState.enableDirectLink = false;
        listenerState.directLinkUrl = "";
        listenerState.isInitialized = false;
    }
}

// ==================== JNI EXPORTS ====================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeValidateIntegrity(JNIEnv* env, jobject thiz) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigKey(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("data_file_url");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeStoreData(JNIEnv* env, jobject thiz, jstring jsonData) {
    const char* jsonStr = env->GetStringUTFChars(jsonData, nullptr);
    if (jsonStr == nullptr) {
        LOGE("Failed to get JSON string");
        return JNI_FALSE;
    }
    
    try {
        std::string json(jsonStr);
        
        LOGD("========================================");
        LOGD("STORING DATA");
        LOGD("JSON Length: %zu bytes", json.length());
        LOGD("First 300 chars: %.300s", json.c_str());
        LOGD("========================================");
        
        // Check if this is a wrapped response with "data" key
        if (json.find("\"data\"") != std::string::npos && json.find("\"success\"") != std::string::npos) {
            LOGD("✅ Detected nested 'data' structure (wrapped response)");
            // Extract just the data object
            std::string dataJson = extractDataObject(json);
            appData.fullJson = dataJson;
            LOGD("📦 Stored data object: %zu bytes", dataJson.length());
        } else {
            LOGD("✅ Using full JSON (direct structure, no wrapper)");
            appData.fullJson = json;
        }
        
        appData.isLoaded = true;

        // Extract listener_config from the stored JSON
        extractListenerConfig(appData.fullJson);
        
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        
        LOGD("========================================");
        LOGD("✅ DATA STORED SUCCESSFULLY");
        LOGD("   - Data loaded: %s", appData.isLoaded ? "YES" : "NO");
        LOGD("   - Direct link: %s", listenerState.enableDirectLink ? "ENABLED" : "DISABLED");
        if (listenerState.enableDirectLink) {
            LOGD("   - URL: %s", listenerState.directLinkUrl.c_str());
        }
        LOGD("========================================");
        
        return JNI_TRUE;
        
    } catch (...) {
        LOGE("❌ Exception storing data");
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetCategories(JNIEnv* env, jobject) {
    if (!appData.isLoaded) {
        LOGE("Data not loaded - returning empty array");
        return env->NewStringUTF("[]");
    }
    
    LOGD("========================================");
    LOGD("GETTING CATEGORIES");
    
    // Extract the "categories" array from the stored JSON
    std::string categoriesJson = extractJsonArray(appData.fullJson, "categories");
    
    LOGD("Categories JSON length: %zu", categoriesJson.length());
    LOGD("Categories preview: %.200s", categoriesJson.c_str());
    LOGD("========================================");
    
    return env->NewStringUTF(categoriesJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetChannels(JNIEnv* env, jobject) {
    if (!appData.isLoaded) {
        LOGE("Data not loaded - returning empty array");
        return env->NewStringUTF("[]");
    }
    
    LOGD("========================================");
    LOGD("GETTING CHANNELS");
    
    std::string channelsJson = extractJsonArray(appData.fullJson, "channels");
    
    LOGD("Channels JSON length: %zu", channelsJson.length());
    LOGD("========================================");
    
    return env->NewStringUTF(channelsJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetLiveEvents(JNIEnv* env, jobject) {
    if (!appData.isLoaded) {
        LOGE("Data not loaded - returning empty array");
        return env->NewStringUTF("[]");
    }
    
    LOGD("========================================");
    LOGD("GETTING LIVE EVENTS");
    
    // Try both possible keys: "live_events" and "liveEvents"
    std::string eventsJson = extractJsonArray(appData.fullJson, "live_events");
    if (eventsJson == "[]") {
        LOGD("Trying alternate key 'liveEvents'");
        eventsJson = extractJsonArray(appData.fullJson, "liveEvents");
    }
    
    LOGD("Events JSON length: %zu", eventsJson.length());
    LOGD("========================================");
    
    return env->NewStringUTF(eventsJson.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeIsDataLoaded(JNIEnv* env, jobject) {
    LOGD("isDataLoaded check: %s", appData.isLoaded ? "YES" : "NO");
    return appData.isLoaded ? JNI_TRUE : JNI_FALSE;
}

// ==================== LISTENER MANAGER ====================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeShouldShowLink(JNIEnv* env, jobject, jstring pageType, jstring uniqueId) {
    if (!listenerState.isInitialized) {
        LOGD("Listener not initialized");
        return JNI_FALSE;
    }
    
    if (!listenerState.enableDirectLink) {
        LOGD("Direct link disabled");
        return JNI_FALSE;
    }
    
    LOGD("✅ Should show link: YES");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(JNIEnv* env, jobject) {
    LOGD("Returning URL: %s", listenerState.directLinkUrl.c_str());
    return env->NewStringUTF(listenerState.directLinkUrl.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeResetSessions(JNIEnv* env, jobject) {
    triggeredSessions.clear();
    LOGD("Sessions reset");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeIsConfigValid(JNIEnv* env, jobject) {
    return listenerState.isInitialized ? JNI_TRUE : JNI_FALSE;
}

// ==================== REMOTE CONFIG STORAGE ====================

extern "C" JNIEXPORT void JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeStoreConfigUrl(JNIEnv* env, jobject thiz, jstring configUrl) {
    const char* urlStr = env->GetStringUTFChars(configUrl, nullptr);
    remoteConfigUrl = std::string(urlStr);
    remoteConfigFetched = true;
    LOGD("Config URL stored: %s", remoteConfigUrl.c_str());
    env->ReleaseStringUTFChars(configUrl, urlStr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigUrl(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(remoteConfigFetched ? remoteConfigUrl.c_str() : "");
}
