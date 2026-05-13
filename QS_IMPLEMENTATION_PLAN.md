# Quick Settings — Full Implementation Plan (v3)

---

## Layout — Arrangement

```
┌─────────────────────────────────┐
│  HH:MM        🔋 85%      Mon 13│  ← Header row
├────────────────┬────────────────┤
│  📶 Wi-Fi      │  📡 Data       │  ← ViewPager2 tile area (swipe ←→)
│  Home-5G       │  LTE ●●●○      │    Each page: columnCount × 2 rows
├────────────────┼────────────────┤    (e.g. 2 cols = 4 tiles/page,
│  🔵 Bluetooth  │  🔊 Volume     │         3 cols = 6 tiles/page)
│  2 devices     │  72%           │    Remaining tiles on next page(s)
├────────────────┴────────────────┤
│            ● ○ ○                │  ← Page indicator dots
├─────────────────────────────────┤
│  ☀ ──────────────────────── ☀  │  ← Brightness slider (always visible)
├─────────────────────────────────┤
│  ⚙launcher  ↺recents  ⏻power  ⚙sys │  ← Bottom action bar (fixed)
└─────────────────────────────────┘
```

**Order:** Header → Paged tile area + indicator → Brightness slider → Bottom bar

No separate extra row. All tiles live in the single paged grid.

---

## Phase 1 — Dependencies & Manifest

### 1.1 `app/build.gradle` additions

```groovy
// ViewPager2 (tile pagination)
implementation 'androidx.viewpager2:viewpager2:1.1.0'

// Shizuku
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'

// Root via libsu
implementation 'com.github.topjohnwu.libsu:core:5.2.2'
implementation 'com.github.topjohnwu.libsu:service:5.2.2'

// JSON for tile order + custom tile config storage
implementation 'org.json:json:20231013'  // already on classpath on Android, but explicit is safer

// Add jitpack to settings.gradle if not already:
// maven { url 'https://jitpack.io' }
```

### 1.2 `AndroidManifest.xml` additions

```xml
<!-- Bluetooth API 31+ (remove maxSdkVersion="30" from existing entries) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- WiFi state reading -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Mobile data signal, Data tile state -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />

<!-- WiFi SSID on API 33+ -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="tiramisu" />

<!-- Camera (hardware flashlight on some devices) -->
<uses-permission android:name="android.permission.CAMERA" />

<!-- Location tile -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Shizuku provider (binder bridge) -->
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

<!-- Accessibility service (power menu + recents) -->
<service
    android:name=".qs.CloXAccessibilityService"
    android:exported="true"
    android:label="CloX Quick Actions"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**New resource:** `res/xml/accessibility_service_config.xml`
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="0"
    android:accessibilityFlags="0"
    android:canRetrieveWindowContent="false"
    android:canPerformGestures="false"
    android:description="@string/accessibility_service_description"
    android:settingsActivity="com.ailife.clox.settings.SettingsActivity" />
```
Description string: "Allows CloX to show the power menu and recent apps from Quick Settings."

---

## Phase 2 — Privilege Layer

### New package: `com/ailife/clox/qs/privilege/`

#### `PrivilegeManager.kt`

```kotlin
enum class PrivilegeLevel { NONE, SHIZUKU, ROOT }

object PrivilegeManager {
    fun level(ctx: Context): PrivilegeLevel
    fun isShizukuAvailable(): Boolean          // ShizukuProvider.isShizukuInstalled()
    fun hasShizukuPermission(): Boolean        // Shizuku.checkSelfPermission() == GRANTED
    fun requestShizukuPermission(activity, requestCode: Int)
    fun isRootAvailable(): Boolean             // Shell.getShell().isRoot
}
```

Detection order: Shizuku (preferred, no root required) > Root > None.
Cache result per-session; invalidate on `onResume` (user may have started Shizuku).

#### `ShizukuOperations.kt`

Uses `Shizuku.newProcess(cmd, env, dir)` shell approach — more stable than binder reflection.
Each method returns `Boolean` success; `false` triggers fallback in callers.

```kotlin
object ShizukuOperations {
    fun setWifiEnabled(enabled: Boolean): Boolean
        // Shizuku.newProcess(["cmd","wifi","set-wifi-enabled",flag], ...)
    fun setBluetoothEnabled(enabled: Boolean): Boolean
        // cmd bluetooth_manager set-state enable/disable
    fun setMobileDataEnabled(enabled: Boolean): Boolean
        // svc data enable/disable
    fun setAirplaneModeEnabled(ctx: Context, enabled: Boolean): Boolean
        // settings put global airplane_mode_on + broadcast
    fun setLocationEnabled(ctx: Context, enabled: Boolean): Boolean
        // settings put secure location_mode 0/3
    fun setHotspotEnabled(enabled: Boolean): Boolean
        // svc wifi hotspot enable/disable (API 29+)
}
```

#### `RootOperations.kt`

Uses `libsu` Shell API. Returns `Boolean` success.

```kotlin
object RootOperations {
    suspend fun setWifiEnabled(enabled: Boolean): Boolean
        // Shell.cmd("svc wifi enable/disable").exec()
    suspend fun setBluetoothEnabled(enabled: Boolean): Boolean
        // Shell.cmd("svc bluetooth enable/disable").exec()
    suspend fun setMobileDataEnabled(enabled: Boolean): Boolean
        // Shell.cmd("svc data enable/disable").exec()
    suspend fun setAirplaneModeEnabled(ctx: Context, enabled: Boolean): Boolean
        // settings put global airplane_mode_on + broadcast
    suspend fun setLocationEnabled(ctx: Context, enabled: Boolean): Boolean
        // settings put secure location_mode 0/3
    suspend fun setHotspotEnabled(enabled: Boolean): Boolean
        // svc wifi hotspot enable/disable
    suspend fun writeSettings(namespace: String, key: String, value: String): Boolean
        // settings put <namespace> <key> <value>  — used by custom tiles
}
```

---

## Phase 3 — Accessibility Service

### New file: `qs/CloXAccessibilityService.kt`

Minimal service — only used for global action dispatch. Requires no event listening.

```kotlin
class CloXAccessibilityService : AccessibilityService() {
    companion object {
        var instance: WeakReference<CloXAccessibilityService>? = null

        fun isRunning() = instance?.get() != null

        fun triggerPowerMenu(): Boolean =
            instance?.get()?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ?: false

        fun triggerRecents(): Boolean =
            instance?.get()?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }

    override fun onServiceConnected() { instance = WeakReference(this) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
```

**Power action:** `GLOBAL_ACTION_POWER_DIALOG` (API 21+) — shows native Android power menu (power off, restart, emergency, etc.).
**Recents action:** `GLOBAL_ACTION_RECENTS` (API 21+) — shows recents screen.

If accessibility is not enabled when user taps Power or Recents:
→ Show `Snackbar`/`Toast` + `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`.

`QsPermissionHelper` adds `ensureAccessibilityEnabled(ctx): Boolean` which checks and redirects.

---

## Phase 4 — Tile System (Modular + Custom)

### 4.1 Built-in Tile Registry

#### New file: `qs/tile/TileId.kt`

```kotlin
enum class TileId(val key: String) {
    WIFI("wifi"),
    DATA("data"),
    BLUETOOTH("bluetooth"),
    VOLUME("volume"),       // opens VolumeDialog, shows current level as detail
    DND("dnd"),
    ROTATE("rotate"),
    FLASHLIGHT("flashlight"),
    LOCATION("location"),
    AOD("aod"),             // placeholder — no backend yet
    // Custom tiles are addressed as "custom:$uuid" — not in this enum
}
```

#### New file: `qs/tile/TileDescriptor.kt`

```kotlin
data class TileDescriptor(
    val id: String,                  // TileId.key or "custom:$uuid"
    val defaultLabel: String,
    val iconRes: Int,
    val hasState: Boolean = true,    // false = always inactive appearance (e.g. volume shortcut)
)
```

Registry singleton `TileRegistry` holds the canonical list of all built-in `TileDescriptor`s.

### 4.2 Custom Tile Config

#### New file: `qs/tile/CustomTileConfig.kt`

```kotlin
enum class CustomTileMode { LAUNCH_ACTIVITY, WRITE_SETTINGS }

data class CustomTileConfig(
    val uuid: String,               // random UUID, stable ID
    val label: String,
    val mode: CustomTileMode,
    // LAUNCH_ACTIVITY fields:
    val activityComponent: String?, // "com.pkg/.Activity" format
    // WRITE_SETTINGS fields:
    val settingsNamespace: String?, // "system" | "secure" | "global"
    val settingsKey: String?,
)
```

Storage: JSON array in `SharedPreferences` under key `qs_custom_tiles`.

**WRITE_SETTINGS custom tile behavior:**
- Read state: `Settings.{namespace}.getInt(cr, key, 0)` → `active = (value != 0)`
- Toggle: write `1 - currentValue` (clamps non-binary values to 0)
- `secure` and `global` namespaces require elevated privilege; show privilege warning if not available.

**LAUNCH_ACTIVITY custom tile behavior:**
- State: always `inactive` (it's a launcher, not a toggle)
- Click: `startActivity(Intent().setComponent(ComponentName.unflattenFromString(component)))`
- Wrap in `try/catch ActivityNotFoundException` → `Toast "App not found"`

### 4.3 Tile Layout Persistence

#### New file: `qs/tile/TileLayoutManager.kt`

```kotlin
object TileLayoutManager {
    // Default active tile order (shown on first install)
    // All other tiles (airplane, hotspot, location, aod, flashlight, custom…) start in the available pool
    val DEFAULT_ACTIVE = listOf("wifi", "data", "bluetooth", "volume", "dnd", "rotate")

    // Full available pool (built-in)
    val BUILT_IN_POOL = TileId.values().map { it.key }

    // Load/save ordered active tile ID list from SharedPreferences as JSON array
    fun loadActiveTiles(ctx: Context): List<String>
    fun saveActiveTiles(ctx: Context, ids: List<String>)

    // Load/save custom tile configs
    fun loadCustomTiles(ctx: Context): List<CustomTileConfig>
    fun saveCustomTiles(ctx: Context, tiles: List<CustomTileConfig>)
    fun addCustomTile(ctx: Context, config: CustomTileConfig)
    fun removeCustomTile(ctx: Context, uuid: String)

    // Returns full descriptor for any tile id (built-in or custom)
    fun descriptorFor(id: String, ctx: Context): TileDescriptor?

    // Pagination helper — splits active list into pages
    fun paginateTiles(ids: List<String>, columns: Int): List<List<String>> =
        ids.chunked(columns * 2)   // 2 rows per page
}
```

---

## Phase 5 — State Management

### New package: `com/ailife/clox/qs/`

#### `QsPrefs.kt`

```kotlin
object QsPrefs {
    const val QS_ENABLED            = "qs_enabled"
    const val TILE_COLUMNS          = "qs_tile_columns"       // 2 or 3, default 2
    const val COLORED_TILES         = "qs_color_tiles"
    const val COLOR_CONNECTED       = "qs_color_connected"    // default #379950
    const val COLOR_IDLE            = "qs_color_idle"         // default #2444E3
    const val COLOR_DISCONNECTED    = "qs_color_disconnected" // default #AD7623
    const val COLOR_OFF             = "qs_color_off"          // default #AAAAAA
    const val ROUND_MARGIN          = "qs_round_margin"       // 0–50dp default 0
    const val PRIVILEGE_MODE        = "qs_privilege"          // "none"|"shizuku"|"root"
    const val FLASHLIGHT_HARDWARE   = "qs_flashlight_hardware" // false = screen mode
    const val TILE_ORDER            = "qs_tile_order"         // JSON array of tile IDs
    const val CUSTOM_TILES          = "qs_custom_tiles"       // JSON array of configs
    const val PERMISSIONS_REQUESTED = "qs_permissions_done"
}
```

#### `QsTileState.kt`

```kotlin
sealed class TileState {
    data class WifiState(
        val enabled: Boolean, val connected: Boolean,
        val ssid: String?, val frequency: String?
    ) : TileState()
    data class DataState(
        val enabled: Boolean, val networkType: String?, val signalBars: Int
    ) : TileState()
    data class BtState(
        val enabled: Boolean, val connectedDevices: Int
    ) : TileState()
    data class VolumeState(
        val level: Int, val max: Int, val muted: Boolean
    ) : TileState()
    data class DndState(
        val active: Boolean, val ringerMode: Int
    ) : TileState()
    data class RotateState(val locked: Boolean) : TileState()
    data class FlashlightState(val on: Boolean) : TileState()
    data class LocationState(val enabled: Boolean) : TileState()
    data class AodState(val enabled: Boolean) : TileState()   // placeholder
    data class AirplaneState(val enabled: Boolean) : TileState()
    data class HotspotState(val enabled: Boolean) : TileState()
    data class BrightnessState(val value: Int, val autoMode: Boolean) : TileState()
    data class BatteryState(val level: Int, val charging: Boolean) : TileState()
}
```

#### `QsStateManager.kt`

Holds one `MutableStateFlow` per tile. Registered receivers update flows.
`attach()` called when panel fully opens; `detach()` when panel closes.

```kotlin
class QsStateManager(private val ctx: Context) {
    val wifi: StateFlow<WifiState>
    val data: StateFlow<DataState>
    val bluetooth: StateFlow<BtState>
    val volume: StateFlow<VolumeState>
    val dnd: StateFlow<DndState>
    val rotate: StateFlow<RotateState>
    val flashlight: StateFlow<FlashlightState>
    val location: StateFlow<LocationState>
    val aod: StateFlow<AodState>           // always emits AodState(false) for now
    val airplane: StateFlow<AirplaneState>
    val hotspot: StateFlow<HotspotState>
    val brightness: StateFlow<BrightnessState>
    val battery: StateFlow<BatteryState>
    val time: StateFlow<String>

    fun attach()
    fun detach()
    fun stateFor(tileId: String): StateFlow<TileState>?  // maps id → flow
}
```

**Receiver/callback map:**

| State | Mechanism |
|---|---|
| WiFi | `WIFI_STATE_CHANGED_ACTION` + `NetworkCallback(TRANSPORT_WIFI)` |
| Data | `NetworkCallback(TRANSPORT_CELLULAR)` + `PhoneStateListener` (API<31) / `TelephonyCallback` (API31+) |
| Bluetooth | `ACTION_STATE_CHANGED` + `ACTION_CONNECTION_STATE_CHANGED` |
| Volume | `AudioManager.VOLUME_CHANGED_ACTION` |
| DND | `ACTION_INTERRUPTION_FILTER_CHANGED` + ringer mode read |
| Rotate | `ContentObserver` on `Settings.System.ACCELEROMETER_ROTATION` |
| Flashlight | Internal state (no system broadcast) |
| Location | `LocationManager.PROVIDERS_CHANGED_ACTION` |
| AOD | Read only (placeholder) |
| Airplane | `ACTION_AIRPLANE_MODE_CHANGED` |
| Hotspot | `WIFI_AP_STATE_CHANGED_ACTION` |
| Battery | `ACTION_BATTERY_CHANGED` (sticky) |
| Time | `ACTION_TIME_TICK` + immediate read |
| Brightness | `ContentObserver` on `Settings.System.SCREEN_BRIGHTNESS` |

---

## Phase 6 — QS Panel UI

### 6.1 `res/layout/overlay_quick_settings.xml` — Full redesign

Correct order: header → tile grid → brightness → extra row → bottom bar.

```xml
<FrameLayout>
  <!-- Blur + scrim layers (z=0, z=1) -->
  <ImageView android:id="@+id/qsBlurBg" android:tag="blur_bg" />
  <View android:background="#CC000000" />

  <!-- Main scrollable content (in case tiles overflow on small screens) -->
  <ScrollView android:fillViewport="true">
    <LinearLayout android:orientation="vertical" android:padding="16dp">

      <!-- HEADER -->
      <RelativeLayout android:id="@+id/qsHeader" android:paddingBottom="8dp">
        <TextView android:id="@+id/qsTime"        align=start  />
        <LinearLayout android:id="@+id/qsBatteryGroup" align=centerHorizontal>
          <ImageView android:id="@+id/qsBatteryIcon" w=16dp h=16dp />
          <TextView android:id="@+id/qsBatteryPct" />
        </LinearLayout>
        <TextView android:id="@+id/qsDate"        align=end    />
      </RelativeLayout>

      <!-- PAGED TILE AREA -->
      <!-- Height is fixed: 2 rows × tile height, so the pager doesn't resize between pages -->
      <androidx.viewpager2.widget.ViewPager2
          android:id="@+id/qsTilePager"
          android:layout_width="match_parent"
          android:layout_height="wrap_content" />

      <!-- PAGE INDICATOR — built from TabLayout styled as dots, or a simple custom view -->
      <com.google.android.material.tabs.TabLayout
          android:id="@+id/qsPageIndicator"
          android:layout_width="wrap_content"
          android:layout_height="8dp"
          android:layout_gravity="center_horizontal"
          android:layout_marginTop="6dp"
          app:tabBackground="@drawable/qs_page_dot_selector"
          app:tabGravity="center"
          app:tabIndicatorHeight="0dp"
          app:tabPaddingStart="4dp"
          app:tabPaddingEnd="4dp" />
      <!-- dot selector drawable: filled circle when selected, outline circle otherwise -->
      <!-- Visibility: gone when only 1 page -->

      <!-- DIVIDER -->
      <View android:layout_height="1dp" android:background="?colorOutlineVariant"
          android:layout_marginVertical="8dp" />

      <!-- BRIGHTNESS SLIDER -->
      <LinearLayout android:id="@+id/qsBrightnessRow" android:orientation="horizontal"
          android:gravity="center_vertical" android:layout_marginBottom="4dp">
        <ImageView android:src="@drawable/ic_brightness_low" w=20dp h=20dp />
        <com.google.android.material.slider.Slider
            android:id="@+id/brightnessSlider"
            android:layout_weight="1"
            app:thumbRadius="8dp"
            app:haloRadius="16dp" />
        <ImageView android:src="@drawable/ic_brightness_high" w=20dp h=20dp />
      </LinearLayout>

      <!-- BOTTOM ACTION BAR -->
      <LinearLayout android:id="@+id/qsBottomBar"
          android:gravity="center"
          android:layout_marginTop="4dp">
        <ImageButton android:id="@+id/qsBtnLauncherSettings"
            android:src="@drawable/ic_tune"
            style="@style/Widget.Material3.Button.IconButton" />
        <ImageButton android:id="@+id/qsBtnRecents"
            android:src="@drawable/ic_recents"
            style="@style/Widget.Material3.Button.IconButton" />
        <ImageButton android:id="@+id/qsBtnPower"
            android:src="@drawable/ic_power"
            style="@style/Widget.Material3.Button.IconButton" />
        <ImageButton android:id="@+id/qsBtnSettings"
            android:src="@drawable/ic_settings"
            style="@style/Widget.Material3.Button.IconButton" />
      </LinearLayout>

    </LinearLayout>
  </ScrollView>
</FrameLayout>
```

Note: Volume is a main tile (not a bottom bar button). The bottom bar has 4 fixed actions: Launcher Settings, Recents, Power, System Settings.

### 6.2 `res/layout/view_qs_tile.xml` — MD3 redesign

```xml
<com.google.android.material.card.MaterialCardView
    style="@style/Widget.Material3.CardView.Filled"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:minHeight="72dp"
    app:cardCornerRadius="16dp">
  <LinearLayout android:orientation="vertical"
      android:gravity="center" android:padding="12dp"
      android:layout_width="match_parent">
    <ImageView android:id="@+id/tileIcon" w=24dp h=24dp />
    <TextView android:id="@+id/tileLabel"
        style="@style/TextAppearance.Material3.LabelMedium"
        android:layout_marginTop="4dp"
        android:gravity="center" />
    <TextView android:id="@+id/tileDetail"
        style="@style/TextAppearance.Material3.LabelSmall"
        android:textColor="?colorOnSurfaceVariant"
        android:visibility="gone"
        android:gravity="center"
        android:maxLines="1"
        android:ellipsize="end" />
  </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

### 6.3 `QsTileView.kt` — Updated

- Add `tileDetail: TextView`
- `setTileState(active: Boolean, detail: String? = null)` — updates appearance + detail text
- Color when `active`: MD3 `colorPrimaryContainer` background + `colorOnPrimaryContainer` icon/text
- Color when `inactive`: `colorSurfaceVariant` background + `colorOnSurfaceVariant` icon/text
- Color-coded mode (when `QsPrefs.COLORED_TILES` is true): read state-specific hex from `QsPrefs` instead of MD3 defaults

```kotlin
fun setTileState(active: Boolean, detail: String? = null, stateColor: Int? = null) {
    isActive = active
    tileDetail.text = detail ?: ""
    tileDetail.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    val bgColor = stateColor ?: resolvedColorForState(active)
    setCardBackgroundColor(bgColor)
    // Update icon/text tint appropriately
}
```

### 6.4 `QuickSettingsOverlay.kt` — Full rewrite

Key responsibilities:
1. Inject `QsStateManager` + `TileLayoutManager` reference (passed by `OverlayController`)
2. On `onAttachedToWindow`:
   - Read `QsPrefs.TILE_COLUMNS` (2 or 3) and `TileLayoutManager.loadActiveTiles()`
   - Compute `tilesPerPage = columns * 2` (always 2 rows per page)
   - Partition active tile list into pages: `tiles.chunked(tilesPerPage)`
   - Set `qsTilePager.adapter = QsTilePageAdapter(pages, columns, stateManager)`
   - Connect `TabLayoutMediator(qsPageIndicator, qsTilePager)` for dots; hide indicator if pages == 1
   - Start collecting state flows
3. Each `QsTileView` maps its ID to the correct `StateFlow` from `QsStateManager.stateFor(id)`
4. `rebuildGrid()`: re-runs step 2, called when column count pref changes
5. Gesture conflict: `ViewPager2` handles horizontal swipes internally; the outer `GestureInterceptorLayout` only intercepts vertical swipes, so there is no conflict
6. Brightness slider: WRITE_SETTINGS check on first change; auto-brightness icon toggles `Settings.System.SCREEN_BRIGHTNESS_MODE`
7. Bottom bar buttons wired to actions (see action table below)

**Tile click action table:**

| Tile | Primary action | Fallback chain |
|---|---|---|
| WiFi | toggle | API<29 direct → Shizuku `cmd wifi` → Root `svc wifi` → `ACTION_WIFI_SETTINGS` panel |
| Data | toggle | Shizuku/Root `svc data` → `WRITE_SECURE_SETTINGS` if granted → `ACTION_INTERNET_CONNECTIVITY` (API29+) → data settings |
| Bluetooth | toggle | API≤32 direct → API33+ Shizuku/Root `svc bluetooth` → `REQUEST_ENABLE/DISABLE` intent |
| Volume | open dialog | `VolumeDialogFragment.show()` always works |
| DND | toggle | `NotificationManager.setInterruptionFilter()` if policy access granted → redirect to policy settings |
| Rotate | toggle | `Settings.System.ACCELEROMETER_ROTATION` if `WRITE_SETTINGS` granted → redirect |
| Flashlight | toggle | Hardware: `CameraManager.setTorchMode()` — Screen: show `FlashlightOverlay` (white fullscreen) |
| Location | toggle | Shizuku/Root `settings put secure location_mode` → `ACTION_LOCATION_SOURCE_SETTINGS` |
| AOD | toggle | Show "Coming soon" `Toast` — no backend yet |
| Airplane | toggle | Shizuku/Root only → `AIRPLANE_MODE_SETTINGS` if no privilege |
| Hotspot | toggle | Shizuku/Root → open hotspot settings |
| **Recents** (bottom bar) | show recents | `CloXAccessibilityService.triggerRecents()` → prompt enable accessibility if not running |
| **Power** (bottom bar) | power menu | `CloXAccessibilityService.triggerPowerMenu()` → prompt enable accessibility if not running |
| **Launcher Settings** | open CloX settings | `startActivity(SettingsActivity)` always works |
| **System Settings** | open Android settings | `startActivity(ACTION_SETTINGS)` always works |

---

## Phase 7 — Flashlight Screen Overlay

When `QsPrefs.FLASHLIGHT_HARDWARE` is `false` (default), tapping the Flashlight tile shows a full-screen white overlay instead of the camera LED.

### New file: `overlay/quicksettings/FlashlightOverlay.kt`

Plain `FrameLayout` (same pattern as other overlays in `OverlayController`):
- White background (`#FFFFFF`)
- `setOnTouchListener { _, _ -> overlayController.dismissFlashlight(); true }` — any touch dismisses
- On show:
  1. Save current `Settings.System.SCREEN_BRIGHTNESS` to a field
  2. Write `Settings.System.SCREEN_BRIGHTNESS_MODE = 0` (manual)
  3. Write `Settings.System.SCREEN_BRIGHTNESS = 255`
- On dismiss:
  1. Restore previous brightness + mode
  2. Update `FlashlightState(on = false)` in `QsStateManager`

**`OverlayController`** gets `dismissFlashlight()` and a reference to `FlashlightOverlay`. When flashlight tile is tapped in screen mode:
1. Collapse QS panel (`commitCollapse()` or `dismissActive()`)
2. Show `FlashlightOverlay` (instant, no animation needed)

When hardware mode is on (user opt-in setting): use `CameraManager.setTorchMode()` as before, no overlay shown.

---

## Phase 8 — Volume Dialog

### New file: `res/layout/dialog_volume.xml`

`BottomSheetDialog` with per-stream rows. Each row: icon + `Slider` + percentage `TextView`.

Streams to show: Media (🎵), Ring (🔔), Alarm (⏰), Notification (💬).
Omit Voice Call stream (not user-facing on watches).

### New file: `overlay/quicksettings/VolumeDialogFragment.kt`

`BottomSheetDialogFragment`:
- On create: bind sliders to `AudioManager`, set `valueFrom=0`, `valueTo=streamMax`, `value=current`
- `addOnChangeListener`: `audioManager.setStreamVolume(stream, value.toInt(), 0)` on user-initiated change
- Register `VOLUME_CHANGED_ACTION` receiver to sync sliders if volume changes externally
- Unregister in `onDestroyView`

Called from `QuickSettingsOverlay` via:
```kotlin
VolumeDialogFragment().show(fragmentManager, "volume")
```
`QuickSettingsOverlay` needs to hold a `FragmentManager` reference (passed from `MainActivity`).

---

## Phase 9 — Tile Editor (Settings UI)

### New file: `settings/fragments/TileEditorFragment.kt`

`Fragment` (navigated to from QS settings):
- Two `RecyclerView` sections:
  1. **Active tiles** — ordered list, supports long-press drag-to-reorder via `ItemTouchHelper`, swipe-left removes tile (moves back to pool)
  2. **Available tiles** — all tiles not in active list (built-in + custom pool), tap to add to end of active list
- Changes saved immediately to `TileLayoutManager` on each move/add/remove
- FAB: "Add custom tile" → opens `CustomTileDialogFragment`

### New file: `settings/fragments/CustomTileDialogFragment.kt`

`DialogFragment` / `AlertDialog`:
1. Label field (EditText)
2. Mode selection: radio buttons "Launch App" | "Write Setting"
3. **If Launch App:** `Component name` field with hint `"com.package/.Activity"`. Optional "Pick from installed apps" button (shows package picker).
4. **If Write Setting:** Two fields: `Namespace` (dropdown: system/secure/global) + `Key` (EditText). Shows warning if namespace requires elevated privilege.
5. Save → `TileLayoutManager.addCustomTile()` + add to active list

### New file: `res/layout/fragment_tile_editor.xml`

Two `RecyclerView`s with a divider + section headers ("Active" / "Available"). Active list shows drag handle on right side.

### New file: `res/layout/item_tile_editor.xml`

Row: drag handle icon (active only) | tile icon | tile label | remove button (active) / add button (available).

---

## Phase 10 — Settings

### 10.1 `res/xml/preferences_quick_settings.xml`

```xml
<PreferenceScreen>

  <PreferenceCategory android:title="General">

    <SwitchPreferenceCompat
        android:key="qs_enabled"
        android:title="Enable Quick Settings"
        android:summary="Swipe down from watchface to open; disables gesture and all background listeners when off"
        android:defaultValue="true" />

    <SeekBarPreference
        android:key="qs_sensitivity"
        android:title="Gesture sensitivity"
        android:summary="Top-edge swipe zone height (dp)"
        android:min="20" android:max="200" android:defaultValue="60" />

    <ListPreference
        android:key="qs_tile_columns"
        android:title="Tile columns"
        android:summary="%s"
        android:entries="@array/tile_columns_entries"
        android:entryValues="@array/tile_columns_values"
        android:defaultValue="2" />
    <!-- entries: "2 columns (larger tiles)", "3 columns (compact)"
         values:  "2", "3" -->

  </PreferenceCategory>

  <PreferenceCategory android:title="Tiles">

    <!-- Navigates to TileEditorFragment -->
    <Preference
        android:key="qs_tile_editor"
        android:title="Arrange tiles"
        android:summary="Choose and reorder tiles shown in the panel"
        android:fragment="...TileEditorFragment" />

  </PreferenceCategory>

  <PreferenceCategory android:title="Flashlight">

    <SwitchPreferenceCompat
        android:key="qs_flashlight_hardware"
        android:title="Use hardware LED"
        android:summary="Off: turns screen white at max brightness (works on most watches). On: uses camera flash LED."
        android:defaultValue="false" />

  </PreferenceCategory>

  <PreferenceCategory android:title="Appearance">

    <SwitchPreferenceCompat
        android:key="qs_color_tiles"
        android:title="Color-coded tile states"
        android:summary="Tiles change color based on connection state"
        android:defaultValue="true" />

    <!-- Each of these opens a simple hex-input AlertDialog -->
    <Preference android:key="qs_color_connected"
        android:title="Connected color" android:summary="#379950" />
    <Preference android:key="qs_color_idle"
        android:title="Idle / connecting color" android:summary="#2444E3" />
    <Preference android:key="qs_color_disconnected"
        android:title="Disconnected color" android:summary="#AD7623" />
    <Preference android:key="qs_color_off"
        android:title="Off color" android:summary="#AAAAAA" />

  </PreferenceCategory>

  <PreferenceCategory android:title="Elevated Privileges">

    <ListPreference
        android:key="qs_privilege"
        android:title="Privilege mode"
        android:summary="%s"
        android:entries="@array/privilege_entries"
        android:entryValues="@array/privilege_values"
        android:defaultValue="none" />
    <!--
      none    = use direct APIs where possible, fall back to system panels
      shizuku = prefer Shizuku shell for restricted ops (WiFi/BT API29+, Data, Airplane, Location)
      root    = prefer root shell (same ops via su)
    -->

    <Preference
        android:key="qs_privilege_status"
        android:title="Privilege status"
        android:summary="Checking…"
        android:enabled="false" />

  </PreferenceCategory>

  <PreferenceCategory android:title="Accessibility">

    <!-- Shows status: "Enabled" or "Tap to enable" -->
    <Preference
        android:key="qs_accessibility_status"
        android:title="CloX Quick Actions"
        android:summary="Required for Power menu and Recents" />

  </PreferenceCategory>

</PreferenceScreen>
```

### 10.2 `QuickSettingsSettingsFragment.kt`

- Loads `preferences_quick_settings.xml`
- `onResume`: update `qs_privilege_status` and `qs_accessibility_status` summaries
- `qs_privilege = "shizuku"` selected → call `PrivilegeManager.requestShizukuPermission()` if not granted
- `qs_tile_columns` changed → `QuickSettingsOverlay.rebuildGrid()` on next panel open (change takes effect immediately if panel is open)
- Color preferences → show hex `AlertDialog` + update summary on save
- `qs_accessibility_status` click → open `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- Observe `qs_enabled` change → notify `OverlayController` to enable/disable QS gate

### 10.3 `res/xml/preferences_root.xml` — add entry

```xml
<Preference
    android:key="nav_quick_settings"
    android:title="Quick Settings"
    android:summary="Tiles, columns, gestures, flashlight, colors, privileges"
    android:fragment="com.ailife.clox.settings.fragments.QuickSettingsSettingsFragment" />
```

---

## Phase 11 — Integration

### 11.1 Disable QS gate in `OverlayController.kt`

```kotlin
private fun overlayFor(direction: SwipeDirection): View? {
    if (direction == SwipeDirection.DOWN &&
        !prefs.getBoolean(QsPrefs.QS_ENABLED, true)) return null
    return when (direction) { ... }
}
```

Also: skip `stateManager.attach()` entirely when QS is disabled.

### 11.2 Wire `QsStateManager` lifecycle

`OverlayController` owns a single `QsStateManager`.
- `commitDrag()` (panel fully open) → `stateManager.attach()`
- `commitCollapse()` / `dismissActive()` → `stateManager.detach()`

### 11.3 `QsPermissionHelper.kt`

```kotlin
object QsPermissionHelper {
    val RUNTIME_PERMS_ALWAYS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,       // API 31+
        Manifest.permission.BLUETOOTH_CONNECT,    // API 31+
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    val RUNTIME_PERMS_API33 = arrayOf(
        "android.permission.NEARBY_WIFI_DEVICES",
    )

    fun ensureWriteSettings(ctx: Context): Boolean  // Settings.System.canWrite()
    fun ensureDndAccess(ctx: Context): Boolean       // isNotificationPolicyAccessGranted()
    fun ensureAccessibilityEnabled(ctx: Context): Boolean  // checks CloXAccessibilityService.isRunning()
    fun requestAllRuntime(activity: Activity)        // requests RUNTIME_PERMS_ALWAYS + conditional 33+
}
```

Call `requestAllRuntime()` from `MainActivity.onResume()` once, gated by `QsPrefs.PERMISSIONS_REQUESTED`.

---

## Complete New File List

| File | Purpose |
|---|---|
| `qs/privilege/PrivilegeManager.kt` | Shizuku/root detection + level cache |
| `qs/privilege/ShizukuOperations.kt` | Privileged ops via Shizuku shell |
| `qs/privilege/RootOperations.kt` | Privileged ops via libsu |
| `qs/tile/TileId.kt` | Enum of all built-in tile identifiers |
| `qs/tile/TileDescriptor.kt` | Metadata for each tile (label, icon, etc.) |
| `qs/tile/TileRegistry.kt` | Canonical map of TileId → TileDescriptor |
| `qs/tile/CustomTileConfig.kt` | Data class for user-defined custom tiles |
| `qs/tile/TileLayoutManager.kt` | Persistence: load/save tile order + custom tiles |
| `qs/QsPrefs.kt` | SharedPreferences key constants |
| `qs/QsTileState.kt` | Sealed state data classes (one per tile type) |
| `qs/QsStateManager.kt` | Live state via StateFlows + receivers/callbacks |
| `qs/QsPermissionHelper.kt` | Runtime permission request helpers |
| `qs/CloXAccessibilityService.kt` | AccessibilityService for power menu + recents |
| `overlay/quicksettings/QsTilePageAdapter.kt` | RecyclerView.Adapter for ViewPager2; each item is a GridLayout page of tiles |
| `overlay/quicksettings/VolumeDialogFragment.kt` | BottomSheet: per-stream volume sliders |
| `overlay/quicksettings/FlashlightOverlay.kt` | Fullscreen white overlay (screen flashlight mode) |
| `settings/fragments/QuickSettingsSettingsFragment.kt` | QS settings screen |
| `settings/fragments/TileEditorFragment.kt` | Drag-to-reorder tile picker |
| `settings/fragments/CustomTileDialogFragment.kt` | Add/edit custom tile dialog |
| `res/xml/preferences_quick_settings.xml` | QS settings definitions |
| `res/xml/accessibility_service_config.xml` | AccessibilityService configuration |
| `res/layout/dialog_volume.xml` | Volume dialog layout |
| `res/layout/qs_tile_page.xml` | Single ViewPager2 page: a GridLayout that holds one page's worth of tiles |
| `res/drawable/qs_page_dot_selector.xml` | StateListDrawable: filled dot (selected) / outline dot (unselected) |
| `res/layout/fragment_tile_editor.xml` | Tile editor screen layout |
| `res/layout/item_tile_editor.xml` | Single row in tile editor list |

## Complete Modified File List

| File | Change |
|---|---|
| `AndroidManifest.xml` | Add permissions, Shizuku provider, accessibility service |
| `app/build.gradle` | Add Shizuku + libsu dependencies |
| `overlay/quicksettings/QuickSettingsOverlay.kt` | Full rewrite |
| `overlay/quicksettings/QsTileView.kt` | Add detail text, MD3 color theming |
| `overlay/OverlayController.kt` | Inject QsStateManager, gate on qs_enabled, add flashlight overlay |
| `res/layout/overlay_quick_settings.xml` | Full redesign (correct order: header→tiles→brightness→extra→bar) |
| `res/layout/view_qs_tile.xml` | Add detail text, MD3 FilledCard style |
| `res/xml/preferences_root.xml` | Add QS nav entry |
| `res/values/strings.xml` | All new string resources |

---

## Implementation Order

1. **Phase 1** — Manifest + dependencies (build only, no runtime changes)
2. **Phase 3** — `CloXAccessibilityService` (small, unblocks Power/Recents)
3. **Phase 2** — Privilege layer (`PrivilegeManager`, `ShizukuOperations`, `RootOperations`)
4. **Phase 4** — Tile system (`TileId`, `TileDescriptor`, `TileRegistry`, `CustomTileConfig`, `TileLayoutManager`)
5. **Phase 5** — State management (`QsPrefs`, `QsTileState`, `QsStateManager`)
6. **Phase 6** — Layout redesign (XML first, then `QsTileView`, then `QuickSettingsOverlay`)
7. **Phase 7** — `FlashlightOverlay` + wire into `OverlayController`
8. **Phase 8** — `VolumeDialogFragment` + layout
9. **Phase 9** — Tile editor UI (`TileEditorFragment`, `CustomTileDialogFragment`)
10. **Phase 10** — Settings screens + prefs XML + nav entry
11. **Phase 11** — Integration (`OverlayController` gate, `QsPermissionHelper`, `MainActivity` hooks)
12. **Testing** — each tile fallback on API 26, 29, 31, 33 emulators; custom tile round-trip; tile reorder persistence
