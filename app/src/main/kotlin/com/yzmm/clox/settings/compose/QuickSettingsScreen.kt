package com.yzmm.clox.settings.compose

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.overlay.global.GlobalOverlayPrefs
import com.yzmm.clox.overlay.global.GlobalOverlayService
import com.yzmm.clox.qs.CloXAccessibilityService
import com.yzmm.clox.qs.QsPrefs
import com.yzmm.clox.qs.privilege.PrivilegeLevel
import com.yzmm.clox.qs.privilege.PrivilegeManager
import com.yzmm.clox.qs.privilege.RestrictedSettingsHelper

@Composable
fun QuickSettingsScreen() {
    val ctx = LocalContext.current
    val navigate = LocalSettingsNavigator.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }

    val tileRowEntries = stringArrayResource(R.array.tile_rows_entries)
    val tileRowValues = stringArrayResource(R.array.tile_rows_values)
    val tileStyleEntries = stringArrayResource(R.array.qs_tile_style_entries)
    val tileStyleValues = stringArrayResource(R.array.qs_tile_style_values)
    val privilegeEntries = stringArrayResource(R.array.privilege_entries)
    val privilegeValues = stringArrayResource(R.array.privilege_values)
    val sbEntries = stringArrayResource(R.array.status_bar_overlay_mode_entries)
    val sbValues = stringArrayResource(R.array.status_bar_overlay_mode_values)

    var enabled by remember { mutableStateOf(prefs.getBoolean(QsPrefs.QS_ENABLED, true)) }
    var sensitivity by remember { mutableStateOf(prefs.getInt("qs_sensitivity", 60).coerceIn(20, 200)) }
    var tileRows by remember { mutableStateOf(prefs.getString(QsPrefs.TILE_ROWS, "2") ?: "2") }
    var flashlightHw by remember { mutableStateOf(prefs.getBoolean(QsPrefs.FLASHLIGHT_HARDWARE, false)) }
    var tileStyle by remember {
        mutableStateOf(prefs.getString(QsPrefs.TILE_STYLE, QsPrefs.TILE_STYLE_SOLID)
            ?: QsPrefs.TILE_STYLE_SOLID)
    }
    var colorTiles by remember { mutableStateOf(prefs.getBoolean(QsPrefs.COLORED_TILES, false)) }
    var systemColor by remember { mutableStateOf(prefs.getBoolean(QsPrefs.USE_SYSTEM_COLOR, false)) }
    var colorConnected by remember {
        mutableStateOf(prefs.getString(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED)
            ?: QsPrefs.DEFAULT_COLOR_CONNECTED)
    }
    var colorIdle by remember {
        mutableStateOf(prefs.getString(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE)
            ?: QsPrefs.DEFAULT_COLOR_IDLE)
    }
    var colorDisconnected by remember {
        mutableStateOf(prefs.getString(QsPrefs.COLOR_DISCONNECTED, QsPrefs.DEFAULT_COLOR_DISCONNECTED)
            ?: QsPrefs.DEFAULT_COLOR_DISCONNECTED)
    }
    var colorOff by remember {
        mutableStateOf(prefs.getString(QsPrefs.COLOR_OFF, QsPrefs.DEFAULT_COLOR_OFF)
            ?: QsPrefs.DEFAULT_COLOR_OFF)
    }
    var privilege by remember { mutableStateOf(prefs.getString(QsPrefs.PRIVILEGE_MODE, "none") ?: "none") }

    var globalQs by remember {
        mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_ENABLED, false))
    }
    var edgeSwipe by remember {
        mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_EDGE_SWIPE, true))
    }
    var autoReplace by remember {
        mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_AUTO_REPLACE, true))
    }
    var edgeArea by remember {
        mutableStateOf(
            prefs.getInt(GlobalOverlayPrefs.GLOBAL_QS_EDGE_AREA, GlobalOverlayPrefs.DEFAULT_EDGE_AREA)
                .coerceIn(8, 120)
        )
    }
    var sbMode by remember {
        mutableStateOf(
            prefs.getString(GlobalOverlayPrefs.STATUS_BAR_OVERLAY_MODE, GlobalOverlayPrefs.SB_HIDDEN)
                ?: GlobalOverlayPrefs.SB_HIDDEN
        )
    }
    var sbInset by remember {
        mutableStateOf(
            prefs.getInt(GlobalOverlayPrefs.SB_INSET_PCT, GlobalOverlayPrefs.DEFAULT_INSET_PCT)
                .coerceIn(0, 40)
        )
    }

    var sbClock by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_CLOCK, true)) }
    var sbBattery by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_BATTERY, true)) }
    var sbWifi by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_WIFI, true)) }
    var sbData by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_DATA, true)) }
    var sbBluetooth by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_BLUETOOTH, true)) }
    var sbSpeed by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_SPEED, false)) }
    var sbNotifs by remember { mutableStateOf(prefs.getBoolean(GlobalOverlayPrefs.SB_ITEM_NOTIFS, false)) }

    var showTileRows by remember { mutableStateOf(false) }
    var showTileStyle by remember { mutableStateOf(false) }
    var showPrivilege by remember { mutableStateOf(false) }
    var showSbMode by remember { mutableStateOf(false) }
    var colorPick by remember { mutableStateOf<ColorPick?>(null) }

    var privilegeStatus by remember { mutableStateOf("") }
    var accessibilityStatus by remember { mutableStateOf("") }

    fun refreshStatuses() {
        privilegeStatus = ctx.getString(when (PrivilegeManager.level(ctx)) {
            PrivilegeLevel.SHIZUKU -> R.string.qs_privilege_shizuku
            PrivilegeLevel.ROOT -> R.string.qs_privilege_root
            PrivilegeLevel.NONE -> R.string.qs_privilege_none
        })
        accessibilityStatus = ctx.getString(
            if (CloXAccessibilityService.isRunning()) R.string.qs_accessibility_enabled
            else R.string.qs_accessibility_disabled)
    }
    refreshStatuses()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatuses()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.qs_title)) {
        SectionHeader(res(R.string.qs_setting_general_category))
        SettingsSwitch(
            title = res(R.string.qs_enabled_title),
            summary = res(R.string.qs_enabled_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean(QsPrefs.QS_ENABLED, it).apply()
            },
        )
        SettingsSlider(
            title = res(R.string.qs_sensitivity_title),
            value = sensitivity,
            range = 20..200,
            valueText = "$sensitivity",
            onChange = {
                sensitivity = it
                prefs.edit().putInt("qs_sensitivity", it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.qs_tile_rows_title),
            summary = tileRowEntries.getOrElse(tileRowValues.indexOf(tileRows)) { tileRows },
        ) { showTileRows = true }

        SectionHeader(res(R.string.qs_setting_tiles_category))
        SettingsButton(
            title = res(R.string.qs_tile_editor_title),
            summary = res(R.string.qs_tile_editor_summary),
        ) { navigate(SettingsRoutes.TILE_EDITOR) }

        SectionHeader(res(R.string.qs_setting_flashlight_category))
        SettingsSwitch(
            title = res(R.string.qs_flashlight_hw_title),
            summary = res(R.string.qs_flashlight_hw_summary),
            checked = flashlightHw,
            onCheckedChange = {
                flashlightHw = it
                prefs.edit().putBoolean(QsPrefs.FLASHLIGHT_HARDWARE, it).apply()
            },
        )

        SectionHeader(res(R.string.qs_setting_appearance_category))
        SettingsButton(
            title = res(R.string.qs_setting_tile_style_title),
            summary = tileStyleEntries.getOrElse(tileStyleValues.indexOf(tileStyle)) { tileStyle },
        ) { showTileStyle = true }
        SettingsSwitch(
            title = res(R.string.qs_color_tiles_title),
            checked = colorTiles,
            onCheckedChange = {
                colorTiles = it
                prefs.edit().putBoolean(QsPrefs.COLORED_TILES, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.qs_system_color_title),
            summary = res(R.string.qs_system_color_summary),
            checked = systemColor,
            onCheckedChange = {
                systemColor = it
                prefs.edit().putBoolean(QsPrefs.USE_SYSTEM_COLOR, it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.qs_color_connected_title),
            summary = colorConnected,
            enabled = !systemColor,
        ) { colorPick = ColorPick(QsPrefs.COLOR_CONNECTED, colorConnected) }
        SettingsButton(
            title = res(R.string.qs_color_idle_title),
            summary = colorIdle,
            enabled = !systemColor,
        ) { colorPick = ColorPick(QsPrefs.COLOR_IDLE, colorIdle) }
        SettingsButton(
            title = res(R.string.qs_color_disconnected_title),
            summary = colorDisconnected,
            enabled = !systemColor,
        ) { colorPick = ColorPick(QsPrefs.COLOR_DISCONNECTED, colorDisconnected) }
        SettingsButton(
            title = res(R.string.qs_color_off_title),
            summary = colorOff,
            enabled = !systemColor,
        ) { colorPick = ColorPick(QsPrefs.COLOR_OFF, colorOff) }

        SectionHeader(res(R.string.qs_setting_elevated_privileges_category))
        SettingsButton(
            title = res(R.string.qs_privilege_title),
            summary = privilegeEntries.getOrElse(privilegeValues.indexOf(privilege)) { privilege },
        ) { showPrivilege = true }
        SettingsButton(
            title = res(R.string.qs_privilege_status_title),
            summary = privilegeStatus,
        ) { }

        SectionHeader(res(R.string.qs_setting_accessibility_category))
        SettingsButton(
            title = res(R.string.qs_accessibility_status_title),
            summary = accessibilityStatus,
        ) {
            RestrictedSettingsHelper.openGrantScreen(
                ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        SectionHeader(res(R.string.global_qs_category))
        SettingsSwitch(
            title = res(R.string.global_qs_enabled_title),
            summary = res(R.string.global_qs_enabled_summary),
            checked = globalQs,
            onCheckedChange = { checked ->
                if (checked && !Settings.canDrawOverlays(ctx)) {
                    globalQs = false
                    Toast.makeText(ctx, R.string.global_qs_permission_needed, Toast.LENGTH_LONG).show()
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }
                } else {
                    globalQs = checked
                    prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_ENABLED, checked).apply()
                    GlobalOverlayService.refresh(ctx)
                }
            },
        )
        SettingsSwitch(
            title = res(R.string.global_qs_edge_title),
            summary = res(R.string.global_qs_edge_summary),
            checked = edgeSwipe,
            onCheckedChange = {
                edgeSwipe = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_EDGE_SWIPE, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSlider(
            title = res(R.string.global_qs_edge_area_title),
            value = edgeArea,
            range = 8..120,
            valueText = "${edgeArea}dp",
            onChange = {
                edgeArea = it
                prefs.edit().putInt(GlobalOverlayPrefs.GLOBAL_QS_EDGE_AREA, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.global_qs_replace_title),
            summary = res(R.string.global_qs_replace_summary),
            checked = autoReplace,
            onCheckedChange = {
                autoReplace = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_AUTO_REPLACE, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsButton(
            title = res(R.string.status_bar_overlay_title),
            summary = sbEntries.getOrElse(sbValues.indexOf(sbMode)) { sbMode },
        ) { showSbMode = true }
        SettingsSlider(
            title = res(R.string.sb_inset_title),
            value = sbInset,
            range = 0..40,
            valueText = "$sbInset%",
            onChange = {
                sbInset = it
                prefs.edit().putInt(GlobalOverlayPrefs.SB_INSET_PCT, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )

        SectionHeader(res(R.string.sb_items_category))
        SettingsSwitch(
            title = res(R.string.sb_item_clock),
            checked = sbClock,
            onCheckedChange = {
                sbClock = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_CLOCK, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_battery),
            checked = sbBattery,
            onCheckedChange = {
                sbBattery = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_BATTERY, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_wifi),
            checked = sbWifi,
            onCheckedChange = {
                sbWifi = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_WIFI, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_data),
            checked = sbData,
            onCheckedChange = {
                sbData = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_DATA, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_bluetooth),
            checked = sbBluetooth,
            onCheckedChange = {
                sbBluetooth = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_BLUETOOTH, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_speed),
            checked = sbSpeed,
            onCheckedChange = {
                sbSpeed = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_SPEED, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
        SettingsSwitch(
            title = res(R.string.sb_item_notifs),
            checked = sbNotifs,
            onCheckedChange = {
                sbNotifs = it
                prefs.edit().putBoolean(GlobalOverlayPrefs.SB_ITEM_NOTIFS, it).apply()
                GlobalOverlayService.refresh(ctx)
            },
        )
    }

    if (showTileRows) {
        SettingsPickerDialog(
            title = stringResource(R.string.qs_tile_rows_title),
            labels = tileRowEntries,
            selectedIndex = tileRowValues.indexOf(tileRows).coerceAtLeast(0),
            onSelect = { index ->
                showTileRows = false
                tileRows = tileRowValues[index]
                prefs.edit().putString(QsPrefs.TILE_ROWS, tileRows).apply()
            },
            onDismiss = { showTileRows = false },
        )
    }
    if (showTileStyle) {
        SettingsPickerDialog(
            title = stringResource(R.string.qs_setting_tile_style_title),
            labels = tileStyleEntries,
            selectedIndex = tileStyleValues.indexOf(tileStyle).coerceAtLeast(0),
            onSelect = { index ->
                showTileStyle = false
                tileStyle = tileStyleValues[index]
                prefs.edit().putString(QsPrefs.TILE_STYLE, tileStyle).apply()
            },
            onDismiss = { showTileStyle = false },
        )
    }
    if (showPrivilege) {
        SettingsPickerDialog(
            title = stringResource(R.string.qs_privilege_title),
            labels = privilegeEntries,
            selectedIndex = privilegeValues.indexOf(privilege).coerceAtLeast(0),
            onSelect = { index ->
                showPrivilege = false
                privilege = privilegeValues[index]
                if (privilege == "shizuku") {
                    PrivilegeManager.invalidate()
                    PrivilegeManager.requestShizukuPermission(99)
                }
                prefs.edit().putString(QsPrefs.PRIVILEGE_MODE, privilege).apply()
                PrivilegeManager.invalidate()
                refreshStatuses()
            },
            onDismiss = { showPrivilege = false },
        )
    }
    if (showSbMode) {
        SettingsPickerDialog(
            title = stringResource(R.string.status_bar_overlay_title),
            labels = sbEntries,
            selectedIndex = sbValues.indexOf(sbMode).coerceAtLeast(0),
            onSelect = { index ->
                showSbMode = false
                sbMode = sbValues[index]
                prefs.edit().putString(GlobalOverlayPrefs.STATUS_BAR_OVERLAY_MODE, sbMode).apply()
                GlobalOverlayService.refresh(ctx)
            },
            onDismiss = { showSbMode = false },
        )
    }
    colorPick?.let { current ->
        SettingsTextDialog(
            title = stringResource(
                when (current.key) {
                    QsPrefs.COLOR_CONNECTED -> R.string.qs_color_connected_title
                    QsPrefs.COLOR_IDLE -> R.string.qs_color_idle_title
                    QsPrefs.COLOR_DISCONNECTED -> R.string.qs_color_disconnected_title
                    else -> R.string.qs_color_off_title
                }
            ),
            initial = current.value,
            onConfirm = { raw ->
                colorPick = null
                if (raw.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                    prefs.edit().putString(current.key, raw).apply()
                    when (current.key) {
                        QsPrefs.COLOR_CONNECTED -> colorConnected = raw
                        QsPrefs.COLOR_IDLE -> colorIdle = raw
                        QsPrefs.COLOR_DISCONNECTED -> colorDisconnected = raw
                        QsPrefs.COLOR_OFF -> colorOff = raw
                    }
                }
            },
            onDismiss = { colorPick = null },
        )
    }
}

private data class ColorPick(val key: String, val value: String)
