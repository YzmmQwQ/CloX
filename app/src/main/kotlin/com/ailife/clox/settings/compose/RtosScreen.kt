package com.ailife.clox.settings.compose

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import com.ailife.clox.R
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.rtos.RtosPrefs
import com.ailife.clox.rtos.settings.RtosPasswordSetupActivity
import com.ailife.clox.rtos.settings.RtosWhitelistActivity
import com.ailife.clox.util.safeStartActivity

@Composable
fun RtosScreen() {
    val ctx = LocalContext.current
    val rtosPrefs = remember { RtosPrefs.get(ctx) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val modeEntries = stringArrayResource(R.array.rtos_mode_entries)
    val modeValues = stringArrayResource(R.array.rtos_mode_values)
    val passwordEntries = stringArrayResource(R.array.rtos_password_entries)
    val passwordValues = stringArrayResource(R.array.rtos_password_values)

    var enabled by remember { mutableStateOf(RtosPrefs.isEnabled(ctx)) }
    var mode by remember {
        mutableStateOf(rtosPrefs.getString(RtosPrefs.KEY_MODE, RtosPrefs.MODE_WATCHFACE_ONLY)
            ?: RtosPrefs.MODE_WATCHFACE_ONLY)
    }
    var autoEnter by remember { mutableStateOf(RtosPrefs.autoEnter(ctx)) }
    var launcherIcon by remember {
        mutableStateOf(rtosPrefs.getBoolean(RtosPrefs.KEY_SHOW_LAUNCHER_ICON, false))
    }
    var passwordType by remember {
        mutableStateOf(rtosPrefs.getString(RtosPrefs.KEY_PASSWORD_TYPE, RtosPrefs.PASSWORD_KEYPAD)
            ?: RtosPrefs.PASSWORD_KEYPAD)
    }
    var blockApps by remember { mutableStateOf(RtosPrefs.blockApps(ctx)) }
    var accessibilityStatus by remember {
        mutableStateOf(ctx.getString(
            if (CloXAccessibilityService.isRunning()) R.string.rtos_accessibility_enabled
            else R.string.rtos_accessibility_disabled))
    }

    var showMode by remember { mutableStateOf(false) }
    var showPasswordType by remember { mutableStateOf(false) }

    fun setEntryLauncherEnabled(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, "com.ailife.clox.rtos.RtosEntryLauncher"),
                state, PackageManager.DONT_KILL_APP)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityStatus = ctx.getString(
                    if (CloXAccessibilityService.isRunning()) R.string.rtos_accessibility_enabled
                    else R.string.rtos_accessibility_disabled)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.pref_rtos_title)) {
        SettingsSwitch(
            title = res(R.string.rtos_setting_enabled_title),
            summary = res(R.string.rtos_setting_enabled_summary),
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked && RtosPrefs.passwordHash(ctx) == null) {
                    Toast.makeText(
                        ctx, R.string.toast_rtos_set_password_first, Toast.LENGTH_SHORT).show()
                } else {
                    enabled = checked
                    RtosPrefs.setEnabled(ctx, checked)
                }
            },
        )

        SectionHeader(res(R.string.rtos_setting_options_category))
        SettingsButton(
            title = res(R.string.rtos_setting_mode_title),
            summary = modeEntries.getOrElse(modeValues.indexOf(mode)) { mode },
        ) { showMode = true }
        SettingsSwitch(
            title = res(R.string.rtos_setting_auto_enter_title),
            summary = res(R.string.rtos_setting_auto_enter_summary),
            checked = autoEnter,
            onCheckedChange = {
                autoEnter = it
                rtosPrefs.edit().putBoolean(RtosPrefs.KEY_AUTO_ENTER, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.rtos_setting_launcher_icon_title),
            summary = res(R.string.rtos_setting_launcher_icon_summary),
            checked = launcherIcon,
            onCheckedChange = {
                launcherIcon = it
                rtosPrefs.edit().putBoolean(RtosPrefs.KEY_SHOW_LAUNCHER_ICON, it).apply()
                setEntryLauncherEnabled(it)
            },
        )

        SectionHeader(res(R.string.rtos_setting_password_category))
        SettingsButton(
            title = res(R.string.rtos_setting_password_type_title),
            summary = passwordEntries.getOrElse(passwordValues.indexOf(passwordType)) { passwordType },
        ) { showPasswordType = true }
        SettingsButton(
            title = res(R.string.rtos_setting_set_password_title),
            summary = res(R.string.rtos_setting_set_password_summary),
        ) { ctx.safeStartActivity(Intent(ctx, RtosPasswordSetupActivity::class.java)) }

        SectionHeader(res(R.string.rtos_setting_app_restrictions_category))
        SettingsButton(
            title = res(R.string.rtos_setting_allowed_apps_title),
            summary = res(R.string.rtos_setting_allowed_apps_summary),
        ) { ctx.safeStartActivity(Intent(ctx, RtosWhitelistActivity::class.java)) }
        SettingsSwitch(
            title = res(R.string.rtos_setting_block_apps_title),
            summary = res(R.string.rtos_setting_block_apps_summary),
            checked = blockApps,
            onCheckedChange = { checked ->
                if (checked && !CloXAccessibilityService.isRunning()) {
                    Toast.makeText(
                        ctx, R.string.toast_rtos_accessibility_first, Toast.LENGTH_SHORT).show()
                    QsPermissionHelper.ensureAccessibilityEnabled(ctx)
                } else {
                    blockApps = checked
                    rtosPrefs.edit().putBoolean(RtosPrefs.KEY_BLOCK_APPS, checked).apply()
                }
            },
        )

        SectionHeader(res(R.string.rtos_setting_accessibility_category))
        SettingsButton(
            title = res(R.string.rtos_setting_accessibility_title),
            summary = accessibilityStatus,
        ) { QsPermissionHelper.ensureAccessibilityEnabled(ctx) }
    }

    if (showMode) {
        SettingsPickerDialog(
            title = stringResource(R.string.rtos_setting_mode_title),
            labels = modeEntries,
            selectedIndex = modeValues.indexOf(mode).coerceAtLeast(0),
            onSelect = { index ->
                showMode = false
                mode = modeValues[index]
                rtosPrefs.edit().putString(RtosPrefs.KEY_MODE, mode).apply()
            },
            onDismiss = { showMode = false },
        )
    }
    if (showPasswordType) {
        SettingsPickerDialog(
            title = stringResource(R.string.rtos_setting_password_type_title),
            labels = passwordEntries,
            selectedIndex = passwordValues.indexOf(passwordType).coerceAtLeast(0),
            onSelect = { index ->
                showPasswordType = false
                passwordType = passwordValues[index]
                rtosPrefs.edit().putString(RtosPrefs.KEY_PASSWORD_TYPE, passwordType).apply()
                RtosPrefs.setPassword(ctx, emptyList(), "")
                rtosPrefs.edit()
                    .remove(RtosPrefs.KEY_PASSWORD_HASH)
                    .remove(RtosPrefs.KEY_PASSWORD_SEQ)
                    .apply()
                RtosPrefs.setEnabled(ctx, false)
                enabled = false
                Toast.makeText(
                    ctx, R.string.toast_rtos_password_cleared, Toast.LENGTH_LONG).show()
            },
            onDismiss = { showPasswordType = false },
        )
    }
}
