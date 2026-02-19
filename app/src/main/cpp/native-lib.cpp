#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <ctime>

struct ListenerConfigState {
    bool enableDirectLink;
    std::string directLinkUrl;
    std::set<std::string> allowedPages;
    bool isInitialized;
    std::string contactUrl;
    std::string cricLiveUrl;
    std::string footLiveUrl;
    std::string emailUs;
    std::string webUrl;
    std::string message;
    std::string messageUrl;
    std::string appVersion;
    std::string downloadUrl;
} static listenerState = {false, "", {}, false, "", "", "", "", "", "", "", "", ""};

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
            return "[]";
        }
        
        size_t startPos = json.find('[', keyPos);
        if (startPos == std::string::npos) {
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
        return result;
        
    } catch (...) {
        return "[]";
    }
}

static void extractListenerConfig(const std::string& json) {
    try {
        size_t configPos = json.find("\"listener_config\"");
        if (configPos == std::string::npos) {
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
            } else {
                listenerState.enableDirectLink = false;
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
                        
                        pos = quoteEnd + 1;
                    }
                }
            }
        }
        
        listenerState.isInitialized = true;
        
        auto extractStringField = [&](const std::string& fieldName, std::string& out) {
            size_t pos = json.find("\"" + fieldName + "\"", configPos);
            if (pos == std::string::npos) return;
            size_t colon = json.find(':', pos);
            if (colon == std::string::npos) return;
            size_t quoteStart = json.find('"', colon);
            if (quoteStart == std::string::npos) return;
            quoteStart++;
            size_t quoteEnd = json.find('"', quoteStart);
            if (quoteEnd == std::string::npos) return;
            out = json.substr(quoteStart, quoteEnd - quoteStart);
        };

        extractStringField("contact_url", listenerState.contactUrl);
        extractStringField("cric_live_url", listenerState.cricLiveUrl);
        extractStringField("foot_live_url", listenerState.footLiveUrl);
        extractStringField("email_us", listenerState.emailUs);
        extractStringField("web_url", listenerState.webUrl);
        extractStringField("message", listenerState.message);
        extractStringField("message_url", listenerState.messageUrl);
        extractStringField("app_version", listenerState.appVersion);
        extractStringField("download_url", listenerState.downloadUrl);

    } catch (...) {
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
        return JNI_FALSE;
    }
    
    try {
        std::string json(jsonStr);
        
        if (json.find("\"data\"") != std::string::npos && json.find("\"success\"") != std::string::npos) {
            std::string dataJson = extractDataObject(json);
            appData.fullJson = dataJson;
        } else {
            appData.fullJson = json;
        }
        
        appData.isLoaded = true;
        extractListenerConfig(appData.fullJson);
        
        env->ReleaseStringUTFChars(jsonData, jsonStr);
        
        return JNI_TRUE;
        
    } catch (...) {
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
    
    std::string sportsJson = extractJsonArray(appData.fullJson, "sports_slug");
    if (sportsJson == "[]") {
        sportsJson = extractJsonArray(appData.fullJson, "sports");
    }
    
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
        return JNI_FALSE;
    }
    
    const char* pageTypeStr = env->GetStringUTFChars(pageType, nullptr);
    if (pageTypeStr == nullptr) {
        return JNI_FALSE;
    }
    std::string pageTypeString(pageTypeStr);
    env->ReleaseStringUTFChars(pageType, pageTypeStr);
    
    if (listenerState.allowedPages.find(pageTypeString) == listenerState.allowedPages.end()) {
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
        return JNI_FALSE;
    }
    
    triggeredSessions.insert(sessionKey);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDirectLinkUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.directLinkUrl.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeResetSessions(JNIEnv* env, jobject) {
    triggeredSessions.clear();
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetContactUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.contactUrl.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetCricLiveUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.cricLiveUrl.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetFootLiveUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.footLiveUrl.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetEmailUs(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.emailUs.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetWebUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.webUrl.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetMessage(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetMessageUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.messageUrl.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetAppVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.appVersion.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livetvpro_utils_NativeListenerManager_nativeGetDownloadUrl(JNIEnv* env, jobject) {
    return env->NewStringUTF(listenerState.downloadUrl.c_str());
}
