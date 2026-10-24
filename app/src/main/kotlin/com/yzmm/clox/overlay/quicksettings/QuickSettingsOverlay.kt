package com.yzmm.clox.overlay.quicksettings

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yzmm.clox.R
import com.yzmm.clox.aod.AodPrefs
import com.yzmm.clox.background.BackgroundAppsActivity
import com.yzmm.clox.overlay.notifications.CloXNotificationListenerService
import com.yzmm.clox.qs.CloXAccessibilityService
import com.yzmm.clox.qs.QsPermissionHelper
import com.yzmm.clox.qs.QsPrefs
import com.yzmm.clox.qs.QsStateManager
import com.yzmm.clox.qs.privilege.PrivilegeLevel
import com.yzmm.clox.qs.privilege.PrivilegeManager
import com.yzmm.clox.qs.privilege.RootOperations
import com.yzmm.clox.qs.privilege.ShizukuOperations
import com.yzmm.clox.qs.tile.CustomTileMode
import com.yzmm.clox.qs.tile.TileId
import com.yzmm.clox.qs.tile.TileLayoutManager
import com.yzmm.clox.rtos.RtosManager
import com.yzmm.clox.rtos.RtosPrefs
import com.yzmm.clox.settings.SettingsActivity
import com.yzmm.clox.ui.theme.CloXWearTheme
import com.yzmm.clox.util.safeStartActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickSettingsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val composeView = ComposeView(ctx).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val mediaController = QsMediaController(ctx)
    private val panelRevision = mutableIntStateOf(0)

    private var stateManager: QsStateManager? = null
    private var fragmentManager: FragmentManager? = null
    private var flashlightOverlay: FlashlightOverlay? = null
    private var onDismissRequest: (() -> Unit)? = null
    private var composeOwner: OverlayComposeOwner? = null

    /** Global-QS overlay has no in-view FlashlightOverlay; it supplies this to show a
     *  screen flashlight via a dedicated overlay window instead. */
    var softwareFlashlightHandler: (() -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var cameraId: String? = null
    private var flashlightOn = false

    init {
        addView(composeView)
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cm.cameraIdList.firstOrNull()
        } catch (_: Exception) {}
    }

    fun init(
        sm: QsStateManager,
        fm: FragmentManager?,
        flashOverlay: FlashlightOverlay?,
        onDismiss: () -> Unit,
    ) {
        stateManager = sm
        fragmentManager = fm
        flashlightOverlay = flashOverlay
        onDismissRequest = onDismiss

        // The global overlay lives in a WindowManager view hosted by a Service, so it has no
        // ViewTree owners. Give Compose a minimal owner in that case; in the activity the
        // existing lifecycle owners are reused.
        if (composeView.findViewTreeLifecycleOwner() == null) {
            val owner = OverlayComposeOwner()
            composeOwner = owner
            composeView.setViewTreeLifecycleOwner(owner)
            composeView.setViewTreeSavedStateRegistryOwner(owner)
            composeView.setViewTreeViewModelStoreOwner(owner)
        }

        composeView.setContent {
            CloXWearTheme {
                QuickSettingsPanel(
                    stateManager = sm,
                    mediaController = mediaController,
                    revision = panelRevision.intValue,
                    onTileClick = ::handleTileClick,
                    onBrightnessChange = ::handleBrightnessChange,
                    onVolumeChange = ::handleVolumeChange,
                    onTimeClick = ::openAlarms,
                    onDateClick = ::openCalendar,
                    onBatteryClick = ::openBatterySettings,
                    onAction = ::handlePanelAction,
                )
            }
        }
    }

    /** Re-read state flows if the panel is currently open (called after permission grant). */
    fun refreshStateIfOpen() {
        if (visibility == View.VISIBLE) {
            stateManager?.refreshState()
            mediaController.refresh()
        }
    }

    fun onPanelOpened() {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        stateManager?.attach()
        stateManager?.refreshState()
        val rebinding = ensureNotificationListener()
        mediaController.start()
        if (rebinding) {
            postDelayed({
                if (visibility == View.VISIBLE) mediaController.refresh()
            }, 700)
        }
        panelRevision.intValue++
    }

    fun onPanelClosed() {
        stateManager?.detach()
        mediaController.stop()
        scope?.cancel()
        scope = null
        if (flashlightOn) {
            toggleFlashlightHardware(false)
        }
    }

    // ── Panel actions ────────────────────────────────────────────────────────

    /** The media card needs a live notification listener; rebind an enabled-but-dead one. */
    private fun ensureNotificationListener(): Boolean {
        if (CloXNotificationListenerService.isRunning()) {
            return false
        }
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners")
        val component = ComponentName(
            context, CloXNotificationListenerService::class.java).flattenToString()
        if (enabled?.contains(component) != true) return false
        CloXNotificationListenerService.requestRebind(context)
        return true
    }

    private fun handlePanelAction(action: QsPanelAction) {
        when (action) {
            QsPanelAction.SETTINGS -> context.safeStartActivity(
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            QsPanelAction.RECENTS -> BackgroundAppsActivity.start(context)
            QsPanelAction.FLASHLIGHT -> handleFlashlight()
            QsPanelAction.POWER -> {
                if (!CloXAccessibilityService.triggerPowerMenu()) {
                    QsPermissionHelper.ensureAccessibilityEnabled(context)
                }
            }
            QsPanelAction.SYSTEM_SETTINGS -> try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {}
        }
    }

    private fun handleBrightnessChange(value: Int) {
        if (!Settings.System.canWrite(context)) {
            QsPermissionHelper.ensureWriteSettings(context)
            return
        }
        setBrightness(value)
    }

    private fun handleVolumeChange(value: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        } catch (_: SecurityException) {}
    }

    private fun openAlarms() {
        try {
            context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun openCalendar() {
        try {
            val uri = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build()
            context.startActivity(Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_APP_CALENDAR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun openBatterySettings() {
        try {
            context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    // ── Tile click handler ───────────────────────────────────────────────────

    private fun handleTileClick(tileId: String) {
        val priv = PrivilegeManager.level(context)
        when (tileId) {
            TileId.WIFI.key -> handleWifi(priv)
            TileId.DATA.key -> handleData(priv)
            TileId.BLUETOOTH.key -> handleBluetooth(priv)
            TileId.VOLUME.key -> openVolumeDialog()
            TileId.DND.key -> handleDnd()
            TileId.ROTATE.key -> handleRotate()
            TileId.FLASHLIGHT.key -> handleFlashlight()
            TileId.LOCATION.key -> handleLocation(priv)
            TileId.AOD.key -> handleAod()
            TileId.AIRPLANE.key -> handleAirplane(priv)
            TileId.HOTSPOT.key -> handleHotspot(priv)
            TileId.BATTERY_SAVER.key -> handleBatterySaver(priv)
            TileId.SYNC.key -> handleSync()
            TileId.RTOS_MODE.key -> handleRtosMode()
            else -> handleCustomTile(tileId)
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
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setWifiEnabled(!current)) openWifiPanel()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setWifiEnabled(!current)) openWifiPanel()
            }
            PrivilegeLevel.NONE -> openWifiPanel()
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
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setMobileDataEnabled(!current)) openDataPanel()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setMobileDataEnabled(!current)) openDataPanel()
            }
            PrivilegeLevel.NONE -> openDataPanel()
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
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setBluetoothEnabled(!current)) openBtPanel()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setBluetoothEnabled(!current)) openBtPanel()
            }
            PrivilegeLevel.NONE -> openBtPanel()
        }
    }

    private fun openBtPanel() = try {
        // Open Bluetooth settings directly — REQUEST_ENABLE/DISABLE can resolve to internet settings on some devices
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun openVolumeDialog() {
        val fm = fragmentManager
        if (fm != null) {
            VolumeDialogFragment().show(fm, "volume")
            return
        }
        // Overlay mode (global QS) has no FragmentManager — show the system volume UI instead.
        onDismissRequest?.invoke()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
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

    private fun handleFlashlight() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val useHardware = prefs.getBoolean(QsPrefs.FLASHLIGHT_HARDWARE, false)
        if (useHardware) {
            flashlightOn = !flashlightOn
            toggleFlashlightHardware(flashlightOn)
            stateManager?.setFlashlightState(flashlightOn)
        } else {
            // Screen flashlight
            onDismissRequest?.invoke()
            if (flashlightOverlay != null) flashlightOverlay?.show()
            else softwareFlashlightHandler?.invoke()
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
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setLocationEnabled(!current)) openLocationSettings()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setLocationEnabled(context, !current)) openLocationSettings()
            }
            PrivilegeLevel.NONE -> openLocationSettings()
        }
    }

    private fun openLocationSettings() = try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleAirplane(priv: PrivilegeLevel) {
        val current = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setAirplaneModeEnabled(!current)) openAirplaneSettings()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setAirplaneModeEnabled(context, !current)) openAirplaneSettings()
            }
            PrivilegeLevel.NONE -> openAirplaneSettings()
        }
    }

    private fun openAirplaneSettings() = try {
        context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleHotspot(priv: PrivilegeLevel) {
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setHotspotEnabled(true)) openHotspotSettings()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setHotspotEnabled(true)) openHotspotSettings()
            }
            PrivilegeLevel.NONE -> openHotspotSettings()
        }
    }

    private fun handleBatterySaver(priv: PrivilegeLevel) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val current = pm.isPowerSaveMode
        when (priv) {
            PrivilegeLevel.SHIZUKU -> scope?.launch(Dispatchers.IO) {
                if (!ShizukuOperations.setBatterySaverEnabled(!current)) openBatterySaverSettings()
            }
            PrivilegeLevel.ROOT -> scope?.launch(Dispatchers.IO) {
                if (!RootOperations.setBatterySaverEnabled(!current)) openBatterySaverSettings()
            }
            PrivilegeLevel.NONE -> openBatterySaverSettings()
        }
    }

    private fun openBatterySaverSettings() = try {
        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleSync() {
        val current = try {
            ContentResolver.getMasterSyncAutomatically()
        } catch (_: SecurityException) {
            openSyncSettings()
            return
        }
        try {
            ContentResolver.setMasterSyncAutomatically(!current)
        } catch (_: SecurityException) {
            openSyncSettings()
        }
    }

    private fun openSyncSettings() = try {
        context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    private fun handleRtosMode() {
        if (!RtosPrefs.isEnabled(context) || RtosPrefs.passwordHash(context) == null) {
            Toast.makeText(context, R.string.toast_qs_rtos_not_configured, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, context.getString(R.string.toast_qs_app_not_found, component), Toast.LENGTH_SHORT).show()
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
                    else -> Toast.makeText(context, context.getString(R.string.toast_qs_elevated_required, ns), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Brightness helpers ───────────────────────────────────────────────────

    private fun setBrightness(value: Int) {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        composeOwner?.destroy()
        composeOwner = null
    }
}

/** Minimal ViewTree owners for Compose when the overlay is hosted by a Service window. */
private class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        viewModelStore.clear()
    }
}
