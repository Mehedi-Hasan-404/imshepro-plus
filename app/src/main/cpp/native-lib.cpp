#include <jni.h>
#include <string>
#include <vector>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "NativeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== OBFUSCATION HELPERS ====================

static const unsigned char KEY_PART_1 = 0x12;
static const unsigned char KEY_PART_2 = 0xA7;
static const unsigned char KEY_PART_3 = 0x3C;
static const unsigned char KEY_PART_4 = 0xF1;
static const unsigned char KEY_PART_5 = 0x08;
static const unsigned char KEY_PART_6 = 0x1D;

static unsigned char getKey() {
    return KEY_PART_1 ^ KEY_PART_2 ^ KEY_PART_3 ^ KEY_PART_4 ^ KEY_PART_5 ^ KEY_PART_6;
}

static std::string decrypt(const unsigned char* encrypted, size_t length) {
    unsigned char key = getKey();
    std::string result;
    result.reserve(length);
    
    for (size_t i = 0; i < length; i++) {
        result += static_cast<char>(encrypted[i] ^ key);
    }
    
    return result;
}

// ==================== ENCRYPTED FIELD NAMES ====================

// "data_file_url"
static const unsigned char ENCRYPTED_CONFIG_KEY[] = {
    0x16, 0x06, 0x19, 0x06, 0x5f, 0x0c, 0x08, 0x0f,
    0x0a, 0x5f, 0x1a, 0x1c, 0x0f
};

// "listener_config"
static const unsigned char ENCRYPTED_LISTENER_CONFIG[] = {
    0x0f, 0x08, 0x1e, 0x19, 0x0a, 0x17, 0x0a, 0x1c,
    0x5f, 0x0c, 0x1d, 0x17, 0x0c, 0x08, 0x0e
};

// "enable_direct_link"
static const unsigned char ENCRYPTED_ENABLE_LINK[] = {
    0x0a, 0x17, 0x06, 0x07, 0x0f, 0x0a, 0x5f, 0x16,
    0x08, 0x1c, 0x0a, 0x0c, 0x19, 0x5f, 0x0f, 0x08,
    0x17, 0x0e
};

// "direct_link_url"
static const unsigned char ENCRYPTED_LINK_URL[] = {
    0x16, 0x08, 0x1c, 0x0a, 0x0c, 0x19, 0x5f, 0x0f,
    0x08, 0x17, 0x0e, 0x5f, 0x1a, 0x1c, 0x0f
};

// "allowed_pages"
static const unsigned char ENCRYPTED_ALLOWED_PAGES[] = {
    0x06, 0x0f, 0x0f, 0x1d, 0x1b, 0x0a, 0x16, 0x5f,
    0x1e, 0x06, 0x0e, 0x0a, 0x1e
};

// "categories" (for DataResponse parsing)
static const unsigned char ENCRYPTED_CATEGORIES[] = {
    0x0c, 0x06, 0x19, 0x0a, 0x0e, 0x1d, 0x1c, 0x08,
    0x0a, 0x1e
};

// "channels"
static const unsigned char ENCRYPTED_CHANNELS[] = {
    0x0c, 0x05, 0x06, 0x17, 0x17, 0x0a, 0x0f, 0x1e
};

// "live_events"
static const unsigned char ENCRYPTED_LIVE_EVENTS[] = {
    0x0f, 0x08, 0x1b, 0x0a, 0x5f, 0x0a, 0x1b, 0x0a,
    0x17, 0x19, 0x1e
};

// "data" (for wrapped responses)
static const unsigned char ENCRYPTED_DATA[] = {
    0x16, 0x06, 0x19, 0x06
};

// ==================== INTEGRITY VALIDATION ====================

static unsigned int integrity_token = 0;
static time_t last_check_time = 0;

static bool validateCaller(JNIEnv* env, jobject caller) {
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

static bool validateToken(unsigned int token) {
    time_t now = time(nullptr);
    if ((now - last_check_time) > 60) {
        return false;
    }
    return token == integrity_token;
}

// ==================== LISTENER CONFIG STATE ====================

// Store listener config state in native memory (completely hidden from Java)
struct ListenerConfigState {
    bool enableDirectLink;
    std::string directLinkUrl;
    std::vector<std::string> allowedPages;
    bool isInitialized;
    time_t lastUpdated;
} static listenerState = {false, "", {}, false, 0};

// Store session tracking in native memory
static std::vector<std::string> triggeredSessions;

// ==================== JNI EXPORTS ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_RemoteConfigManager_getNativeConfigKey(JNIEnv* env, jobject thiz) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized caller!");
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_CONFIG_KEY, sizeof(ENCRYPTED_CONFIG_KEY));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_livetvpro_security_SecurityManager_nativeValidateIntegrity(
    JNIEnv* env, 
    jobject thiz,
    jstring packageName,
    jboolean isDebug
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized integrity check!");
        return 0;
    }
    
    const char* pkgName = env->GetStringUTFChars(packageName, nullptr);
    bool validPackage = (strcmp(pkgName, "com.livetvpro") == 0 || 
                         strcmp(pkgName, "com.livetvpro.debug") == 0);
    env->ReleaseStringUTFChars(packageName, pkgName);
    
    if (!validPackage) {
        LOGE("🚨 SECURITY: Invalid package!");
        return 0;
    }
    
    time_t now = time(nullptr);
    last_check_time = now;
    integrity_token = generateToken();
    
    return (jint)integrity_token;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_security_SecurityManager_nativeVerifyToken(
    JNIEnv* env,
    jobject thiz,
    jint token
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized token verification!");
        return JNI_FALSE;
    }
    
    return validateToken((unsigned int)token) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_security_SecurityManager_nativeCheckDebugger(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return JNI_TRUE;
    }
    
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char line[256];
        bool debuggerFound = false;
        
        while (fgets(line, sizeof(line), status)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int pid = atoi(line + 10);
                if (pid != 0) {
                    debuggerFound = true;
                    break;
                }
            }
        }
        
        fclose(status);
        return debuggerFound ? JNI_TRUE : JNI_FALSE;
    }
    
    return JNI_FALSE;
}

// ==================== DATA RESPONSE FIELD NAMES ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetCategoriesKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_CATEGORIES, sizeof(ENCRYPTED_CATEGORIES));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetChannelsKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_CHANNELS, sizeof(ENCRYPTED_CHANNELS));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetLiveEventsKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_LIVE_EVENTS, sizeof(ENCRYPTED_LIVE_EVENTS));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetDataKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_DATA, sizeof(ENCRYPTED_DATA));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetListenerConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_LISTENER_CONFIG, sizeof(ENCRYPTED_LISTENER_CONFIG));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetEnableLinkKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_ENABLE_LINK, sizeof(ENCRYPTED_ENABLE_LINK));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetLinkUrlKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_LINK_URL, sizeof(ENCRYPTED_LINK_URL));
    return env->NewStringUTF(key.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetAllowedPagesKey(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return env->NewStringUTF("");
    }
    
    std::string key = decrypt(ENCRYPTED_ALLOWED_PAGES, sizeof(ENCRYPTED_ALLOWED_PAGES));
    return env->NewStringUTF(key.c_str());
}

// ==================== LISTENER CONFIG INITIALIZATION ====================

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeInitListenerConfig(
    JNIEnv* env,
    jobject thiz,
    jboolean enableDirectLink,
    jstring directLinkUrl,
    jobjectArray allowedPages
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized listener config init!");
        return;
    }
    
    // Store in native memory
    listenerState.enableDirectLink = enableDirectLink;
    
    // Store URL
    if (directLinkUrl != nullptr) {
        const char* urlStr = env->GetStringUTFChars(directLinkUrl, nullptr);
        listenerState.directLinkUrl = std::string(urlStr);
        env->ReleaseStringUTFChars(directLinkUrl, urlStr);
    } else {
        listenerState.directLinkUrl = "";
    }
    
    // Store allowed pages
    listenerState.allowedPages.clear();
    if (allowedPages != nullptr) {
        jsize length = env->GetArrayLength(allowedPages);
        for (jsize i = 0; i < length; i++) {
            jstring page = (jstring)env->GetObjectArrayElement(allowedPages, i);
            const char* pageStr = env->GetStringUTFChars(page, nullptr);
            listenerState.allowedPages.push_back(std::string(pageStr));
            env->ReleaseStringUTFChars(page, pageStr);
            env->DeleteLocalRef(page);
        }
    }
    
    listenerState.isInitialized = true;
    listenerState.lastUpdated = time(nullptr);
}

// ==================== LISTENER MANAGER LOGIC (NATIVE) ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_ListenerManager_nativeShouldShowLink(
    JNIEnv* env,
    jobject thiz,
    jstring pageType,
    jstring uniqueId
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized listener check!");
        return JNI_FALSE;
    }
    
    // Check if initialized
    if (!listenerState.isInitialized) {
        return JNI_FALSE;
    }
    
    // Check if enabled
    if (!listenerState.enableDirectLink) {
        return JNI_FALSE;
    }
    
    // Check if URL is valid
    if (listenerState.directLinkUrl.empty()) {
        return JNI_FALSE;
    }
    
    // Check if page is allowed
    const char* pageTypeStr = env->GetStringUTFChars(pageType, nullptr);
    bool pageAllowed = false;
    for (const auto& page : listenerState.allowedPages) {
        if (page == pageTypeStr) {
            pageAllowed = true;
            break;
        }
    }
    env->ReleaseStringUTFChars(pageType, pageTypeStr);
    
    if (!pageAllowed) {
        return JNI_FALSE;
    }
    
    // Build session key
    std::string sessionKey;
    if (uniqueId != nullptr) {
        const char* uniqueIdStr = env->GetStringUTFChars(uniqueId, nullptr);
        sessionKey = std::string(pageTypeStr) + "_" + std::string(uniqueIdStr);
        env->ReleaseStringUTFChars(uniqueId, uniqueIdStr);
    } else {
        sessionKey = std::string(pageTypeStr);
    }
    
    // Check if already triggered
    for (const auto& triggered : triggeredSessions) {
        if (triggered == sessionKey) {
            return JNI_FALSE; // Already shown
        }
    }
    
    // Mark as triggered
    triggeredSessions.push_back(sessionKey);
    
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_ListenerManager_nativeGetDirectLinkUrl(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized URL access!");
        return env->NewStringUTF("");
    }
    
    if (!listenerState.isInitialized) {
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF(listenerState.directLinkUrl.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_utils_ListenerManager_nativeResetSessions(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized reset!");
        return;
    }
    
    triggeredSessions.clear();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_ListenerManager_nativeIsConfigValid(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return JNI_FALSE;
    }
    
    if (!listenerState.isInitialized) {
        return JNI_FALSE;
    }
    
    // Basic validation
    if (listenerState.enableDirectLink) {
        if (listenerState.directLinkUrl.empty()) {
            return JNI_FALSE;
        }
        if (listenerState.allowedPages.empty()) {
            return JNI_FALSE;
        }
    }
    
    return JNI_TRUE;
}
