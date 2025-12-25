#include <jni.h>
#include <string>

static const char* getObfuscatedUrl() {

    static const unsigned char encrypted[] = {
        0x05, 0x19, 0x19, 0x1d, 0x1e, 0x57, 0x42, 0x42,
        0x0c, 0x04, 0x09, 0x1e, 0x0a, 0x02, 0x40, 0x1d,
        0x01, 0x18, 0x1e, 0x43, 0x1b, 0x08, 0x1f, 0x0e,
        0x08, 0x01, 0x43, 0x0c, 0x1d, 0x1d, 0x42, 0x0c,
        0x1d, 0x04, 0x42, 0x1d, 0x18, 0x0f, 0x01, 0x04,
        0x0e, 0x42
    };

    unsigned char k1 = 0x12;
    unsigned char k2 = 0xA7;
    unsigned char k3 = 0x3C;
    unsigned char k4 = 0xF1;
    unsigned char k5 = 0x08;
    unsigned char k6 = 0x1D;

    unsigned char key = k1 ^ k2 ^ k3 ^ k4 ^ k5 ^ k6;

    static char decrypted[64];

    for (size_t i = 0; i < sizeof(encrypted); i++) {
        decrypted[i] = encrypted[i] ^ key;
    }

    decrypted[sizeof(encrypted)] = '\0';

    return decrypted;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_livetvpro_di_NetworkModule_getBaseUrl(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF(getObfuscatedUrl());
}
