#include "AppDelegate.h"
#include <memory>
#include <jni.h>
#include "lua-bindings/manual/LuaEngine.h"
#include "lua-bindings/manual/lua_module_register.h"
#include "audio/AudioEngine.h"
#include "base/Director.h"
#include "platform/FileUtils.h"
#include "platform/RenderView.h"
#include "platform/android/jni/JniHelper.h"
#include "platform/android/FileUtils-android.h"

using namespace ax;

AppDelegate::AppDelegate()  {}
AppDelegate::~AppDelegate() {}

void AppDelegate::initGfxContextAttrs()
{
    // r, g, b, a, depth, stencil, msaa
    GfxContextAttrs attrs = {8, 8, 8, 8, 24, 8, 0};
    RenderView::setGfxContextAttrs(attrs);
}

bool AppDelegate::applicationDidFinishLaunching()
{
    auto director = Director::getInstance();
    director->setAnimationInterval(1.0f / 60.0f);

    // Set design resolution = frame size (1:1 pixel mapping for smartwatch)
    auto renderView = director->getRenderView();
    auto frameSize  = renderView->getFrameSize();
    renderView->setDesignResolutionSize(frameSize.width, frameSize.height,
                                        ResolutionPolicy::EXACT_FIT);

    auto engine = LuaEngine::getInstance();
    ScriptEngineManager::getInstance()->setScriptEngine(engine);
    lua_State* L = engine->getLuaStack()->getLuaState();
    lua_module_register(L);

    // "." = assets root; needed so "require 'axmol.init'" finds assets/axmol/init.lua
    engine->getLuaStack()->addSearchPath(".");
    engine->getLuaStack()->addSearchPath("lua");
    FileUtils::getInstance()->addSearchPath("res");

    // Load full Axmol Lua framework (NodeEx wires onEnter/onExit as script handlers)
    engine->executeString("AX_USE_FRAMEWORK = true");
    engine->executeString("require 'axmol.init'");

    // cc.Scene/Node have no Lua ctor(); stub calls enableNodeEvents() so that
    // subclass onEnter()/onExit() methods fire via registerScriptHandler.
    engine->executeString(
        "local function _enableEvents(self) self:enableNodeEvents() end\n"
        "cc.Scene.ctor = _enableEvents\n"
        "cc.Node.ctor  = _enableEvents\n"
        "cc.Layer.ctor = _enableEvents\n"
    );

    // Pre-expose WatchfaceScene as a global; main.lua calls require "watchface"
    // (returns cached value) then reads WatchfaceScene as a global.
    engine->executeString("WatchfaceScene = require 'watchface'");

    if (engine->executeString("require 'main'"))
        return false;

    return true;
}

void AppDelegate::applicationDidEnterBackground()
{
    Director::getInstance()->stopAnimation();
    AudioEngine::pauseAll();
}

void AppDelegate::applicationWillEnterForeground()
{
    Director::getInstance()->startAnimation();
    AudioEngine::resumeAll();
}

void AppDelegate::applicationWillQuit() {}

namespace { std::unique_ptr<AppDelegate> sApp; }

// Called by Axmol's JNI_OnLoad — must NOT be extern "C"; Axmol declares it as
// a C++ weak symbol (_Z22axmol_android_app_initP7_JNIEnv).
void axmol_android_app_init(JNIEnv* /*env*/)
{
    sApp = std::make_unique<AppDelegate>();
}

// JNI shim called from CocosManager.init() to initialise Axmol's JniHelper
// classloader and the Android asset manager — work that AxmolActivity normally
// does via AxmolEngine.init().
extern "C" JNIEXPORT void JNICALL
Java_com_ailife_clox_cocos_CocosManager_nativeSetContext(
        JNIEnv* /*env*/, jclass, jobject context, jobject assetManager)
{
    ax::JniHelper::setClassLoaderFrom(context);
    ax::FileUtilsAndroid::setAssetManagerFromJava(assetManager);
}
