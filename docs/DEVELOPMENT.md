# CloX — Developer Guide

Architecture, build, and internals for **CloX**, an Android smartwatch launcher and watchface
engine (`com.ailife.clox`). For the product overview see the [main README](../README.md). For
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
└── NotificationOverlay   ← Swipe-down or swipe-right (MediaSession card + notification list)
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
| UI | XML layouts + Fragments — Jetpack Compose **not used** |
| Design | Material Design 3 |
| Min SDK | 26 (Android 8.0) / Compile & Target 36 |
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
├── kotlin/com/ailife/clox/   MainActivity, public API receivers/providers
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
git clone --recurse-submodules git@gitlab.com:ailife8881/clox.git

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

Accessible via **CloX Settings** in the app list (second launcher entry). Live settings are the
custom cards under `settings/fragments/*`; `res/xml/preferences_*.xml` is dead code.

```
Settings
├── General      — gestures, actions, double-tap
├── App list     — style (list/grid/pages/honeycomb), hidden apps
├── Icons        — global icon pack, per-app overrides
├── Watchface    — active face, watchface directory
└── Appearance   — blur mode (auto / snapshot / disabled), blur radius
```

## Third-party integration

CloX exposes public APIs so companion apps, transfer tools, and watchface stores can push data,
change the active watchface, install faces, and manage the watchface directory. See:

- [INTEGRATION.md](INTEGRATION.md) — root index of all public APIs
- [EXTERNAL_DATA_API.md](EXTERNAL_DATA_API.md) — push key/value data to the active face
- [integrations/](integrations/) — change / install / directory references

## License

Private — all rights reserved.
