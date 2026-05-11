#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "CloX_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// JNI bridge — called from LuaBridge.kt
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_ailife_clox_cocos_LuaBridge_callLuaFunction(
        JNIEnv *env,
        jobject /* this */,
        jstring funcName,
        jstring args) {

    const char *name = env->GetStringUTFChars(funcName, nullptr);
    const char *argsStr = env->GetStringUTFChars(args, nullptr);

    LOGI("callLuaFunction: %s(%s)", name, argsStr);

    // TODO: route to Axmol Lua runtime via ax::LuaEngine::getInstance()
    std::string result = "{}";

    env->ReleaseStringUTFChars(funcName, name);
    env->ReleaseStringUTFChars(args, argsStr);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_LuaBridge_passTouchNative(
        JNIEnv *env,
        jobject /* this */,
        jfloat x,
        jfloat y,
        jint action) {

    LOGI("passTouchNative: action=%d x=%.1f y=%.1f", action, x, y);
    // TODO: inject into Axmol touch pipeline via ax::EventDispatcher
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeInit(
        JNIEnv *env,
        jobject /* this */,
        jobject assetManager,
        jint width,
        jint height) {

    LOGI("nativeInit: %dx%d", width, height);
    // TODO: ax::Application::getInstance()->run() bootstrap
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnResume(JNIEnv *, jobject) {
    LOGI("nativeOnResume");
    // TODO: ax::Application::getInstance()->applicationWillEnterForeground()
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnPause(JNIEnv *, jobject) {
    LOGI("nativeOnPause");
    // TODO: ax::Application::getInstance()->applicationDidEnterBackground()
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeOnDestroy(JNIEnv *, jobject) {
    LOGI("nativeOnDestroy");
}
