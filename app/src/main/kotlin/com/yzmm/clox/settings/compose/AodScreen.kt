package com.yzmm.clox.settings.compose

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.aod.AodPrefs

@Composable
fun AodScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }

    var enabled by remember { mutableStateOf(prefs.getBoolean(AodPrefs.KEY_ENABLED, false)) }
    var whenCharging by remember {
        mutableStateOf(prefs.getBoolean(AodPrefs.KEY_ALWAYS_WHEN_CHARGING, false))
    }
    var timeout by remember {
        mutableStateOf(
            prefs.getInt(AodPrefs.KEY_TIMEOUT_SECONDS, AodPrefs.DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(5, 300)
        )
    }
    var brightness by remember {
        mutableStateOf(
            prefs.getInt(AodPrefs.KEY_BRIGHTNESS, AodPrefs.DEFAULT_BRIGHTNESS).coerceIn(1, 255)
        )
    }
    var reawaken by remember {
        mutableStateOf(prefs.getBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, true))
    }
    var lockGesture by remember {
        mutableStateOf(prefs.getBoolean(AodPrefs.KEY_LOCK_GESTURE, true))
    }
    var lockDelay by remember {
        mutableStateOf(
            prefs.getInt(AodPrefs.KEY_LOCK_GESTURE_DELAY, AodPrefs.DEFAULT_LOCK_GESTURE_DELAY)
                .coerceIn(0, 30)
        )
    }
    var shallowSleep by remember {
        mutableStateOf(prefs.getBoolean(AodPrefs.KEY_SHALLOW_SLEEP, false))
    }
    var powerExit by remember {
        mutableStateOf(prefs.getBoolean(AodPrefs.KEY_POWER_BUTTON_EXIT, true))
    }

    var showOverlayDialog by remember { mutableStateOf(false) }

    fun needOverlayPermission() = !Settings.canDrawOverlays(ctx)
    fun promptOverlayIfNeeded() {
        if (needOverlayPermission()) showOverlayDialog = true
    }

    LaunchedEffect(Unit) {
        if (enabled && reawaken) promptOverlayIfNeeded()
    }

    WearSettingsScreen(title = stringResource(R.string.pref_aod_title)) {
        SettingsSwitch(
            title = res(R.string.pref_aod_enabled_title),
            summary = res(R.string.pref_aod_enabled_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean(AodPrefs.KEY_ENABLED, it).apply()
                if (it && reawaken) promptOverlayIfNeeded()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_aod_when_charging_title),
            summary = res(R.string.pref_aod_when_charging_summary),
            checked = whenCharging,
            enabled = enabled,
            onCheckedChange = {
                whenCharging = it
                prefs.edit().putBoolean(AodPrefs.KEY_ALWAYS_WHEN_CHARGING, it).apply()
            },
        )

        SectionHeader(res(R.string.pref_aod_category_display))
        SettingsSlider(
            title = res(R.string.pref_aod_timeout_title),
            value = timeout,
            range = 5..300,
            valueText = res(R.string.unit_seconds, timeout),
            onChange = {
                timeout = it
                prefs.edit().putInt(AodPrefs.KEY_TIMEOUT_SECONDS, it).apply()
            },
        )
        SettingsSlider(
            title = res(R.string.pref_aod_brightness_title),
            value = brightness,
            range = 1..255,
            valueText = "$brightness",
            onChange = {
                brightness = it
                prefs.edit().putInt(AodPrefs.KEY_BRIGHTNESS, it).apply()
            },
        )

        SectionHeader(res(R.string.pref_aod_category_behavior))
        SettingsSwitch(
            title = res(R.string.pref_aod_reawaken_title),
            summary = res(R.string.pref_aod_reawaken_summary),
            checked = reawaken,
            enabled = enabled,
            onCheckedChange = {
                reawaken = it
                prefs.edit().putBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, it).apply()
                if (it) promptOverlayIfNeeded()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_aod_lock_gesture_title),
            summary = res(R.string.pref_aod_lock_gesture_summary),
            checked = lockGesture,
            enabled = enabled,
            onCheckedChange = {
                lockGesture = it
                prefs.edit().putBoolean(AodPrefs.KEY_LOCK_GESTURE, it).apply()
            },
        )
        if (lockGesture) {
            SettingsSlider(
                title = res(R.string.pref_aod_lock_gesture_delay_title),
                value = lockDelay,
                range = 0..30,
                valueText = res(R.string.unit_seconds, lockDelay),
                onChange = {
                    lockDelay = it
                    prefs.edit().putInt(AodPrefs.KEY_LOCK_GESTURE_DELAY, it).apply()
                },
            )
        }
        SettingsSwitch(
            title = res(R.string.pref_aod_shallow_sleep_title),
            summary = res(R.string.pref_aod_shallow_sleep_summary),
            checked = shallowSleep,
            enabled = enabled,
            onCheckedChange = {
                shallowSleep = it
                prefs.edit().putBoolean(AodPrefs.KEY_SHALLOW_SLEEP, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_aod_power_button_exit_title),
            summary = res(R.string.pref_aod_power_button_exit_summary),
            checked = powerExit,
            enabled = enabled,
            onCheckedChange = {
                powerExit = it
                prefs.edit().putBoolean(AodPrefs.KEY_POWER_BUTTON_EXIT, it).apply()
            },
        )
    }

    if (showOverlayDialog) {
        SettingsConfirmDialog(
            title = stringResource(R.string.aod_overlay_perm_title),
            message = stringResource(R.string.aod_overlay_perm_message),
            confirmLabel = stringResource(R.string.aod_overlay_perm_grant),
            onConfirm = {
                runCatching {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }.onFailure {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
            },
            onDismiss = { showOverlayDialog = false },
        )
    }
}
