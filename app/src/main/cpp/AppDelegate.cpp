#include "AppDelegate.h"
#include <memory>
#include <cstdio>
#include <jni.h>
#include "lua-bindings/manual/LuaEngine.h"
#include "lua-bindings/manual/lua_module_register.h"
#include "audio/AudioEngine.h"
#include "base/Director.h"
#include "base/EventCustom.h"
#include "base/EventDispatcher.h"
#include "base/EventType.h"
#include "base/ZipUtils.h"
#include "platform/FileUtils.h"
#include "platform/RenderView.h"
#include "platform/android/jni/JniHelper.h"
#include "platform/android/FileUtils-android.h"
#include "blur_postprocess.h"
#include "lauxlib.h"

using namespace ax;

// Zip-Slip guard: a .watch is untrusted input, so a crafted entry name must
// never let "destDir + name" escape destDir. Reject absolute paths (unix "/",
// windows drive "C:") and any name with a ".." path component — with destDir a
// fixed trusted prefix, that is sufficient to keep every write inside it. The
// files don't exist yet, so realpath-based containment isn't available here.
static bool isUnsafeZipEntryName(const std::string& name) {
    if (name.empty()) return true;
    if (name[0] == '/' || name[0] == '\\') return true;          // absolute
    if (name.size() >= 2 && name[1] == ':') return true;         // drive letter
    size_t i = 0, n = name.size();
    while (i < n) {
        size_t j = i;
        while (j < n && name[j] != '/' && name[j] != '\\') ++j;
        if (j - i == 2 && name[i] == '.' && name[i + 1] == '.') return true;
        i = j + 1;
    }
    return false;
}

// ── axUnzipToDir(zipPath, destDir) → true | false, errmsg ────────────────────
// Extracts all files from a ZIP archive to destDir (trailing slash required).
// Creates subdirectories as needed. Called from Lua on the GL thread.
static int lua_axUnzipToDir(lua_State* L) {
    const char* zipPath = luaL_checkstring(L, 1);
    const char* destDir = luaL_checkstring(L, 2);

    auto fu  = FileUtils::getInstance();
    auto zip = ZipFile::createFromFile(zipPath);
    if (!zip) {
        lua_pushboolean(L, 0);
        lua_pushstring(L, "cannot open zip");
        return 2;
    }

    fu->createDirectory(destDir);

    std::string name = zip->getFirstFilename();
    while (!name.empty()) {
        if (isUnsafeZipEntryName(name)) {
            ax::print("axUnzipToDir: rejected unsafe entry '%s'", name.c_str());
        } else {
            std::string data;
            ResizableBufferAdapter<std::string> buf(&data);
            if (zip->getFileData(name, &buf) && !data.empty()) {
                std::string out = std::string(destDir) + name;
                auto slash = out.rfind('/');
                if (slash != std::string::npos)
                    fu->createDirectory(out.substr(0, slash + 1));
                if (FILE* f = fopen(out.c_str(), "wb")) {
                    fwrite(data.data(), 1, data.size(), f);
                    fclose(f);
                }
            }
        }
        name = zip->getNextFilename();
    }
    delete zip;

    lua_pushboolean(L, 1);
    return 1;
}

// ── axRemoveDir(dirPath) → true | false ──────────────────────────────────────
static int lua_axRemoveDir(lua_State* L) {
    const char* dir = luaL_checkstring(L, 1);
    bool ok = FileUtils::getInstance()->removeDirectory(dir);
    lua_pushboolean(L, ok ? 1 : 0);
    return 1;
}

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

    // Hook the post-draw event to run the C++ blur pass each frame.
    // Fires after scene rendering, before swapBuffers — see Director.cpp:343/352.
    director->getEventDispatcher()->addCustomEventListener(
        Director::EVENT_AFTER_DRAW,
        [](EventCustom*) { BlurPostProcess::applyIfNeeded(); });

    // Reset BlurPP's GL objects whenever the renderer is recreated. The EGL
    // context can be lost+recreated WITHOUT the app-background lifecycle: an
    // activity recreation that reuses the live GL surface (dark/light or
    // orientation change) recreates the context but never calls
    // applicationDidEnterBackground, so onContextLost() (which zeroes BlurPP's
    // texture/FBO handles) would otherwise be skipped. Its stale names from the
    // dead context then get recycled by Axmol for the rebaked watchface run RTs,
    // and the next blur pass reads/writes/deletes them — baking the flattened
    // layers black ("works after a rebake, until the blur is triggered").
    director->getEventDispatcher()->addCustomEventListener(
        EVENT_RENDERER_RECREATED,
        [](EventCustom*) { BlurPostProcess::onContextLost(); });

    auto engine = LuaEngine::getInstance();
    ScriptEngineManager::getInstance()->setScriptEngine(engine);
    lua_State* L = engine->getLuaStack()->getLuaState();
    lua_module_register(L);
    lua_register(L, "axUnzipToDir", lua_axUnzipToDir);
    lua_register(L, "axRemoveDir",  lua_axRemoveDir);

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
    BlurPostProcess::onContextLost();   // EGL context will be destroyed
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
Java_com_yzmm_clox_cocos_CocosManager_nativeSetContext(
        JNIEnv* /*env*/, jclass, jobject context, jobject assetManager)
{
    ax::JniHelper::setClassLoaderFrom(context);
    ax::FileUtilsAndroid::setAssetManagerFromJava(assetManager);
}
