#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== DATA STORAGE ====================
// This holds your categories and channels in memory
struct AppData {
    std::string categoriesJson;
    std::string channelsJson;
    std::string liveEventsJson;
    bool isLoaded;
} static appData = {"", "", "", false};

// This holds the URL found in Firebase
static std::string remoteConfigUrl = "";
static bool remoteConfigFetched = false;

// ==================== JNI EXPORTS ====================

// 1. INTEGRITY CHECK (Always Pass)
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeValidateIntegrity(
    JNIEnv* env,
    jobject thiz
) {
    return JNI_TRUE;
}

// 2. CONFIG KEY NAME (The Critical Fix)
// This tells the app to look for the key "data_file_url" in Firebase
extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetConfigKey(
    JNIEnv* env,
    jobject thiz
) {
    return env->NewStringUTF("data_file_url");
}

// 3. STORE CONFIG URL (Called after Firebase finds the URL)
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
    LOGD("Remote Config URL stored: %s", remoteConfigUrl.c_str());
}

// 4. GET CONFIG URL
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

// 5. STORE DATA (Parses the JSON from your Vercel link)
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
        
        // Save the raw JSON for all getters
        appData.categoriesJson = json;
        appData.channelsJson = json;
        appData.liveEventsJson = json;
        appData.isLoaded = true;

        env->ReleaseStringUTFChars(jsonData, jsonStr);
        LOGD("Data stored successfully");
        return JNI_TRUE;
    } catch (...) {
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        LOGE("Failed to store data");
        return JNI_FALSE;
    }
}

// 6. GETTERS (Used by the app to display lists)
extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetCategories(JNIEnv* env, jobject) {
    return env->NewStringUTF(appData.isLoaded ? appData.categoriesJson.c_str() : "[]");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetChannels(JNIEnv* env, jobject) {
    return env->NewStringUTF(appData.isLoaded ? appData.channelsJson.c_str() : "[]");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeGetLiveEvents(JNIEnv* env, jobject) {
    return env->NewStringUTF(appData.isLoaded ? appData.liveEventsJson.c_str() : "[]");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_data_repository_NativeDataRepository_nativeIsDataLoaded(JNIEnv* env, jobject) {
    return appData.isLoaded ? JNI_TRUE : JNI_FALSE;
}

// ==================== LISTENER MANAGER STUBS ====================
// Required to prevent crashes if other parts of the app call these

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeShouldShowLink(JNIEnv* env, jobject, jstring, jstring) {
    return JNI_FALSE; 
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeResetSessions(JNIEnv* env, jobject) {}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeIsConfigValid(JNIEnv* env, jobject) {
    return JNI_TRUE;
}

