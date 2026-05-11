#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "CloX_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// When Axmol is present, the real AppDelegate / LuaEngine take over these
// calls.  When it is absent (stub build), these log-only functions are used.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeInit(
        JNIEnv *, jobject, jobject /*assetManager*/, jint w, jint h) {
    LOGI("nativeInit: %dx%d", w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnResume(JNIEnv *, jobject) {
    LOGI("nativeOnResume");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnPause(JNIEnv *, jobject) {
    LOGI("nativeOnPause");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnDestroy(JNIEnv *, jobject) {
    LOGI("nativeOnDestroy");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ailife_clox_cocos_LuaBridge_callLuaFunction(
        JNIEnv *env, jobject, jstring funcName, jstring args) {
    const char *name = env->GetStringUTFChars(funcName, nullptr);
    const char *argsStr = env->GetStringUTFChars(args, nullptr);
    LOGI("callLuaFunction: %s(%s)", name, argsStr);
    env->ReleaseStringUTFChars(funcName, name);
    env->ReleaseStringUTFChars(args, argsStr);
    return env->NewStringUTF("{}");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_LuaBridge_passTouchNative(
        JNIEnv *, jobject, jfloat x, jfloat y, jint action) {
    LOGI("passTouchNative: action=%d x=%.1f y=%.1f", action, x, y);
}
