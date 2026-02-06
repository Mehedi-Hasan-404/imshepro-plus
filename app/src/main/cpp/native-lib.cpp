#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "NativeLib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct ListenerConfigState {
    bool enableDirectLink;
    std::string directLinkUrl;
    std::set<std::string> allowedPages;
    bool isInitialized;
} static listenerState = {false, "", {}, false};

static std::set<std::string> triggeredSessions;

struct AppData {
    std::string fullJson;
    bool isLoaded;
} static appData = {"", false};

static std::string remoteConfigUrl = "";
static bool remoteConfigFetched = false;

static std::string extractDataObject(const std::string& json) {
    try {
        size_t dataPos = json.find("\"data\"");
        if (dataPos == std::string::npos) {
            LOGD("No 'data' wrapper found, using full JSON");
            return json;
        }
        
        size_t startPos = json.find('{', dataPos);
        if (startPos == std::string::npos) {
            return json;
        }
        
        int braceCount = 1;
        size_t endPos = startPos + 1;
        
        while (endPos < json.length() && braceCount > 0) {
            if (json[endPos] == '{') braceCount++;
            else if (json[endPos] == '}') braceCount--;
            endPos++;
        }
        
        if (braceCount != 0) return json;
        
        std::string result = json.substr(startPos, endPos - startPos);
        LOGD("📦 Extracted data object: %zu characters", result.length());
        return result;
        
    } catch (...) {
        return json;
    }
}

static std::string extractJsonArray(const std::string& json, const std::string& key) {
    try {
        std::string searchKey = "\"" + key + "\"";
        size_t keyPos = json.find(searchKey);
        
        if (keyPos == std::string::npos) {
            LOGE("Key '%s' not found", key.c_str());
            return "[]";
        }
        
        size_t startPos = json.find('[', keyPos);
        if (startPos == std::string::npos) {
            LOGE("Opening bracket not found for '%s'", key.c_str());
            return "[]";
        }
        
        int bracketCount = 1;
        size_t endPos = startPos + 1;
        
        while (endPos < json.length() && bracketCount > 0) {
            if (json[endPos] == '[') bracketCount++;
            else if (json[endPos] == ']') bracketCount--;
            endPos++;
        }
        
        if (bracketCount != 0) return "[]";
        
        std::string result = json.substr(startPos, endPos - startPos);
        LOGD("Extracted %s: %zu characters", key.c_str(), result.length());
        return result;
        
    } catch (...) {
        return "[]";
    }
}

static void extractListenerConfig(const std::string& json) {
    try {
        size_t configPos = json.find("\"listener_config\"");
        if (configPos == std::string::npos) {
            LOGD("No listener_config found");
            listenerState.enableDirectLink = false;
            listenerState.directLinkUrl = "";
            listenerState.isInitialized = false;
            return;
        }
        
        size_t enablePos = json.find("\"enable_direct_link\"", configPos);
        if (enablePos != std::string::npos) {
            size_t truePos = json.find("true", enablePos);
            size_t falsePos = json.find("false", enablePos);
            
            if (truePos != std::string::npos && (falsePos == std::string::npos || truePos < falsePos)) {
                listenerState.enableDirectLink = true;
                LOGD("✅ Direct link ENABLED");
            } else {
                listenerState.enableDirectLink = false;
                LOGD("❌ Direct link DISABLED");
            }
        }
        
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
        
        listenerState.allowedPages.clear();
        size_t pagesPos = json.find("\"allowed_pages\"", configPos);
        if (pagesPos != std::string::npos) {
            size_t arrayStart = json.find('[', pagesPos);
            if (arrayStart != std::string::npos) {
                size_t arrayEnd = json.find(']', arrayStart);
                if (arrayEnd != std::string::npos) {
                    std::string pagesArray = json.substr(arrayStart + 1, arrayEnd - arrayStart - 1);
                    
                    size_t pos = 0;
                    while (pos < pagesArray.length()) {
                        size_t quoteStart = pagesArray.find('\"', pos);
                        if (quoteStart == std::string::npos) break;
                        
                        size_t quoteEnd = pagesArray.find('\"', quoteStart + 1);
                        if (quoteEnd == std::string::npos) break;
                        
                        std::string pageName = pagesArray.substr(quoteStart + 1, quoteEnd - quoteStart - 1);
                        listenerState.allowedPages.insert(pageName);
                        LOGD("   Allowed page: %s", pageName.c_str());
                        
                        pos = quoteEnd + 1;
                    }
                }
            }
        }
        
        listenerState.isInitialized = true;
        
    } catch (...) {
        LOGE("Exception extracting listener_config");
        listenerState.enableDirectLink = false;
        listenerState.isInitialized = false;
    }
}

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
        
        if (json.find("\"data\"") != std::string::npos && json.find("\"success\"") != std::string::npos) {
            LOGD("✅ Detected nested 'data' structure");
            std::string dataJson = extractDataObject(json);
            appData.fullJson = dataJson;
        } else {
            LOGD("✅ Using full JSON (direct structure)");
            appData.fullJson = json;
        }
        
        appData.isLoaded = true;
        extractListenerConfig(appData.fullJson);
        
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        
        LOGD("========================================");
        LOGD("✅ DATA STORED SUCCESSFULLY");
        LOGD("   - Direct link: %s", listenerState.enableDirectLink ? "ENABLED" : "DISABLED");
        if (listenerState.enableDirectLink) {
            LOGD("   - URL: %s", listenerState.directLinkUrl.c_str());
            LOGD("   - Allowed pages: %zu", listenerState.allowedPages.size());
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
    if (!appData.isLoaded) return env->NewStringUTF("[]");
    std::string categoriesJson = extractJsonArray(appData.fullJson, "categories");
    return env->NewStringUTF(categoriesJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetChannels(JNIEnv* env, jobject) {
    if (!appData.isLoaded) return env->NewStringUTF("[]");
    std::string channelsJson = extractJsonArray(appData.fullJson, "channels");
    return env->NewStringUTF(channelsJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetLiveEvents(JNIEnv* env, jobject) {
    if (!appData.isLoaded) return env->NewStringUTF("[]");
    
    std::string eventsJson = extractJsonArray(appData.fullJson, "live_events");
    if (eventsJson == "[]") {
        eventsJson = extractJsonArray(appData.fullJson, "liveEvents");
    }
    
    return env->NewStringUTF(eventsJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetEventCategories(JNIEnv* env, jobject) {
    if (!appData.isLoaded) return env->NewStringUTF("[]");
    
    std::string eventCategoriesJson = extractJsonArray(appData.fullJson, "event_categories");
    if (eventCategoriesJson == "[]") {
        eventCategoriesJson = extractJsonArray(appData.fullJson, "eventCategories");
    }
    
    return env->NewStringUTF(eventCategoriesJson.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetSports(JNIEnv* env, jobject) {
    if (!appData.isLoaded) return env->NewStringUTF("[]");
    
    // Sports_slug has the same structure as channels, so we extract it as channels
    std::string sportsJson = extractJsonArray(appData.fullJson, "sports_slug");
    if (sportsJson == "[]") {
        sportsJson = extractJsonArray(appData.fullJson, "sports");
    }
    
    LOGD("📺 Sports extracted: %zu characters", sportsJson.length());
    return env->NewStringUTF(sportsJson.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeIsDataLoaded(JNIEnv* env, jobject) {
    return appData.isLoaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeShouldShowLink(
    JNIEnv* env, 
    jobject thiz, 
    jstring pageType, 
    jstring uniqueId
) {
    if (!listenerState.isInitialized || !listenerState.enableDirectLink) {
        LOGD("❌ Link disabled or not initialized");
        return JNI_FALSE;
    }
    
    const char* pageTypeStr = env->GetStringUTFChars(pageType, nullptr);
    if (pageTypeStr == nullptr) {
        return JNI_FALSE;
    }
    std::string pageTypeString(pageTypeStr);
    env->ReleaseStringUTFChars(pageType, pageTypeStr);
    
    if (listenerState.allowedPages.find(pageTypeString) == listenerState.allowedPages.end()) {
        LOGD("❌ Page '%s' not in allowed list", pageTypeString.c_str());
        return JNI_FALSE;
    }
    
    std::string sessionKey = pageTypeString;
    
    if (uniqueId != nullptr) {
        const char* uniqueIdStr = env->GetStringUTFChars(uniqueId, nullptr);
        if (uniqueIdStr != nullptr) {
            sessionKey += ":" + std::string(uniqueIdStr);
            env->ReleaseStringUTFChars(uniqueId, uniqueIdStr);
        }
    }
    
    if (triggeredSessions.find(sessionKey) != triggeredSessions.end()) {
        LOGD("✅ Session '%s' already triggered - ALLOW PLAYBACK", sessionKey.c_str());
        return JNI_FALSE;
    }
    
    triggeredSessions.insert(sessionKey);
    LOGD("🔗 First access to '%s' - SHOW LINK", sessionKey.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.directLinkUrl.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeResetSessions(JNIEnv* env, jobject) {
    triggeredSessions.clear();
    LOGD("🔄 All sessions reset");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeIsConfigValid(JNIEnv* env, jobject) {
    return listenerState.isInitialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeStoreConfigUrl(JNIEnv* env, jobject thiz, jstring configUrl) {
    const char* urlStr = env->GetStringUTFChars(configUrl, nullptr);
    remoteConfigUrl = std::string(urlStr);
    remoteConfigFetched = true;
    env->ReleaseStringUTFChars(configUrl, urlStr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigUrl(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(remoteConfigFetched ? remoteConfigUrl.c_str() : "");
}
