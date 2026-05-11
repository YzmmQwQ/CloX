# CloX

A smartwatch launcher for Android — custom gesture navigation, Axmol Engine (Lua) watchface, and Material Design 3 overlays.

## Architecture

```
MainActivity (singleTask)
├── HomeFragment          ← Axmol SurfaceView — Lua watchface (clock + animation)
├── AppListFragment       ← Slides in from right edge on swipe-left
└── OverlayController     ← Manages four full-screen overlay layers

Overlays (above HomeFragment, GONE by default):
├── QuickSettingsOverlay  ← Swipe-down  (tiles + brightness/volume sliders)
├── WidgetsOverlay        ← Swipe-up    (AppWidgetHost cards)
└── NotificationOverlay   ← Swipe-right (MediaSession card + notification list)
```

**Gesture system** — `GestureInterceptorLayout` (root `FrameLayout` subclass):
- Requires 1.5× directional ratio before committing direction (prevents diagonal misfires)
- App list follows finger in real time via `translationX`, snaps with `SpringAnimation` on release
- Vertical scroll passes through to RecyclerView when app list is open
- Any swipe dismisses an active overlay before other routing runs

**Axmol / Lua watchface** — `assets/lua/watchface.lua`:
- Digital clock updated every second via Axmol scheduler
- Rotating animation + orbiting dots (GL-rendered, 60 fps)
- Touch Test button (`ccui.Button`) confirms touch pipeline
- `bridge.lua` event bus connects Android data (battery, steps) to Lua scenes

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin (Android), Lua (Axmol watchface) |
| Engine | [Axmol Engine](https://github.com/axmolengine/axmol) (Cocos2d-x fork) |
| UI | XML layouts + Fragments — Jetpack Compose **not used** |
| Design | Material Design 3 |
| Min SDK | 26 (Android 8.0) / Target 36 |
| ABI | `arm64-v8a` |
| Build | Gradle 8.4 + CMake 3.22.1 + NDK r25c |

## Project structure

```
app/src/main/
├── kotlin/com/ailife/clox/
│   ├── MainActivity.kt
│   ├── gesture/          GestureInterceptorLayout, SwipeDirection
│   ├── cocos/            CocosManager, LuaBridge, SnapshotHelper
│   ├── home/             HomeFragment (Axmol SurfaceView wrapper)
│   ├── applist/          AppListFragment, ViewModel, Adapter, Backends
│   ├── overlay/          OverlayController, BlurEngine, QS, Widgets, Notifications
│   ├── icon/             IconResolver, IconPackLoader
│   └── settings/         SettingsActivity + 4 PreferenceFragmentCompat screens
├── assets/lua/
│   ├── main.lua          Axmol entry point
│   ├── watchface.lua     Clock scene + Touch Test button
│   └── bridge.lua        Android ↔ Lua event bus
└── cpp/
    └── launcher_native.cpp   JNI stubs (CocosManager, LuaBridge)
```

## CLI build setup

> Android Studio is **not required**. All build and deploy steps work from the terminal.

### Prerequisites

- Java 17+ (`java -version`)
- Android SDK with cmdline-tools (run `bash setup-cli.sh` to install everything automatically)

### First-time setup

```bash
# Install Android SDK, NDK r25c, CMake 3.22.1, Gradle wrapper JAR
bash setup-cli.sh

# Persist env vars (added by setup-cli.sh)
source ~/.bashrc
```

### Build

```bash
./gradlew assembleDebug
```

### Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Logcat

```bash
adb logcat -s CloX_Native LuaBridge OverlayController
```

## Adding Axmol (Phase 1A)

The home screen is currently black (Axmol stub). To activate the Lua watchface:

```bash
git submodule add https://github.com/axmolengine/axmol.git axmol
cd axmol && git checkout v2.x-stable
cd ..
./gradlew assembleDebug   # full native build — ~10 min first time
```

## Settings

Accessible via **CloX Settings** in any app list (second launcher entry).  
Double-tap the home screen also opens settings.

### Settings hierarchy

```
Settings
├── General      — double-tap action
├── App list     — style (list/grid/pages/honeycomb), hidden apps
├── Icons        — global icon pack, per-app overrides
└── Appearance   — blur mode (auto / snapshot / disabled), blur radius
```

## Implementation phases

| Phase | Feature | Status |
|---|---|---|
| 1A | Axmol Engine + Lua watchface | Stub (submodule needed) |
| 1B | App List Fragment | Done |
| 1C | Gesture System | Done |
| 2 | Quick Settings Overlay | Done |
| 3 | Widgets Overlay | Done |
| 4 | Notification + Media Overlay | Done |
| 5 | Blur Engine | Done |
| 6 | Settings Activity | Done |
| 7 | Home/Back key handling | Done |

## License

Private — all rights reserved.
