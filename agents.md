# AI Agent Guide — CloX

Context for AI coding agents (Claude Code, Copilot, etc.) working on this repo.

---

## Project identity

| Field | Value |
|---|---|
| App name | CloX |
| Package | `com.ailife.clox` |
| Language | Kotlin (primary), Lua (Axmol watchface) |
| Min SDK | 26 / Target 36 |
| ABI | `arm64-v8a` only |

---

## Hard constraints — never break these

| Rule | Reason |
|---|---|
| **No Jetpack Compose** | Explicit design decision — all UI is XML layouts + Fragments |
| **No ViewPager2** | Swipe navigation is entirely custom via `GestureInterceptorLayout` |
| **No `ndk.dir` in `local.properties`** | Deprecated — use `ndkVersion` in `app/build.gradle` instead |
| **Axmol SurfaceView owns the home screen** | `HomeFragment` must only wrap the Axmol GL surface — no Android UI widgets on the home screen |
| **All home-screen content in Lua** | Clock, animation, buttons → `assets/lua/watchface.lua`, not Android XML |
| **Material Design 3 throughout** | Use `Theme.Material3.*` and MD3 components everywhere |

---

## Architecture quick-reference

### Gesture routing (`GestureInterceptorLayout`)

Two touch paths — both produce identical callbacks:
- **Path A** (child consumed `ACTION_DOWN`, e.g. RecyclerView) → `onInterceptTouchEvent` fires on `MOVE`
- **Path B** (no child consumed `DOWN`, e.g. Axmol SurfaceView) → `onTouchEvent` owns the stream

Direction is locked only when `primaryAxis ≥ 1.5 × secondaryAxis` — prevents diagonal misfires.

Callbacks on `MainActivity`:
- `onSwipeStart` — direction locked
- `onSwipeUpdate(direction, dx, dy)` — only moves app list when direction matches (LEFT to open, RIGHT to close)
- `onSwipeEnd` — overlay dismiss has priority 1; then app-list threshold; then overlay open

### App list (`AppListFragment`)

- Always attached to `fragmentContainer`, positioned off-screen via `translationX = screenW`
- Opens by springing `translationX` to `0f`, closes by springing back to `screenW`
- `gestureRoot.appListOpen = true` suppresses vertical interception (lets RecyclerView scroll)
- Spring reference kept in `appListSpring` — always `cancel()` before starting a new one

### Overlay lifecycle

```
onSwipe(direction) → overlayController.openOverlay(view)
                   → BlurEngine.applyBlur(...)
                   → view.visibility = VISIBLE → animateIn
dismissActive()    → animateOut → view.visibility = GONE
```

Any swipe dismisses active overlay before any other routing runs (`handleSwipeEnd`).

### Blur engine strategy (priority order)

1. `RenderEffect.createBlurEffect` — API 31+
2. `RenderScript` blur on downsampled bitmap — API 26–30
3. Pure-Java stackblur on PixelCopy snapshot — user "snapshot" mode
4. Semi-transparent scrim — fallback / user "disabled" mode

### JNI bridge (`CocosManager` / `LuaBridge`)

- `launcher_native.cpp` — stub JNI; populate with Axmol calls after submodule is added
- `LuaBridge.passTouch(x, y, action)` — forwards single taps to Lua via JNI
- `LuaBridge.onLuaEvent(event, payload)` — Lua → Android event bus (called from native)
- `Bridge.sendToAndroid(event, payload)` — Lua → Android via `luaoc.callStaticMethod`

---

## File map — key files

| File | Purpose |
|---|---|
| `gesture/GestureInterceptorLayout.kt` | All touch interception and swipe routing |
| `gesture/SwipeDirection.kt` | `LEFT / RIGHT / UP / DOWN` enum |
| `MainActivity.kt` | Wires gestures → app list / overlays; spring animations |
| `home/HomeFragment.kt` | Adds Axmol SurfaceView; no Android UI |
| `cocos/CocosManager.kt` | Axmol lifecycle bridge (init/resume/pause/destroy) |
| `cocos/LuaBridge.kt` | JNI ↔ Lua call bridge |
| `applist/AppListViewModel.kt` | Loads installed apps; refreshes on package broadcast |
| `applist/AppListBackend.kt` | Interface — swap list/grid/pages/honeycomb backends |
| `overlay/BlurEngine.kt` | Runtime blur strategy selection |
| `overlay/OverlayController.kt` | Show/dismiss overlay views with blur + fade |
| `assets/lua/watchface.lua` | Axmol scene: clock, animation, `ccui.Button` touch test |
| `assets/lua/bridge.lua` | Lua-side event bus (onEvent / dispatchEvent / sendToAndroid) |
| `assets/lua/main.lua` | Axmol entry point — creates WatchfaceScene |
| `CMakeLists.txt` | Native build; detects Axmol submodule, falls back to JNI stub |

---

## Build commands

```bash
./gradlew assembleDebug                          # build
adb install -r app/build/outputs/apk/debug/app-debug.apk  # deploy
adb logcat -s CloX_Native LuaBridge             # logs
./gradlew lint                                   # static analysis
```

---

## Common mistakes to avoid

- **Do not** add any view to `HomeFragment` other than the Axmol SurfaceView — all home content belongs in Lua.
- **Do not** use `addToBackStack` for the app list — it's always attached, shown/hidden via `translationX`.
- **Do not** start a `SpringAnimation` on the app list without first calling `appListSpring?.cancel()` — multiple concurrent springs cause stuck positions.
- **Do not** route swipe-right to `NotificationOverlay` without first checking `appListOpen` — when the list is open, swipe-right closes the list.
- **Do not** add `ndk.dir` to `local.properties` — it is deprecated; `ndkVersion "25.2.9519653"` in `app/build.gradle` is sufficient.
- **Do not** intercept vertical touches when `appListOpen == true` — RecyclerView needs them to scroll.
- **Do not** `return true` unconditionally from `onInterceptTouchEvent` after `lockDirection()` — only intercept if `isDragging` was actually set.

---

## Open questions (resolve at implementation time)

1. **QS tile list** — exact tiles needed (WiFi, BT, DND, Flashlight, Airplane, Rotate confirmed; any hardware-specific ones like heart rate or AOD?)
2. **Double-tap action** — currently opens Settings; confirm final behavior
3. **Widget picker** — system `AppWidgetPickerActivity` or custom overlay?
4. **Notification dismiss** — swipe-to-dismiss individual notifications, or no dismiss?
5. **Lua API surface** — which Android data events does the final watchface need? (battery %, step count, heart rate, others?)
