#include <jni.h>
#include <android/log.h>
#include <string>
#include "lua-bindings/manual/LuaEngine.h"
#include "blur_postprocess.h"

extern "C" {
#include "lua.h"
}

#define LOG_TAG "CloX_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

// Calls a Lua global function by name with a single string argument.
// Must be called on the GL thread (Axmol's Lua VM is single-threaded).
extern "C" JNIEXPORT jstring JNICALL
Java_com_ailife_clox_cocos_LuaBridge_callLuaFunction(
        JNIEnv *env, jobject, jstring funcName, jstring args) {
    const char *name   = env->GetStringUTFChars(funcName, nullptr);
    const char *argsStr = env->GetStringUTFChars(args, nullptr);
    LOGI("callLuaFunction: %s(%s)", name, argsStr);

    jstring result = env->NewStringUTF("{}");

    auto *engine = ax::LuaEngine::getInstance();
    if (engine) {
        lua_State *L = engine->getLuaStack()->getLuaState();
        lua_getglobal(L, name);
        if (lua_isfunction(L, -1)) {
            lua_pushstring(L, argsStr);
            if (lua_pcall(L, 1, 1, 0) == 0) {
                const char *ret = lua_tostring(L, -1);
                if (ret) {
                    env->DeleteLocalRef(result);
                    result = env->NewStringUTF(ret);
                }
                lua_pop(L, 1);
            } else {
                LOGE("callLuaFunction %s error: %s", name, lua_tostring(L, -1));
                lua_pop(L, 1);
            }
        } else {
            LOGE("callLuaFunction: '%s' is not a global function", name);
            lua_pop(L, 1);
        }
    } else {
        LOGE("callLuaFunction: LuaEngine not ready");
    }

    env->ReleaseStringUTFChars(funcName, name);
    env->ReleaseStringUTFChars(args, argsStr);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeSetBlurRadius(
        JNIEnv*, jclass, jfloat radius) {
    BlurPostProcess::g_radius.store(radius, std::memory_order_relaxed);
}

// Forward a touch from the Android gesture layer into Lua. Must arrive on the
// GL thread (LuaBridge.passTouch queueEvents it) — the Lua VM is single-
// threaded. x/y are Android view pixels (origin top-left, Y down); the Lua
// side converts them into watch-canvas coordinates.
extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_LuaBridge_passTouchNative(
        JNIEnv *, jobject, jfloat x, jfloat y, jint action) {
    LOGI("passTouchNative: action=%d x=%.1f y=%.1f", action, x, y);
    auto *engine = ax::LuaEngine::getInstance();
    if (!engine) { LOGE("passTouchNative: LuaEngine not ready"); return; }
    lua_State *L = engine->getLuaStack()->getLuaState();
    lua_getglobal(L, "WatchfaceOnTouch");
    if (!lua_isfunction(L, -1)) { lua_pop(L, 1); return; }
    lua_pushnumber(L, x);
    lua_pushnumber(L, y);
    lua_pushinteger(L, action);
    if (lua_pcall(L, 3, 0, 0) != 0) {
        LOGE("WatchfaceOnTouch error: %s", lua_tostring(L, -1));
        lua_pop(L, 1);
    }
}
