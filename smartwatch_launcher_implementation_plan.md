# Smartwatch Launcher — Implementation Plan

## Project Identity

- **Language**: Kotlin (primary), Lua (Cocos2d watchface)
- **Min SDK**: API 26 / Target: API 36
- **Engine**: Axmol Engine (Cocos2d-x 3.17.2-compatible fork)
- **UI paradigm**: XML layouts + Fragments; Compose explicitly forbidden
- **Design system**: Material Design 3 throughout
- **Screen**: Square-primary; round mode deferred
- **Target hardware**: 4-core A53/A55, 2–4 GB RAM

---

## Architecture Overview

```
MainActivity (singleTask, no history)
├── HomeFragment          ← Axmol SurfaceView (Lua watchface)
├── AppListFragment       ← Swipe-left destination
└── OverlayController     ← Manages all overlay layers

Overlay layers (all full-screen, above HomeFragment):
├── QuickSettingsOverlay  ← Swipe-down
├── WidgetsOverlay        ← Swipe-up
└── NotificationOverlay   ← Swipe-right

SettingsActivity          ← Separate activity, XML PreferenceFragmentCompat
```

**Key architectural decisions:**
- `MainActivity` uses a single `FrameLayout` root. `HomeFragment` is always the bottom layer. `AppListFragment` slides in/out via `FragmentTransaction`. All four overlays are `ViewGroup`s that sit above the fragment container in the same `FrameLayout`, GONE by default.
- No `ViewPager2` — swipe navigation is entirely custom via `GestureInterceptorLayout`.
- Axmol runs in its own GL thread. The `AxmolSurfaceView` is the only child of `HomeFragment`.

---

## Module Structure

```
app/
├── src/main/
│   ├── kotlin/com.launcher/
│   │   ├── MainActivity.kt
│   │   ├── gesture/
│   │   │   ├── GestureInterceptorLayout.kt    ← Custom ViewGroup, all swipe routing
│   │   │   └── SwipeDirection.kt
│   │   ├── cocos/
│   │   │   ├── CocosManager.kt                ← Lifecycle bridge to Axmol
│   │   │   ├── LuaBridge.kt                   ← JNI ↔ Lua calls
│   │   │   └── SnapshotHelper.kt              ← GL surface screenshot via PixelCopy
│   │   ├── applist/
│   │   │   ├── AppListFragment.kt
│   │   │   ├── AppListAdapter.kt
│   │   │   ├── AppListBackend.kt              ← Interface
│   │   │   ├── backends/
│   │   │   │   └── ListBackend.kt             ← First impl (single column)
│   │   │   ├── AppListViewModel.kt
│   │   │   └── model/AppItem.kt
│   │   ├── overlay/
│   │   │   ├── OverlayController.kt
│   │   │   ├── BlurEngine.kt                  ← Hardware / RenderScript / snapshot fallback
│   │   │   ├── quicksettings/
│   │   │   │   ├── QuickSettingsOverlay.kt
│   │   │   │   └── QsTileView.kt
│   │   │   ├── widgets/
│   │   │   │   ├── WidgetsOverlay.kt
│   │   │   │   ├── WidgetCard.kt
│   │   │   │   ├── WidgetHostManager.kt       ← AppWidgetHost wrapper
│   │   │   │   └── cards/
│   │   │   │       └── AddWidgetCard.kt
│   │   │   └── notifications/
│   │   │       ├── NotificationOverlay.kt
│   │   │       ├── MediaPlayerCard.kt
│   │   │       ├── NotificationListAdapter.kt
│   │   │       └── ReplyPopup.kt
│   │   ├── icon/
│   │   │   ├── IconResolver.kt                ← Global icon pack + custom image
│   │   │   └── IconPackLoader.kt
│   │   └── settings/
│   │       ├── SettingsActivity.kt
│   │       └── fragments/
│   │           ├── GeneralSettingsFragment.kt
│   │           ├── AppListSettingsFragment.kt  ← Style picker, hidden apps
│   │           ├── BlurSettingsFragment.kt
│   │           └── IconSettingsFragment.kt
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml
│   │   │   ├── fragment_home.xml
│   │   │   ├── fragment_app_list.xml
│   │   │   ├── overlay_quick_settings.xml
│   │   │   ├── overlay_widgets.xml
│   │   │   ├── overlay_notifications.xml
│   │   │   ├── item_app_list.xml
│   │   │   ├── card_media_player.xml
│   │   │   ├── card_widget.xml
│   │   │   └── dialog_reply.xml
│   │   └── xml/
│   │       └── preferences_*.xml
│   └── assets/
│       └── lua/
│           ├── main.lua
│           ├── watchface.lua
│           └── bridge.lua
└── axmol/                                     ← Git submodule, Axmol engine source
    └── (built as :axmol AAR via CMake + NDK)
```

---

## Phase 1 — Axmol Integration & App List (Priority)

### 1A — Axmol Engine Setup

**Goal**: Axmol renders a digital clock with a simple animation in the watchface area.

#### Steps

1. **Add Axmol as a Git submodule**
   ```bash
   git submodule add https://github.com/axmolengine/axmol.git axmol
   git submodule update --init --recursive
   cd axmol && git checkout v2.x-stable   # pin a stable tag
   ```

2. **NDK & CMake requirements**
   - NDK: r25c or r26 (Axmol CI-tested)
   - CMake: 3.22+
   - `local.properties`: set `ndk.dir`
   - ABI filters: `arm64-v8a` only for now (covers all target hardware). Add `armeabi-v7a` if needed later.

3. **`axmol/CMakeLists.txt` integration**
   Include from your app's `CMakeLists.txt`:
   ```cmake
   set(AX_ENABLE_LUA ON)
   set(AX_ENABLE_PHYSICS OFF)
   set(AX_ENABLE_AUDIO ON)   # optional, for watchface SFX
   add_subdirectory(${CMAKE_SOURCE_DIR}/../axmol axmol_build)
   target_link_libraries(launcher_native axmol lua)
   ```

4. **`app/build.gradle` additions**
   ```groovy
   android {
       defaultConfig {
           externalNativeBuild { cmake { cppFlags "-std=c++17" } }
       }
       externalNativeBuild { cmake { path "CMakeLists.txt" } }
   }
   ```

5. **`CocosManager.kt`** — wraps Axmol lifecycle
   ```kotlin
   object CocosManager {
       fun init(activity: Activity)
       fun onResume()
       fun onPause()           // called when AppListFragment opens
       fun onDestroy()
       fun getSurfaceView(): SurfaceView  // the Axmol GL surface
   }
   ```

6. **`LuaBridge.kt`** — JNI calls into Lua runtime
   ```kotlin
   object LuaBridge {
       external fun callLuaFunction(funcName: String, args: String): String
       // Called from Axmol side via JNI to Kotlin:
       fun onLuaEvent(event: String, payload: String) { ... }
   }
   ```

7. **Lua side — `assets/lua/watchface.lua`**
   ```lua
   -- Simple digital clock + rotation animation for Axmol test
   local ClockScene = class("ClockScene", cc.Scene)

   function ClockScene:onEnter()
       local label = cc.Label:createWithSystemFont("00:00:00", "sans-serif", 48)
       label:setPosition(cx, cy)
       self:addChild(label)

       -- Update every second via scheduler
       cc.Director:getInstance():getScheduler():scheduleScriptFunc(function()
           local t = os.date("*t")
           label:setString(string.format("%02d:%02d:%02d", t.hour, t.min, t.sec))
       end, 1.0, false)

       -- Simple rotating square as animation test
       local square = cc.DrawNode:create()
       square:drawSolidRect(cc.p(-20,-20), cc.p(20,20), cc.c4f(0.2, 0.6, 1.0, 0.8))
       square:setPosition(cx, cy - 80)
       self:addChild(square)
       square:runAction(cc.RepeatForever:create(cc.RotateBy:create(2.0, 360)))
   end
   ```

8. **Axmol ↔ Android touch passthrough** (implemented in `GestureInterceptorLayout`, see §1C)

#### Migration notes (Cocos2d-x 3.17.2 → Axmol)
- Namespace: `cocos2d::` → `ax::` (or keep `CC_USE_PHYSICS` shim)
- `CCApplication` → `ax::Application`
- `CCEGLView` → `ax::GLView` (now `ax::backend::Device`)
- Lua bindings: identical API surface. `require "cocos.init"` still works.
- Audio: `SimpleAudioEngine` → `ax::AudioEngine` (async)
- **Most 3.17.2 Lua scripts run unmodified.** C++ glue code needs namespace + include path updates only.

---

### 1B — App List Fragment

**Goal**: Full-screen app list appears on swipe-left. Modular backend allows style switching.

#### `AppListBackend` interface
```kotlin
interface AppListBackend {
    // Called once to get the RecyclerView LayoutManager for this style
    fun createLayoutManager(context: Context): RecyclerView.LayoutManager
    // Called to bind each item view
    fun bindView(holder: RecyclerView.ViewHolder, item: AppItem)
    // The item layout resource to inflate
    val itemLayoutRes: Int
}
```

Concrete implementations in `backends/`:
- `ListBackend` — `LinearLayoutManager`, single-column, icon + label
- `GridBackend` (future) — `GridLayoutManager(3)`
- `PagesBackend` (future) — custom paged LayoutManager
- `HoneycombBackend` (future) — custom hex LayoutManager

#### `AppItem` model
```kotlin
data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable,           // resolved by IconResolver
    val isHidden: Boolean = false
)
```

#### `AppListViewModel`
- Uses `PackageManager.queryIntentActivities(MAIN/LAUNCHER)` to build the list
- Filters hidden apps based on SharedPreferences blacklist/whitelist
- Exposes `StateFlow<List<AppItem>>`
- Refreshes on `Intent.ACTION_PACKAGE_ADDED/REMOVED/CHANGED` broadcast

#### Icon resolution — `IconResolver`
Priority order:
1. Per-app custom image (stored in app-private storage, path in prefs)
2. Per-app icon pack override (from selected global icon pack)
3. Global icon pack (standard `IconPackLoader` — queries `AndroidManifest` `<icon-pack>` intent filter, loads from target package resources)
4. System default (`PackageManager.getApplicationIcon`)

#### App list XML layout (`fragment_app_list.xml`)
```xml
<FrameLayout>
    <!-- Search bar (MD3 SearchBar) -->
    <com.google.android.material.search.SearchBar ... />
    <!-- App list -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/appRecyclerView"
        android:clipToPadding="false" />
</FrameLayout>
```

#### Hidden apps management (in Settings)
- `AppListSettingsFragment` shows full app list with checkboxes
- Toggle mode: blacklist (hide checked) or whitelist (show only checked)
- Stored in `SharedPreferences` as `Set<String>` of package names

---

### 1C — Gesture System

**Goal**: `GestureInterceptorLayout` is a `FrameLayout` subclass that sits as the root of `activity_main.xml`. It intercepts all touch events before any child receives them.

#### Touch routing rules

| Gesture | Android action | Lua receives? |
|---|---|---|
| Swipe any direction | Intercepts, animates overlay/fragment | No |
| Long press anywhere | Intercepts (future: watchface edit mode) | No |
| Double tap | Intercepts, fires system action (configurable) | No |
| Single tap | Reads for double-tap detection timer, **passes raw MotionEvent to Lua via JNI** | Yes |

#### Implementation sketch

```kotlin
class GestureInterceptorLayout(ctx: Context, attrs: AttributeSet?) : FrameLayout(ctx, attrs) {

    private val gestureDetector = GestureDetectorCompat(ctx, object : SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            handleDoubleTap(); return true  // consumed by Android
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            LuaBridge.passTouch(e)  // forward to Lua
            return false            // not consumed — still passes to child views
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isSwipe(ev)) return true   // consume the swipe chain
        gestureDetector.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Handles swipe animation (layer follows finger, blurs background)
        trackSwipeAnimation(ev)
        return true
    }
}
```

#### Swipe animation (follow-finger + threshold)
- On `ACTION_MOVE`: translate the incoming overlay layer by `(deltaY for down/up, deltaX for left/right)`; simultaneously increase blur radius proportionally to distance traveled
- On `ACTION_UP`: if displacement > 40% of screen dimension → animate to full open (spring to final position); else → spring back to hidden
- Use `SpringAnimation` (AndroidX dynamicanimation) for the spring-back and open snap — feels natural on watch hardware
- Cocos2d view pauses (`CocosManager.onPause()`) the moment `AppListFragment` becomes visible; for other overlays, use performance mode decision (§ Blur section)

---

## Phase 2 — Quick Settings Overlay

**Reference**: The toggle style from the provided XML (fragment_qs.xml). All controls rebuilt in MD3.

### Layout structure (`overlay_quick_settings.xml`)
```xml
<FrameLayout android:id="@+id/qsRoot" ...>
    <!-- Blurred background (managed by BlurEngine) -->
    <ImageView android:id="@+id/qsBlurBg" ... />

    <LinearLayout android:orientation="vertical" ...>
        <!-- Top status bar row -->
        <include layout="@layout/qs_status_bar" />

        <!-- Toggle grid — same intent as fragment_qs.xml but MD3 styled -->
        <!-- Uses MaterialCardView tiles in a FlowLayout or GridLayout -->
        <androidx.gridlayout.widget.GridLayout
            android:id="@+id/qsTileGrid"
            android:columnCount="3" ... />

        <!-- Slider controls (brightness, volume) -->
        <com.google.android.material.slider.Slider android:id="@+id/brightnessSlider" />
        <com.google.android.material.slider.Slider android:id="@+id/volumeSlider" />
    </LinearLayout>
</FrameLayout>
```

### Toggle tiles (`QsTileView`)
- Each tile: `MaterialCardView` + icon + label + state indicator
- States: active (filled, primary color), inactive (outlined, surface color)
- Long press on tile → tile settings (future)
- Tiles to implement (first pass, ask for exact list at implementation time):
  WiFi, Bluetooth, DND, Flashlight, Airplane mode, Screen brightness auto, Rotate

**Note**: Ask user for the exact tile list and any non-standard tiles (e.g. heart rate, always-on display) before implementing.

---

## Phase 3 — Widgets & Shortcuts Overlay

### Layout (`overlay_widgets.xml`)
```xml
<FrameLayout>
    <ImageView android:id="@+id/widgetBlurBg" />
    <RecyclerView
        android:id="@+id/widgetList"
        android:orientation="vertical" />
</FrameLayout>
```

### Widget card sizing
- Width: always full-width (screen width)
- Height: 1–4 units, where 1 unit = `screenHeight / 5`
- User sets height via long-press → edit mode → up/down stepper

### Built-in card slot (future)
- Interface `LauncherCard` with `getView()`, `getMinHeight()`, `getSupportedSizes()`
- First-party cards (weather, steps, etc.) added later via this interface

### Third-party app widgets — `WidgetHostManager`
- Extends `AppWidgetHost`
- `BIND_APPWIDGET` permission: prompts user via `startActivityForResult(ACTION_APPWIDGET_PICK)`
- Widget picker: opened when user taps the "Add" card (last in list)
- Widget picker is a full-screen overlay (same style as QS)
- Resize maps to `AppWidgetHostView` `LayoutParams.height` change

### Widget interactions
- **Single tap**: passes through to widget
- **Long press**: enters edit mode — delete (X button appears) or resize (height stepper)
- Edit mode overlay uses a semi-transparent scrim over the widget card

---

## Phase 4 — Notification & Media Overlay

### Layout (`overlay_notifications.xml`)
```xml
<FrameLayout>
    <ImageView android:id="@+id/notifBlurBg" />
    <LinearLayout android:orientation="vertical">
        <!-- Pinned at top: media player card -->
        <include layout="@layout/card_media_player" />
        <!-- Notification list -->
        <RecyclerView android:id="@+id/notifList" />
    </LinearLayout>
</FrameLayout>
```

### Media player card (`card_media_player.xml`)
- Uses `MediaSession` / `MediaController` API
- Subscribes to active `MediaSession` via `MediaSessionManager.getActiveSessions()`
- Displays: album art (thumbnail), track title, artist, progress bar, play/pause, prev/next
- MD3 `MaterialCardView` with `ShapeableImageView` for art

### Notification list
- `NotificationListenerService` subclass
- Flat `RecyclerView`, each item: app icon, app name, title, body (truncated to 2 lines for small screen)
- **Action buttons**: `Notification.Action` list rendered as `MaterialButton` chips below body
- **Reply action**: detect `RemoteInput` on actions → show `ReplyPopup` (AlertDialog with `TextInputLayout` + `TextInputEditText` + Send button) → `RemoteInput.fillInIntent` → action `PendingIntent`

---

## Phase 5 — Blur Engine

`BlurEngine` selects the best available strategy at runtime:

| Priority | Condition | Strategy |
|---|---|---|
| 1 | API 31+ and user hasn't disabled HW blur | `RenderEffect.createBlurEffect()` on the overlay `View` |
| 2 | API 26–30 | `RenderScript` blur on a downsampled `Bitmap` |
| 3 | User explicitly disabled blur in settings | Snapshot mode (see below) |
| 4 | Neither HW nor RS available | Semi-transparent `ColorDrawable` scrim |

### Snapshot mode (performance save mode)
1. Call `PixelCopy.request(window, rect, bitmap, callback, handler)` (API 26+)
2. Apply stackblur (pure Java, no RS dependency) on the bitmap at 1/4 resolution, then scale up
3. Set result as `ImageView` background of the overlay
4. Call `CocosManager.onPause()` — GL thread stops rendering
5. On overlay dismiss: clear bitmap, call `CocosManager.onResume()`

**User setting**: `BlurSettingsFragment` in settings offers:
- Auto (default — use best available)
- Always snapshot (saves GPU, manual blur)
- Disable blur (scrim only)

---

## Phase 6 — Settings Activity

- Separate `SettingsActivity` (`android:launchMode="standard"`)
- Root: `PreferenceFragmentCompat` with nested `PreferenceScreen`s
- MD3 theme applied via `style="@style/Theme.Material3.DayNight"` in manifest

### Settings hierarchy
```
Settings
├── General
│   └── Double-tap action (choose app or action)
├── App list
│   ├── Style (preset picker: list, grid, pages, honeycomb)
│   ├── Hidden apps (multi-select list)
│   └── Icon settings
│       ├── Global icon pack (select installed pack)
│       └── Per-app overrides (list → tap app → pick from pack or gallery)
├── Appearance
│   └── Blur
│       ├── Blur mode (auto / snapshot / disabled)
│       └── Blur radius (slider, if not disabled)
├── Widgets
│   └── (future: widget-specific settings)
└── About
```

---

## Blur & Performance Notes for Target Hardware

- A53 @ 1.4 GHz: `RenderEffect` not available (API < 31 likely), use RS or snapshot
- A55 @ 1.8 GHz with API 31+: `RenderEffect` works well
- Always downsample to 1/4 before software blur — full-res blur on 400×400 is ~25ms on A53
- Cocos2d GL thread: always pause when any full-screen overlay is open in snapshot mode; in non-snapshot mode, Cocos can keep running under the overlay (the overlay is above it in the view hierarchy)

---

## Phase 7 — Home & Back Key Handling

- Override `onKeyDown` in `MainActivity` for `KEYCODE_HOME` and `KEYCODE_BACK`
- **Home**: dismiss any open overlay, return to `HomeFragment` if not already there
- **Back**: same as Home (no app history to pop — launcher is root)
- No custom `onBackPressedDispatcher` needed beyond this
- Cocos2d handles its own back key internally (Axmol's `keyboardEnabled`) — disable this and let Android handle it

---

## Remaining Clarification Needed (at implementation time)

1. **Quick Settings tiles**: exact list of tiles, any hardware-specific ones (heart rate sensor, AOD, GPS)?
2. **Double-tap action on home**: what does it do — open a specific app, toggle a QS tile, nothing?
3. **Notification overlay dismiss**: swipe-left to dismiss individual notifications, or no dismiss?
4. **Widget "Add" picker**: system picker (`AppWidgetPickerActivity`) or custom built overlay?
5. **Watchface Lua API**: what events does the final watchface Lua code need to receive from Android (step count, heart rate, battery)?

---

## Development Sequence (LLM Prompt Order)

1. **Axmol submodule + CMakeLists + Kotlin stubs** (CocosManager, LuaBridge, empty JNI)
2. **HomeFragment + AxmolSurfaceView wiring** + simple Lua clock scene
3. **GestureInterceptorLayout** with console-log-only swipe detection
4. **AppListFragment + ListBackend** with live app loading (no icon pack yet)
5. **AppListViewModel + broadcast receiver** for install/uninstall refresh
6. **IconResolver** (system icon → icon pack → custom image)
7. **BlurEngine** (all three strategies)
8. **Overlay scaffolding** (all four overlays wired to gesture directions)
9. **Quick Settings overlay** (tiles, sliders, MD3 styling)
10. **Widgets overlay** (RecyclerView of cards, AppWidgetHost, resize)
11. **Notification overlay** (NotificationListenerService, MediaSession card)
12. **Reply popup**
13. **SettingsActivity** (all fragments, preference XML)
14. **Performance pass** (profile on A53 target, fix jank)
15. **Axmol Lua optimization pass** (texture atlases, draw call batching)
