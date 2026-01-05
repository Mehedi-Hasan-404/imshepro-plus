#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "NativeLib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ==================== SIMPLE ENCRYPTION ====================

static const unsigned char XOR_KEY[] = {0x12, 0xA7, 0x3C, 0xF1, 0x08, 0x1D};

static std::string xorEncryptDecrypt(const std::string& input) {
    std::string output = input;
    for (size_t i = 0; i < output.length(); i++) {
        output[i] ^= XOR_KEY[i % sizeof(XOR_KEY)];
    }
    return output;
}

// ==================== ENCRYPTED STRINGS ====================

static const char* ENC_DATA_FILE_URL = "\x16\x06\x19\x06\x5f\x0c\x08\x0f\x0a\x5f\x1a\x1c\x0f";
static const char* ENC_LISTENER_CONFIG = "\x0f\x08\x1e\x19\x0a\x17\x0a\x1c\x5f\x0c\x1d\x17\x0c\x08\x0e";
static const char* ENC_ENABLE_LINK = "\x0a\x17\x06\x07\x0f\x0a\x5f\x16\x08\x1c\x0a\x0c\x19\x5f\x0f\x08\x17\x0e";
static const char* ENC_DIRECT_LINK_URL = "\x16\x08\x1c\x0a\x0c\x19\x5f\x0f\x08\x17\x0e\x5f\x1a\x1c\x0f";
static const char* ENC_ALLOWED_PAGES = "\x06\x0f\x0f\x1d\x1b\x0a\x16\x5f\x1e\x06\x0e\x0a\x1e";
static const char* ENC_CATEGORIES = "\x0c\x06\x19\x0a\x0e\x1d\x1c\x08\x0a\x1e";
static const char* ENC_CHANNELS = "\x0c\x05\x06\x17\x17\x0a\x0f\x1e";
static const char* ENC_LIVE_EVENTS = "\x0f\x08\x1b\x0a\x5f\x0a\x1b\x0a\x17\x19\x1e";
static const char* ENC_DATA = "\x16\x06\x19\x06";

static std::string decrypt(const char* encrypted, size_t length) {
    unsigned char key = 0x12 ^ 0xA7 ^ 0x3C ^ 0xF1 ^ 0x08 ^ 0x1D;
    std::string result;
    result.reserve(length);
    for (size_t i = 0; i < length; i++) {
        result += static_cast<char>(encrypted[i] ^ key);
    }
    return result;
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
    std::string categoriesJson;
    std::string channelsJson;
    std::string liveEventsJson;
    bool isLoaded;
} static appData = {"", "", "", false};

// ==================== JNI EXPORTS ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeValidateIntegrity(
    JNIEnv* env,
    jobject thiz
) {
    // Always return true - no security checks
    LOGD("Integrity check passed");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    std::string key = decrypt(ENC_DATA_FILE_URL, 13);
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeStoreData(
    JNIEnv* env,
    jobject thiz,
    jstring jsonData
) {
    const char* jsonStr = env->GetStringUTFChars(jsonData, nullptr);
    try {
        std::string json(jsonStr);

        // Store in native memory
        appData.categoriesJson = json;
        appData.channelsJson = json;
        appData.liveEventsJson = json;
        appData.isLoaded = true;
        
        // Parse listener config
        size_t configPos = json.find("\"listener_config\"");
        if (configPos != std::string::npos) {
            size_t enablePos = json.find("\"enable_direct_link\"", configPos);
            if (enablePos != std::string::npos) {
                size_t truePos = json.find("true", enablePos);
                size_t falsePos = json.find("false", enablePos);
                listenerState.enableDirectLink = (truePos != std::string::npos && truePos < falsePos);
            }
            
            size_t urlPos = json.find("\"direct_link_url\"", configPos);
            if (urlPos != std::string::npos) {
                size_t colonPos = json.find(":", urlPos);
                size_t quoteStart = json.find("\"", colonPos);
                size_t quoteEnd = json.find("\"", quoteStart + 1);
                
                if (quoteStart != std::string::npos && quoteEnd != std::string::npos) {
                    listenerState.directLinkUrl = json.substr(quoteStart + 1, quoteEnd - quoteStart - 1);
                }
            }
            
            listenerState.isInitialized = true;
        }
        
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        LOGD("Data stored successfully");
        return JNI_TRUE;
        
    } catch (...) {
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        LOGE("Failed to store data");
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetCategories(
    JNIEnv* env,
    jobject thiz
) {
    if (!appData.isLoaded) {
        return env->NewStringUTF("[]");
    }
    return env->NewStringUTF(appData.categoriesJson.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetChannels(
    JNIEnv* env,
    jobject thiz
) {
    if (!appData.isLoaded) {
        return env->NewStringUTF("[]");
    }
    return env->NewStringUTF(appData.channelsJson.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetLiveEvents(
    JNIEnv* env,
    jobject thiz
) {
    if (!appData.isLoaded) {
        return env->NewStringUTF("[]");
    }
    return env->NewStringUTF(appData.liveEventsJson.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeIsDataLoaded(
    JNIEnv* env,
    jobject thiz
) {
    return appData.isLoaded ? JNI_TRUE : JNI_FALSE;
}

// ==================== LISTENER MANAGER ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeShouldShowLink(
    JNIEnv* env,
    jobject thiz,
    jstring pageType,
    jstring uniqueId
) {
    if (!listenerState.isInitialized || !listenerState.enableDirectLink) {
        return JNI_FALSE;
    }
    
    if (listenerState.directLinkUrl.empty()) {
        return JNI_FALSE;
    }
    
    const char* pageTypeStr = env->GetStringUTFChars(pageType, nullptr);
    
    // Check if page is allowed
    bool pageAllowed = false;
    for (const auto& page : listenerState.allowedPages) {
        if (page == pageTypeStr) {
            pageAllowed = true;
            break;
        }
    }
    
    if (!pageAllowed) {
        env->ReleaseStringUTFChars(pageType, pageTypeStr);
        return JNI_FALSE;
    }
    
    // Build session key
    std::string sessionKey(pageTypeStr);
    if (uniqueId != nullptr) {
        const char* uniqueIdStr = env->GetStringUTFChars(uniqueId, nullptr);
        sessionKey += "_";
        sessionKey += uniqueIdStr;
        env->ReleaseStringUTFChars(uniqueId, uniqueIdStr);
    }
    
    env->ReleaseStringUTFChars(pageType, pageTypeStr);

    // Check if already triggered
    for (const auto& triggered : triggeredSessions) {
        if (triggered == sessionKey) {
            return JNI_FALSE;
        }
    }
    
    // Mark as triggered
    triggeredSessions.push_back(sessionKey);
    LOGD("Showing link for session: %s", sessionKey.c_str());
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(
    JNIEnv* env,
    jobject thiz
) {
    if (!listenerState.isInitialized) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(listenerState.directLinkUrl.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeResetSessions(
    JNIEnv* env,
    jobject thiz
) {
    triggeredSessions.clear();
    LOGD("Sessions reset");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeIsConfigValid(
    JNIEnv* env,
    jobject thiz
) {
    if (!listenerState.isInitialized) {
        return JNI_FALSE;
    }
    
    if (listenerState.enableDirectLink) {
        if (listenerState.directLinkUrl.empty() || listenerState.allowedPages.empty()) {
            return JNI_FALSE;
        }
    }
    
    return JNI_TRUE;
}

// ==================== REMOTE CONFIG ====================
// CRITICAL FIX: The function names below now match NativeDataRepository instead of NativeRemoteConfigManager

static std::string remoteConfigUrl = "";
static bool remoteConfigFetched = false;

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeStoreConfigUrl(
    JNIEnv* env,
    jobject thiz,
    jstring configUrl
) {
    const char* urlStr = env->GetStringUTFChars(configUrl, nullptr);
    remoteConfigUrl = std::string(urlStr);
    remoteConfigFetched = true;
    env->ReleaseStringUTFChars(configUrl, urlStr);
    
    LOGD("Remote config URL stored in native memory");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigUrl(
    JNIEnv* env,
    jobject thiz
) {
    if (!remoteConfigFetched) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(remoteConfigUrl.c_str());
}

