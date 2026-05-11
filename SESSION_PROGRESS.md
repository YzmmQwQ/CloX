# CloX Session Progress Tracker

## Project Identity
- **App:** CloX — smartwatch launcher
- **Package:** `com.ailife.clox`
- **Repo:** `git@gitlab.com:ailife8881/clox.git` (branch: `main`)
- **Working dir:** `/home/ailife/Documents/AndroidStudioProjects/CloX`

---

## Work Completed ✅

- [x] Full Android project scaffold (80+ files) — Kotlin, XML layouts, Lua assets
- [x] `GestureInterceptorLayout` — dual-path touch (Path A: child consumed DOWN, Path B: Axmol surface), 1.5× directional ratio lock
- [x] App list (follow-finger, spring open/close, RecyclerView scroll passthrough)
- [x] All 4 overlays wired (QS, Widgets, Notifications, AppList)
- [x] `BlurEngine` — RenderEffect → RenderScript → StackBlur → scrim
- [x] `SettingsActivity` + 4 preference fragments
- [x] App icon from `bg.png` + `foreground.png` → all mipmap densities
- [x] Settings second launcher entry (`activity-alias`)
- [x] `QUERY_ALL_PACKAGES` permission added
- [x] CLI build env: Java 21, NDK r25c, CMake 3.31.4, Gradle 8.4, PowerShell 7.4
- [x] Axmol v2.11.3 added as git submodule at `axmol/`
- [x] `axslcc` v1.14.0 binary at `tools/axslcc/bin/axslcc`
- [x] `AppDelegate.cpp/.h` written (Lua engine init, loads `require 'main'`)
- [x] `CMakeLists.txt` wired with `cpp_android_spec --whole-archive`, `lua_android_spec`, `axlua`, `ax_setup_app_config`
- [x] `CocosManager.kt` uses reflection to init `AxmolGLSurfaceView` + `AxmolRenderer`
- [x] `watchface.lua` — digital clock, rotating animation, `ccui.Button` touch test
- [x] Git repo pushed to GitLab, 2 commits
- [x] Axmol native build fixed — all axlua extension link errors resolved
- [x] `CMakeLists.txt` simplified — removed 7 conflicting FORCE overrides
- [x] `build.gradle` cmake args cleaned — removed OFF flags for cocostudio/spine/dragonbones/physics
- [x] `CocosManager.kt` simplified — reflection replaced with direct `AxmolGLSurfaceView` import (77→24 lines)
- [x] `axmol_android_app_init` bug fixed — removed wrong `extern "C"` (C++ mangled symbol required)
- [x] `MainActivity` implements `AxmolEngineListener` → `AxmolEngine.init()` works
- [x] `AppDelegate.cpp` fixed — `setDesignResolutionSize`, `cc=ax` alias, `AX_USE_FRAMEWORK`, `enableNodeEvents` stub
- [x] Axmol Lua framework scripts copied to `assets/axmol/` via Gradle task
- [x] Compiled axslc shaders copied to `assets/axslc/` via Gradle task
- [x] **Lua watchface rendering on device: clock + date + rotating square + button** ✓

---

## Current Position 🟢

**Watchface RENDERING on device as of 2026-05-11.**
Clock, date, rotating square, and ccui.Button all visible. No crashes, no Lua errors.

---

## Pending Items 📋

- [ ] Test gesture system end-to-end with Axmol running (swipe overlays)
- [ ] Push working Axmol build to GitLab
- [ ] Phase 2–7 features (QS tiles, widget host, notification listener, settings)

---

## Critical Variables

| Variable | Value |
|---|---|
| NDK version | `25.2.9519653` (r25c) installed at `~/android-sdk/ndk/25.2.9519653` |
| CMake version | `3.31.4` at `~/android-sdk/cmake/3.31.4` |
| Axmol version | `v2.11.3` (pinned) |
| axslcc version | `v1.14.0` |
| PowerShell | `~/.local/bin/pwsh` → `~/.local/pwsh7/pwsh` |
| `AX_LUA_ENGINE` cmake var | `plainlua` |
| `_AX_LUA_LIB` cmake var | `axlua` |
| `BUILD_ENGINE_DONE` | Must be `TRUE` before `add_subdirectory(axmol)` |
| ANDROID_HOME | `~/android-sdk` (in `~/.bashrc`) |

---

## Key File Locations

| File | Purpose |
|---|---|
| `CMakeLists.txt` | Root — Axmol integration, current build problem |
| `app/build.gradle` | Gradle — `cppFlags "-std=c++20"`, ndkVersion, cmake 3.31.4 |
| `app/src/main/cpp/AppDelegate.cpp` | Game entry point, `axmol_android_app_init()` |
| `app/src/main/cpp/AppDelegate.h` | AppDelegate class declaration |
| `app/src/main/kotlin/com/ailife/clox/cocos/CocosManager.kt` | Reflection-based AxmolGLSurfaceView init |
| `app/src/main/assets/lua/watchface.lua` | Lua home screen scene |
| `app/src/main/assets/lua/main.lua` | Lua entry point (`require 'main'`) |
| `app/src/main/assets/lua/bridge.lua` | Android ↔ Lua event bus |
| `tools/axslcc/bin/axslcc` | Shader compiler binary (tracked in git) |
| `axmol/` | Git submodule at v2.11.3 |

---

## Next Immediate Action 🚀

1. Test gesture overlays (swipe down=QS, up=Widgets, right=Notifications, left=AppList)
2. `git add -A && git commit -m "feat: Axmol Lua watchface rendering on device"`
3. `git push` to GitLab
4. Phase 2: real QS tiles, widget host, notification listener

---

## Architecture Constraints (Never Break)
- No Jetpack Compose
- No ViewPager2  
- Home screen = Axmol SurfaceView + Lua ONLY (no Android UI on home)
- `ndkVersion "25.2.9519653"` in `app/build.gradle` (no `ndk.dir`)
- Build command: `./gradlew installDebug` (no export prefix)
- C++ standard: `-std=c++20` (Axmol v2.11 requires C++20)
