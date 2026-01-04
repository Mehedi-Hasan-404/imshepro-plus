#include <jni.h>
#include <string>

// ==================== OBFUSCATION HELPERS ====================

// XOR encryption key components
static const unsigned char KEY_PART_1 = 0x12;
static const unsigned char KEY_PART_2 = 0xA7;
static const unsigned char KEY_PART_3 = 0x3C;
static const unsigned char KEY_PART_4 = 0xF1;
static const unsigned char KEY_PART_5 = 0x08;
static const unsigned char KEY_PART_6 = 0x1D;

// Calculate final key at runtime
static unsigned char getKey() {
    return KEY_PART_1 ^ KEY_PART_2 ^ KEY_PART_3 ^ KEY_PART_4 ^ KEY_PART_5 ^ KEY_PART_6;
}

// Decrypt string
static std::string decrypt(const unsigned char* encrypted, size_t length) {
    unsigned char key = getKey();
    std::string result;
    result.reserve(length);
    
    for (size_t i = 0; i < length; i++) {
        result += static_cast<char>(encrypted[i] ^ key);
    }
    
    return result;
}

// ==================== ENCRYPTED BASE URL ====================

static const unsigned char ENCRYPTED_BASE_URL[] = {
    0x05, 0x19, 0x19, 0x1d, 0x1e, 0x57, 0x42, 0x42,
    0x0c, 0x04, 0x09, 0x1e, 0x0a, 0x02, 0x40, 0x1d,
    0x01, 0x18, 0x1e, 0x43, 0x1b, 0x08, 0x1f, 0x0e,
    0x08, 0x01, 0x43, 0x0c, 0x1d, 0x1d, 0x42, 0x0c,
    0x1d, 0x04, 0x42, 0x1d, 0x18, 0x0f, 0x01, 0x04,
    0x0e, 0x42
};
static const size_t BASE_URL_LENGTH = sizeof(ENCRYPTED_BASE_URL);

// ==================== MAIN EXPORT (REQUIRED) ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_di_NetworkModule_getBaseUrl(JNIEnv* env, jobject thiz) {
    // Decrypt and return base URL
    std::string url = decrypt(ENCRYPTED_BASE_URL, BASE_URL_LENGTH);
    return env->NewStringUTF(url.c_str());
}
#include <jni.h>
#include <string>
#include <vector>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "NativeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== OBFUSCATION HELPERS ====================

// XOR encryption key components (split to avoid detection)
static const unsigned char KEY_PART_1 = 0x12;
static const unsigned char KEY_PART_2 = 0xA7;
static const unsigned char KEY_PART_3 = 0x3C;
static const unsigned char KEY_PART_4 = 0xF1;
static const unsigned char KEY_PART_5 = 0x08;
static const unsigned char KEY_PART_6 = 0x1D;

// Calculate final key at runtime
static unsigned char getKey() {
    return KEY_PART_1 ^ KEY_PART_2 ^ KEY_PART_3 ^ KEY_PART_4 ^ KEY_PART_5 ^ KEY_PART_6;
}

// Decrypt string
static std::string decrypt(const unsigned char* encrypted, size_t length) {
    unsigned char key = getKey();
    std::string result;
    result.reserve(length);
    
    for (size_t i = 0; i < length; i++) {
        result += static_cast<char>(encrypted[i] ^ key);
    }
    
    return result;
}

// ==================== ENCRYPTED BASE URL ====================

static const unsigned char ENCRYPTED_BASE_URL[] = {
    0x05, 0x19, 0x19, 0x1d, 0x1e, 0x57, 0x42, 0x42,
    0x0c, 0x04, 0x09, 0x1e, 0x0a, 0x02, 0x40, 0x1d,
    0x01, 0x18, 0x1e, 0x43, 0x1b, 0x08, 0x1f, 0x0e,
    0x08, 0x01, 0x43, 0x0c, 0x1d, 0x1d, 0x42, 0x0c,
    0x1d, 0x04, 0x42, 0x1d, 0x18, 0x0f, 0x01, 0x04,
    0x0e, 0x42
};
static const size_t BASE_URL_LENGTH = sizeof(ENCRYPTED_BASE_URL);

// ==================== ENCRYPTED REMOTE CONFIG KEY ====================

// "data_file_url" encrypted
static const unsigned char ENCRYPTED_CONFIG_KEY[] = {
    0x16, 0x06, 0x19, 0x06, 0x5f, 0x0c, 0x08, 0x0f,
    0x0a, 0x5f, 0x1a, 0x1c, 0x0f
};
static const size_t CONFIG_KEY_LENGTH = sizeof(ENCRYPTED_CONFIG_KEY);

// ==================== INTEGRITY VALIDATION ====================

// Store validation token (changes on each successful check)
static unsigned int integrity_token = 0;
static time_t last_check_time = 0;

// Anti-tampering: Validate calling context
static bool validateCaller(JNIEnv* env, jobject caller) {
    // Get the class name of the caller
    jclass callerClass = env->GetObjectClass(caller);
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getNameMethod = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    
    jstring className = (jstring)env->CallObjectMethod(callerClass, getNameMethod);
    const char* classNameStr = env->GetStringUTFChars(className, nullptr);
    
    // Verify caller is from legitimate package
    bool isValid = (strstr(classNameStr, "com.livetvpro") != nullptr);
    
    env->ReleaseStringUTFChars(className, classNameStr);
    env->DeleteLocalRef(className);
    env->DeleteLocalRef(callerClass);
    env->DeleteLocalRef(classClass);
    
    return isValid;
}

// Generate integrity token based on current time
static unsigned int generateToken() {
    time_t now = time(nullptr);
    return (unsigned int)(now ^ 0xDEADBEEF);
}

// Validate integrity token
static bool validateToken(unsigned int token) {
    time_t now = time(nullptr);
    
    // Token valid for 60 seconds
    if ((now - last_check_time) > 60) {
        return false;
    }
    
    return token == integrity_token;
}

// ==================== ENCRYPTED DATA RESPONSE STRUCTURE ====================

// Structure to hold encrypted data response fields
struct EncryptedDataResponse {
    const unsigned char* data;
    size_t length;
};

// "listener_config" field structure encrypted
static const unsigned char ENCRYPTED_LISTENER_CONFIG[] = {
    0x0f, 0x08, 0x1e, 0x19, 0x0a, 0x17, 0x0a, 0x1c,
    0x5f, 0x0c, 0x1d, 0x17, 0x0c, 0x08, 0x0e
};

// "enable_direct_link" encrypted
static const unsigned char ENCRYPTED_ENABLE_LINK[] = {
    0x0a, 0x17, 0x06, 0x07, 0x0f, 0x0a, 0x5f, 0x16,
    0x08, 0x1c, 0x0a, 0x0c, 0x19, 0x5f, 0x0f, 0x08,
    0x17, 0x0e
};

// "direct_link_url" encrypted
static const unsigned char ENCRYPTED_LINK_URL[] = {
    0x16, 0x08, 0x1c, 0x0a, 0x0c, 0x19, 0x5f, 0x0f,
    0x08, 0x17, 0x0e, 0x5f, 0x1a, 0x1c, 0x0f
};

// "allowed_pages" encrypted
static const unsigned char ENCRYPTED_ALLOWED_PAGES[] = {
    0x06, 0x0f, 0x0f, 0x1d, 0x1b, 0x0a, 0x16, 0x5f,
    0x1e, 0x06, 0x0e, 0x0a, 0x1e
};

// ==================== JNI EXPORTS ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_di_NetworkModule_getBaseUrl(JNIEnv* env, jobject thiz) {
    // Validate caller
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized caller detected!");
        return env->NewStringUTF("");
    }
    
    // Decrypt and return base URL
    std::string url = decrypt(ENCRYPTED_BASE_URL, BASE_URL_LENGTH);
    return env->NewStringUTF(url.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_RemoteConfigManager_getNativeConfigKey(JNIEnv* env, jobject thiz) {
    // Validate caller
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized caller detected!");
        return env->NewStringUTF("");
    }
    
    // Decrypt and return config key
    std::string key = decrypt(ENCRYPTED_CONFIG_KEY, CONFIG_KEY_LENGTH);
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
    // Validate caller
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized integrity check caller!");
        return 0;
    }
    
    const char* pkgName = env->GetStringUTFChars(packageName, nullptr);
    
    // Verify package name
    bool validPackage = (strcmp(pkgName, "com.livetvpro") == 0 || 
                         strcmp(pkgName, "com.livetvpro.debug") == 0);
    
    env->ReleaseStringUTFChars(packageName, pkgName);
    
    if (!validPackage) {
        LOGE("🚨 SECURITY: Invalid package name!");
        return 0;
    }
    
    // Generate new token
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
    // Validate caller
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized token verification!");
        return JNI_FALSE;
    }
    
    return validateToken((unsigned int)token) ? JNI_TRUE : JNI_FALSE;
}

// ==================== DATA RESPONSE FIELD EXTRACTION ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_DataRepository_nativeGetListenerConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    // Validate caller
    if (!validateCaller(env, thiz)) {
        LOGE("🚨 SECURITY: Unauthorized data access!");
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

// ==================== ANTI-DEBUGGING ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_security_SecurityManager_nativeCheckDebugger(
    JNIEnv* env,
    jobject thiz
) {
    if (!validateCaller(env, thiz)) {
        return JNI_TRUE; // Pretend debugger is attached
    }
    
    // Check for common debugger detection
    // This is a simplified check - can be expanded
    
    // Check if TracerPid exists in /proc/self/status
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

// ==================== STRING ENCRYPTION UTILITY ====================

// Helper function to encrypt strings (use this to generate encrypted arrays)
// This is for development only - remove in production
extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeHelper_encryptString(
    JNIEnv* env,
    jobject thiz,
    jstring input
) {
    const char* inputStr = env->GetStringUTFChars(input, nullptr);
    size_t length = strlen(inputStr);
    unsigned char key = getKey();
    
    std::string result = "static const unsigned char ENCRYPTED_DATA[] = {\n    ";
    
    for (size_t i = 0; i < length; i++) {
        char hex[8];
        sprintf(hex, "0x%02X", (unsigned char)(inputStr[i] ^ key));
        result += hex;
        
        if (i < length - 1) {
            result += ", ";
            if ((i + 1) % 8 == 0) {
                result += "\n    ";
            }
        }
    }
    
    result += "\n};";
    
    env->ReleaseStringUTFChars(input, inputStr);
    return env->NewStringUTF(result.c_str());
}
