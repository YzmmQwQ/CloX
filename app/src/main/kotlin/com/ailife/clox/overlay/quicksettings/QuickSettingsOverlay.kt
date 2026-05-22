package com.ailife.clox.overlay.quicksettings

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.ailife.clox.R
import com.ailife.clox.aod.AodPrefs
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.qs.QsPrefs
import com.ailife.clox.qs.QsStateManager
import com.ailife.clox.qs.TileState
import com.ailife.clox.qs.privilege.PrivilegeLevel
import com.ailife.clox.qs.privilege.PrivilegeManager
import com.ailife.clox.qs.privilege.RootOperations
import com.ailife.clox.qs.privilege.ShizukuOperations
import com.ailife.clox.qs.tile.CustomTileMode
import com.ailife.clox.qs.tile.TileId
import com.ailife.clox.qs.tile.TileLayoutManager
import com.ailife.clox.rtos.RtosManager
import com.ailife.clox.rtos.RtosPrefs
import com.ailife.clox.settings.SettingsActivity
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.FrameLayout

class QuickSettingsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private lateinit var tilePager: ViewPager2
    private lateinit var pageIndicator: TabLayout
    private lateinit var brightnessSlider: Slider
    private lateinit var qsTime: TextView
    private lateinit var qsBatteryPct: TextView
    private lateinit var qsDate: TextView
    // blurBg removed — blur is now applied to the static blurLayer in activity_main.xml
    private lateinit var btnLauncherSettings: ImageButton
    private lateinit var btnRecents: ImageButton
    private lateinit var btnFlashlight: ImageButton
    private lateinit var btnPower: ImageButton
    private lateinit var btnSettings: ImageButton

    private var stateManager: QsStateManager? = null
    private var fragmentManager: FragmentManager? = null
    private var flashlightOverlay: FlashlightOverlay? = null
    private var onDismissRequest: (() -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var cameraId: String? = null
    private var flashlightOn = false
    private var tileAdapter: QsTilePageAdapter? = null

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_quick_settings, this, true)
        tilePager = findViewById(R.id.qsTilePager)
        pageIndicator = findViewById(R.id.qsPageIndicator)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        qsTime = findViewById(R.id.qsTime)
        qsBatteryPct = findViewById(R.id.qsBatteryPct)
        qsDate = findViewById(R.id.qsDate)
        // blurBg no longer inside overlay — see blurLayer in activity_main.xml
        btnLauncherSettings = findViewById(R.id.qsBtnLauncherSettings)
        btnRecents = findViewById(R.id.qsBtnRecents)
        btnFlashlight = findViewById(R.id.qsBtnFlashlight)
        btnPower = findViewById(R.id.qsBtnPower)
        btnSettings = findViewById(R.id.qsBtnSettings)

        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cm.cameraIdList.firstOrNull()
        } catch (_: Exception) {}

        setupBrightness()
        setupBottomBar()
        applyDynamicSizes()
    }

    private fun applyDynamicSizes() {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val density = dm.density

        // Status bar text: scale with screen width, cap at 22sp
        val statusTextSp = ((screenW / density) * 0.055f).coerceIn(14f, 22f)
        qsTime.textSize = statusTextSp
        qsBatteryPct.textSize = statusTextSp
        qsDate.textSize = statusTextSp * 0.85f

        // Bottom bar: 5 buttons fit in content width with equal space
        // Button size = content_width / 6 (leaves ~1/6 for spacing), min 36dp, max 52dp
        val btnSizePx = (screenW / 6f).coerceIn(36 * density, 52 * density).toInt()
        val btnPadPx = (btnSizePx * 0.18f).toInt()
        val cornerPx = (btnSizePx * 0.32f)  // ~32% = squircle approximation

        val buttons = listOf(btnLauncherSettings, btnRecents, btnFlashlight, btnPower, btnSettings)
        buttons.forEach { btn ->
            btn.layoutParams.width = btnSizePx
            btn.layoutParams.height = btnSizePx
            btn.setPadding(btnPadPx, btnPadPx, btnPadPx, btnPadPx)
            btn.requestLayout()
            // Squircle background: rounded rectangle with ~32% corner radius
            val squircle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(android.graphics.Color.argb(51, 255, 255, 255))
            }
            val ripple = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(80, 255, 255, 255)),
                squircle, squircle
            )
            btn.background = ripple
        }
    }

    fun init(sm: QsStateManager, fm: FragmentManager, flashOverlay: FlashlightOverlay, onDismiss: () -> Unit) {
        stateManager = sm
        fragmentManager = fm
        flashlightOverlay = flashOverlay
        onDismissRequest = onDismiss
    }

    /** Re-read state flows if the panel is currently open (called after permission grant). */
    fun refreshStateIfOpen() {
        if (visibility == android.view.View.VISIBLE) {
            stateManager?.refreshState()
        }
    }

    fun onPanelOpened() {
        val sm = stateManager ?: return
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        sm.attach()        // registers receivers on main; initial state reads on IO
        buildTileGrid()    // fast: defers VP2 adapter assignment via post{}
        applyBottomBarStyle()
        collectHeaderFlows(sm)
    }

    fun onPanelClosed() {
        stateManager?.detach()
        scope?.cancel()
        scope = null
        if (flashlightOn) {
            toggleFlashlightHardware(false)
        }
    }

    private fun buildTileGrid() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rows = prefs.getString(QsPrefs.TILE_ROWS, "2")?.toIntOrNull() ?: 2
        val columns = 2  // always 2 columns
        val activeTiles = TileLayoutManager.loadActiveTiles(context)
        val pages = TileLayoutManager.paginateTiles(activeTiles, rows)
        val sm = stateManager ?: return
        val sc = scope ?: return

        // Tile height: 64dp minimum per row. VP2 = rows × tileH + row margins.
        // Deferred via post{} so layout params apply after the view is fully attached.
        val density = context.resources.displayMetrics.density
        val tileH = (64 * density).toInt()       // 64dp per tile row
        val rowMargin = (10 * density).toInt()    // 5dp top + 5dp bottom per tile
        val pagerH = rows * (tileH + rowMargin)

        val adapter = QsTilePageAdapter(pages, columns, rows, tileH, sm, sc,
            onTileClick = { tileId, tileView -> handleTileClick(tileId, tileView) },
            onTileLongClick = { tileId -> handleTileLongClick(tileId) }
        )

        // Defer adapter + layout to the next frame so this call returns immediately
        // and doesn't block the spring animation that was just committed.
        tilePager.post {
            tileAdapter = adapter
            tilePager.adapter = adapter
            tilePager.layoutParams = tilePager.layoutParams.apply { height = pagerH }
            tilePager.requestLayout()

            if (pages.size > 1) {
                pageIndicator.visibility = View.VISIBLE
                TabLayoutMediator(pageIndicator, tilePager) { _, _ -> }.attach()
            } else {
                pageIndicator.visibility = View.GONE
            }
        }
    }

    private fun collectHeaderFlows(sm: QsStateManager) {
        val sc = scope ?: return
        sm.time.onEach { qsTime.text = it }.launchIn(sc)
        sm.battery.onEach { state ->
            qsBatteryPct.text = if (state.charging) "${state.level}%⚡" else "${state.level}%"
        }.launchIn(sc)
        qsDate.text = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date())
    }

    private fun setupBrightness() {
        brightnessSlider.valueFrom = 0f
        brightnessSlider.valueTo = 255f
        brightnessSlider.value = getBrightness().toFloat()
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && QsPermissionHelper.ensureWriteSettings(context)) setBrightness(value.toInt())
        }
    }

    private fun applyBottomBarStyle() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val solid = prefs.getString(QsPrefs.TILE_STYLE, QsPrefs.TILE_STYLE_STROKE) == QsPrefs.TILE_STYLE_SOLID
        val bgRes = if (solid) R.drawable.qs_btn_bg_solid else R.drawable.qs_btn_bg
        listOf(btnLauncherSettings, btnRecents, btnFlashlight, btnPower, btnSettings)
            .forEach { it.setBackgroundResource(bgRes) }
    }

    private fun setupBottomBar() {
        applyBottomBarStyle()
        btnLauncherSettings.setOnClickListener {
            context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        btnRecents.setOnClickListener {
            if (!CloXAccessibilityService.triggerRecents())
                QsPermissionHelper.ensureAccessibilityEnabled(context)
        }
        btnFlashlight.setOnClickListener { handleFlashlight(null) }
        btnPower.setOnClickListener {
            if (!CloXAccessibilityService.triggerPowerMenu()) {
                QsPermissionHelper.ensureAccessibilityEnabled(context)
            }
        }
        btnSettings.setOnClickListener {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {}
        }
    }

    // ── Tile click handler ───────────────────────────────────────────────────

    // Async tiles: need to wait for system callback before state updates (show loading ring)
    private val asyncTiles = setOf(
        TileId.WIFI.key, TileId.BLUETOOTH.key, TileId.DATA.key,
        TileId.LOCATION.key, TileId.AIRPLANE.key, TileId.HOTSPOT.key
    )

    private fun handleTileClick(tileId: String, view: QsTileView) {
        // Clear pending for everything except async system API tiles and custom tiles are sync
        if (tileId !in asyncTiles) view.isPending = false
        val priv = PrivilegeManager.level(context)
        when (tileId) {
            TileId.WIFI.key       -> handleWifi(priv)
            TileId.DATA.key       -> handleData(priv)
            TileId.BLUETOOTH.key  -> handleBluetooth(priv)
            TileId.VOLUME.key     -> openVolumeDialog()  // short press = in-app slider dialog
            TileId.DND.key        -> handleDnd()
            TileId.ROTATE.key     -> handleRotate()
            TileId.FLASHLIGHT.key -> handleFlashlight(view)
            TileId.LOCATION.key   -> handleLocation(priv)
            TileId.AOD.key        -> handleAod()
            TileId.AIRPLANE.key   -> handleAirplane(priv)
            TileId.HOTSPOT.key    -> handleHotspot(priv)
            TileId.RTOS_MODE.key  -> handleRtosMode()
            else -> {
                handleCustomTile(tileId)
                // Refresh custom WRITE_SETTINGS tile state immediately after toggling
                tileAdapter?.applyCustomTileState(view, tileId, context)
            }
        }
    }

    // ── Long press: open "more" settings for each tile (mirrors old app behavior) ──

    private fun handleTileLongClick(tileId: String) {
        when (tileId) {
            TileId.VOLUME.key -> cycleRingerMode()  // long press = cycle ringer (old app behavior)
            TileId.HOTSPOT.key -> openHotspotSettings()
            else -> {
                val intent = when (tileId) {
                    TileId.WIFI.key      -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    TileId.DATA.key      -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                                "android.settings.DATA_USAGE_SETTINGS"
                                            else "android.settings.DATA_ROAMING_SETTINGS")
                    TileId.BLUETOOTH.key -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    TileId.DND.key       -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    TileId.LOCATION.key  -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    TileId.AIRPLANE.key  -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    else                 -> Intent(Settings.ACTION_SETTINGS)
                }
                try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) {}
            }
        }
    }

    // Cycle ringer: Normal → Vibrate → Silent (mirror old app's setSoundMode)
    private fun cycleRingerMode() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) { QsPermissionHelper.ensureDndAccess(context); return }
        am.ringerMode = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_NORMAL  -> android.media.AudioManager.RINGER_MODE_VIBRATE
            android.media.AudioManager.RINGER_MODE_VIBRATE -> android.media.AudioManager.RINGER_MODE_SILENT
            else                                           -> android.media.AudioManager.RINGER_MODE_NORMAL
        }
    }

    // ── Individual tile handlers ─────────────────────────────────────────────

    private fun handleWifi(priv: PrivilegeLevel) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
        val current = wm.isWifiEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION") wm.isWifiEnabled = !current; return
        }
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setWifiEnabled(!current)) openWifiPanel() }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setWifiEnabled(!current)) openWifiPanel() }
            PrivilegeLevel.NONE    -> openWifiPanel()
        }
    }

    private fun openWifiPanel() = try {
        context.startActivity(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleData(priv: PrivilegeLevel) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val current = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setMobileDataEnabled(!current)) openDataPanel() }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setMobileDataEnabled(!current)) openDataPanel() }
            PrivilegeLevel.NONE    -> openDataPanel()
        }
    }

    private fun openDataPanel() = try {
        context.startActivity(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "android.settings.panel.action.INTERNET_CONNECTIVITY" else Settings.ACTION_DATA_ROAMING_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    @Suppress("DEPRECATION")
    private fun handleBluetooth(priv: PrivilegeLevel) {
        val bt = BluetoothAdapter.getDefaultAdapter() ?: return
        val current = bt.isEnabled
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            if (current) bt.disable() else bt.enable(); return
        }
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setBluetoothEnabled(!current)) openBtPanel(current) }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setBluetoothEnabled(!current)) openBtPanel(current) }
            PrivilegeLevel.NONE    -> openBtPanel(current)
        }
    }

    private fun openBtPanel(@Suppress("UNUSED_PARAMETER") currentlyEnabled: Boolean) = try {
        // Open Bluetooth settings directly — REQUEST_ENABLE/DISABLE can resolve to internet settings on some devices
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun openVolumeDialog() {
        val fm = fragmentManager ?: return
        VolumeDialogFragment().show(fm, "volume")
    }

    private fun handleDnd() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) { QsPermissionHelper.ensureDndAccess(context); return }
        val current = nm.currentInterruptionFilter
        nm.setInterruptionFilter(
            if (current == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    private fun handleRotate() {
        if (!QsPermissionHelper.ensureWriteSettings(context)) return
        val current = Settings.System.getInt(context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 1)
        Settings.System.putInt(context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, if (current == 1) 0 else 1)
    }

    private fun handleFlashlight(view: QsTileView?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val useHardware = prefs.getBoolean(QsPrefs.FLASHLIGHT_HARDWARE, false)
        if (useHardware) {
            flashlightOn = !flashlightOn
            toggleFlashlightHardware(flashlightOn)
            stateManager?.setFlashlightState(flashlightOn)
        } else {
            // Screen flashlight
            onDismissRequest?.invoke()
            flashlightOverlay?.show()
        }
    }

    private fun toggleFlashlightHardware(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraId ?: return
            cm.setTorchMode(id, on)
        } catch (_: Exception) {}
    }

    private fun handleAod() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getBoolean(AodPrefs.KEY_ENABLED, false)
        prefs.edit().putBoolean(AodPrefs.KEY_ENABLED, !current).apply()
    }

    private fun handleLocation(priv: PrivilegeLevel) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else true
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setLocationEnabled(!current)) openLocationSettings() }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setLocationEnabled(context, !current)) openLocationSettings() }
            PrivilegeLevel.NONE    -> openLocationSettings()
        }
    }

    private fun openLocationSettings() = try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleAirplane(priv: PrivilegeLevel) {
        val current = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setAirplaneModeEnabled(!current)) openAirplaneSettings() }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setAirplaneModeEnabled(context, !current)) openAirplaneSettings() }
            PrivilegeLevel.NONE    -> openAirplaneSettings()
        }
    }

    private fun openAirplaneSettings() = try {
        context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleHotspot(priv: PrivilegeLevel) {
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) { if (!ShizukuOperations.setHotspotEnabled(true)) openHotspotSettings() }
            PrivilegeLevel.ROOT    -> scope?.launch(Dispatchers.IO) { if (!RootOperations.setHotspotEnabled(true)) openHotspotSettings() }
            PrivilegeLevel.NONE    -> openHotspotSettings()
        }
    }

    private fun handleRtosMode() {
        if (!RtosPrefs.isEnabled(context) || RtosPrefs.passwordHash(context) == null) {
            Toast.makeText(context, "RTOS mode not configured", Toast.LENGTH_SHORT).show()
            return
        }
        onDismissRequest?.invoke()   // close QS panel first
        RtosManager.enter(context)
    }

    private fun openHotspotSettings() = try {
        context.startActivity(Intent().apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = android.content.ComponentName("com.android.settings",
                "com.android.settings.Settings\$WifiTetherSettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {
        try { context.startActivity(Intent("android.settings.WIRELESS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun handleCustomTile(tileId: String) {
        if (!tileId.startsWith("custom:")) return
        val uuid = tileId.removePrefix("custom:")
        val config = TileLayoutManager.loadCustomTiles(context).firstOrNull { it.uuid == uuid } ?: return
        when (config.mode) {
            CustomTileMode.LAUNCH_ACTIVITY -> {
                val component = config.activityComponent ?: return
                try {
                    context.startActivity(Intent().apply {
                        setComponent(android.content.ComponentName.unflattenFromString(component) ?: return)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "App not found: $component", Toast.LENGTH_SHORT).show()
                }
            }
            CustomTileMode.WRITE_SETTINGS -> {
                val ns = config.settingsNamespace ?: return
                val key = config.settingsKey ?: return
                val cr = context.contentResolver
                val current = try {
                    when (ns) {
                        "system" -> Settings.System.getInt(cr, key, 0)
                        "secure" -> Settings.Secure.getInt(cr, key, 0)
                        "global" -> Settings.Global.getInt(cr, key, 0)
                        else -> 0
                    }
                } catch (_: Exception) { 0 }
                val newVal = (1 - current).coerceIn(0, 1).toString()
                val priv = PrivilegeManager.level(context)
                when {
                    ns == "system" && QsPermissionHelper.ensureWriteSettings(context) ->
                        Settings.System.putInt(cr, key, newVal.toInt())
                    priv == PrivilegeLevel.SHIZUKU ->
                        ShizukuOperations.writeSettings(ns, key, newVal)
                    priv == PrivilegeLevel.ROOT ->
                        scope?.launch(Dispatchers.IO) { RootOperations.writeSettings(ns, key, newVal) }
                    else -> Toast.makeText(context, "Requires elevated privileges for $ns settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Brightness helpers ───────────────────────────────────────────────────

    private fun getBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) { 128 }

    private fun setBrightness(value: Int) {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }
}
