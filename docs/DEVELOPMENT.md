# CloX — Developer Guide

Architecture, build, and internals for **CloX**, an Android smartwatch launcher and watchface
engine (`com.yzmm.clox`). For the product overview see the [main README](../README.md). For
integrating other apps with CloX see the [Integration Guide](INTEGRATION.md).

## Architecture

```
MainActivity (singleTask)
├── HomeFragment          ← Axmol SurfaceView — Lua watchface (native GL renderer)
├── AppListFragment       ← Opens from the crown/home key (left swipe is opt-in)
└── OverlayController     ← Manages full-screen overlay layers

Overlays (above HomeFragment, GONE by default):
├── QuickSettingsOverlay  ← Side-button action (tiles + brightness/volume sliders)
├── WidgetsOverlay        ← Swipe-up    (AppWidgetHost cards)
└── NotificationOverlay   ← Swipe-down (media card + notification list)
```

**Gesture system** — `GestureInterceptorLayout` (root `FrameLayout` subclass):
- Requires 1.5× directional ratio before committing direction (prevents diagonal misfires)
- App list follows finger in real time via `translationX`, snaps with `SpringAnimation` on release
  (left-swipe entry disabled by default; the crown/home key toggles the list)
- Quick Settings opens from `KEYCODE_STEM_1/2/3` or any button mapped to `expand_qs`
- Top-opened notification panel scrolls with DOWN and collapses on UP once the list is at the bottom
- Vertical scroll passes through to RecyclerView when app list is open
- Any swipe dismisses an active overlay before other routing runs

**Watchface engine** — Axmol (Lua + C++), native GL renderer:
- `assets/lua/` — Axmol entry (`main.lua`), scenes, and the `bridge.lua` event bus that feeds
  Android data (battery, steps, sensors) into Lua scenes
- `assets/lua/wf_*.lua` — WatchMaker face engine: tag-indexed event buckets (`wf_engine`),
  compiled attribute expressions (`wf_expr`), sandboxed face scripts (`wf_script`), and the
  `wf_render` builders (see `WF_ENGINE_PROGRESS.md`)
- `Source/shaders/wm_*.frag` — WatchMaker shape/effect shaders; `axslcc` compiles them to
  `assets/axslc/custom/` during the native build (see the shader-rebuild note below)
- `assets/wf_default/` — bundled default watchface; `assets/watchface_remote/` — remote face host

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin (Android), Lua (watchface engine), C++ (native/JNI) |
| Engine | [Axmol Engine](https://github.com/axmolengine/axmol) (Cocos2d-x fork) — vendored as a submodule fork |
| UI | Jetpack Compose + Wear Material 3 (migration in progress); legacy XML layouts + Fragments coexist |
| Design | Wear Material 3 / Material 3 |
| Min SDK | 26 (Android 8.0) / Compile 37 / Target 36 |
| ABI | `armeabi-v7a`, `arm64-v8a` (+ `x86`/`x86_64` for emulator builds) |
| Build | Gradle 8.4 + CMake 3.22.1 + NDK r25c |

The Axmol engine is a submodule pointing at the CloX fork
(`git@gitlab.com:ailife8881/clox-axmol.git`). Clone with `--recurse-submodules`.

## Project structure

```
app/src/main/
├── kotlin/com/launcher/
│   ├── gesture/          GestureInterceptorLayout, SwipeDirection
│   ├── cocos/            CocosManager, LuaBridge, SnapshotHelper
│   ├── applist/          AppListFragment, ViewModel, Adapter, Backends
│   ├── overlay/          OverlayController, BlurEngine, QS, Widgets, Notifications
│   ├── icon/             IconResolver, IconPackLoader
│   └── settings/         SettingsActivity + PreferenceFragmentCompat screens
├── kotlin/com/yzmm/clox/   MainActivity, public API receivers/providers
├── assets/lua/           main.lua, bridge.lua, wf_*.lua (WatchMaker engine)
├── assets/wf_default/    bundled default watchface
└── cpp/                  JNI bridge (CocosManager, LuaBridge)
Source/shaders/           wm_*.frag WatchMaker shaders (compiled by axslcc)
```

## CLI build setup

> Android Studio is **not required**. All build and deploy steps work from the terminal.

### Prerequisites

- Java 17+ (`java -version`)
- Android SDK with cmdline-tools (run `bash setup-cli.sh` to install everything automatically)

### First-time setup

```bash
# Clone with the Axmol submodule
git clone --recurse-submodules https://github.com/YzmmQwQ/CloX.git

# Install Android SDK, NDK r25c, CMake 3.22.1, Gradle wrapper JAR
bash setup-cli.sh

# Persist env vars (added by setup-cli.sh)
source ~/.bashrc
```

### Build

```bash
./gradlew assembleDebug   # full native build — ~10 min first time
```

### Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Logcat

```bash
adb logcat -s CloX_Native LuaBridge OverlayController
```

### Release signing

Release APKs use a fixed key so successive builds install over each other. The
keystore stays out of the repo (`keystore/` is git-ignored):

- **Local builds** — create `keystore/keystore.properties` with `storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`. Release builds pick it up
  automatically; debug builds keep the standard debug key.
- **CI** — add repository secrets `CLOX_KEYSTORE_BASE64` (base64 of the `.jks`),
  `CLOX_STORE_PASSWORD`, `CLOX_KEY_ALIAS`, `CLOX_KEY_PASSWORD`. The workflow
  restores `keystore/keystore.properties` before building and fails a release
  build when the key is missing.
- Without a key, release falls back to the debug key — fine for local testing,
  but its signature differs per machine/run.

### Rebuilding shaders

Shaders compile during the native build into `build/runtime/axslc` and are
packaged as `assets/axslc/` (the asset merge waits for the native build).
Editing `Source/shaders/*.frag` and re-running Gradle is enough — no manual
`axslcc` step.

## Settings

Accessible via **CloX Settings** in the app list (second launcher entry). The whole section is
Jetpack Compose + Wear Material 3 and follows the standard Wear screen structure: one
`AppScaffold` (TimeText) around a `SwipeDismissableNavHost` in `SettingsActivity`; each screen is a
`WearSettingsScreen` = `ScreenScaffold` + `TransformingLazyColumn` (snap fling/rotary, scroll
indicator, `ListHeader` section headers, per-item transformations and M3 content padding). Screens
live in `settings/compose/`, shared rows/dialogs in `SettingsComponents.kt`, routes in
`SettingsRoutes.kt`. The old `settings/fragments/*` hosts and XML resources are dead code.

```
Settings
├── Notifications   — panel styles, alerts, filtering, listener access
├── Quick settings  — tiles, appearance, privileges, global overlay
├── App list        — style + per-style options, icon pack, background
├── Animations      — open/close/gesture-return animations
├── Watchface       — selector, sort, auto-change, store/remote/import
├── Gestures        — double-tap/long-press, swipe actions, button mapping
├── AOD             — timeout, brightness, reawaken, lock gesture
├── Appearance      — dark mode, DPI, blur mode/radius
├── Tap actions     — watchface shortcut → app mapping
├── Weather / Location / Health / Sensors — data sources
├── Hide apps / RTOS / Debug / About
```

## Third-party integration

CloX exposes public APIs so companion apps, transfer tools, and watchface stores can push data,
change the active watchface, install faces, and manage the watchface directory. See:

- [INTEGRATION.md](INTEGRATION.md) — root index of all public APIs
- [EXTERNAL_DATA_API.md](EXTERNAL_DATA_API.md) — push key/value data to the active face
- [integrations/](integrations/) — change / install / directory references

## License

Private — all rights reserved.
