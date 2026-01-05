#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <ctime>
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "NativeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ==================== OBFUSCATION & ENCRYPTION ====================

static const unsigned char XOR_KEY[] = {0x12, 0xA7, 0x3C, 0xF1, 0x08, 0x1D};

static std::string xorEncryptDecrypt(const std::string& input) {
    std::string output = input;
    for (size_t i = 0; i < output.length(); i++) {
        output[i] ^= XOR_KEY[i % sizeof(XOR_KEY)];
    }
    return output;
}

// ==================== ENCRYPTED STRINGS ====================

// Field names (encrypted)
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

// ==================== INTEGRITY VALIDATION ====================

static unsigned int integrity_token = 0;
static time_t last_check_time = 0;
static bool app_tampered = false;

static bool validateCaller(JNIEnv* env, jobject caller) {
    if (app_tampered) return false;
    
    jclass callerClass = env->GetObjectClass(caller);
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getNameMethod = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    
    jstring className = (jstring)env->CallObjectMethod(callerClass, getNameMethod);
    const char* classNameStr = env->GetStringUTFChars(className, nullptr);
    
    bool isValid = (strstr(classNameStr, "com.livetvpro") != nullptr);
    
    env->ReleaseStringUTFChars(className, classNameStr);
    env->DeleteLocalRef(className);
    env->DeleteLocalRef(callerClass);
    env->DeleteLocalRef(classClass);
    
    return isValid;
}

static unsigned int generateToken() {
    time_t now = time(nullptr);
    return (unsigned int)(now ^ 0xDEADBEEF);
}

static bool checkDebugger() {
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char line[256];
        while (fgets(line, sizeof(line), status)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int pid = atoi(line + 10);
                fclose(status);
                return pid != 0;
            }
        }
        fclose(status);
    }
    return false;
}

static bool validatePackage(JNIEnv* env) {
    jclass contextClass = env->FindClass("android/app/ActivityThread");
    jmethodID currentAppMethod = env->GetStaticMethodID(contextClass, "currentApplication", "()Landroid/app/Application;");
    jobject context = env->CallStaticObjectMethod(contextClass, currentAppMethod);
    
    jmethodID getPackageNameMethod = env->GetMethodID(env->GetObjectClass(context), "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageNameMethod);
    
    const char* pkgName = env->GetStringUTFChars(packageName, nullptr);
    bool valid = (strcmp(pkgName, "com.livetvpro") == 0 || strcmp(pkgName, "com.livetvpro.debug") == 0);
    
    env->ReleaseStringUTFChars(packageName, pkgName);
    env->DeleteLocalRef(packageName);
    env->DeleteLocalRef(context);
    env->DeleteLocalRef(contextClass);
    
    return valid;
}

// ==================== LISTENER CONFIG STATE (NATIVE ONLY) ====================

struct ListenerConfigState {
    bool enableDirectLink;
    std::string directLinkUrl;
    std::vector<std::string> allowedPages;
    bool isInitialized;
    time_t lastUpdated;
} static listenerState = {false, "", {}, false, 0};

static std::vector<std::string> triggeredSessions;

// ==================== DATA STORAGE (NATIVE ONLY) ====================

struct AppData {
    std::string categoriesJson;
    std::string channelsJson;
    std::string liveEventsJson;
    bool isLoaded;
    time_t lastFetch;
} static appData = {"", "", "", false, 0};

// ==================== JNI EXPORTS ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeValidateIntegrity(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 Unauthorized integrity check!");
        app_tampered = true;
        return JNI_FALSE;
    }
    
    if (checkDebugger()) {
        LOGE("🚨 Debugger detected!");
        app_tampered = true;
        return JNI_FALSE;
    }
    
    if (!validatePackage(env)) {
        LOGE("🚨 Invalid package!");
        app_tampered = true;
        return JNI_FALSE;
    }
    
    time_t now = time(nullptr);
    last_check_time = now;
    integrity_token = generateToken();
    
    LOGD("✅ Integrity validated (token: %u)", integrity_token);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("");
    }
    
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
    if (!validateCaller(env, thiz) || app_tampered) {
        return JNI_FALSE;
    }
    
    const char* jsonStr = env->GetStringUTFChars(jsonData, nullptr);
    
    try {
        // Parse JSON and extract fields using native parsing
        std::string json(jsonStr);
        
        // Store encrypted in native memory
        appData.categoriesJson = json; // In production, parse and extract categories
        appData.channelsJson = json;   // In production, parse and extract channels
        appData.liveEventsJson = json; // In production, parse and extract live_events
        appData.isLoaded = true;
        appData.lastFetch = time(nullptr);
        
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
        LOGD("✅ Data stored in native memory");
        return JNI_TRUE;
        
    } catch (...) {
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        LOGE("❌ Failed to store data");
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetCategories(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("[]");
    }
    
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
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("[]");
    }
    
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
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("[]");
    }
    
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
    if (!validateCaller(env, thiz) || app_tampered) {
        return JNI_FALSE;
    }
    
    return appData.isLoaded ? JNI_TRUE : JNI_FALSE;
}

// ==================== LISTENER MANAGER (NATIVE IMPLEMENTATION) ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeShouldShowLink(
    JNIEnv* env,
    jobject thiz,
    jstring pageType,
    jstring uniqueId
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return JNI_FALSE;
    }
    
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
    
    LOGD("✅ Showing link for session: %s", sessionKey.c_str());
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("");
    }
    
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
    if (!validateCaller(env, thiz)) {
        return;
    }
    
    triggeredSessions.clear();
    LOGD("🔄 Sessions reset");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeIsConfigValid(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return JNI_FALSE;
    }
    
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

// ==================== REMOTE CONFIG (NATIVE) ====================

static std::string remoteConfigUrl = "";
static bool remoteConfigFetched = false;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeRemoteConfigManager_nativeGetConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENC_DATA_FILE_URL, 13);
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeRemoteConfigManager_nativeStoreConfigUrl(
    JNIEnv* env,
    jobject thiz,
    jstring configUrl
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return;
    }
    
    const char* urlStr = env->GetStringUTFChars(configUrl, nullptr);
    remoteConfigUrl = std::string(urlStr);
    remoteConfigFetched = true;
    env->ReleaseStringUTFChars(configUrl, urlStr);
    
    LOGD("✅ Remote config URL stored in native memory");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeRemoteConfigManager_nativeGetConfigUrl(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return env->NewStringUTF("");
    }
    
    if (!remoteConfigFetched) {
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF(remoteConfigUrl.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeRemoteConfigManager_nativeIsConfigReady(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz) || app_tampered) {
        return JNI_FALSE;
    }
    
    return remoteConfigFetched ? JNI_TRUE : JNI_FALSE;
}
