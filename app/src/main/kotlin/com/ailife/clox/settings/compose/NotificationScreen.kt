package com.ailife.clox.settings.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.qs.privilege.PrivilegeLevel
import com.ailife.clox.qs.privilege.PrivilegeManager
import com.ailife.clox.qs.privilege.RestrictedSettingsHelper
import com.ailife.clox.qs.privilege.RootOperations
import com.ailife.clox.qs.privilege.ShizukuOperations
import com.ailife.clox.util.safeStartActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotificationScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }

    var enabled by remember { mutableStateOf(prefs.getBoolean("notif_enabled", true)) }
    var cardStyle by remember { mutableStateOf(prefs.getString("notif_card_style", "glass") ?: "glass") }
    var viewStyle by remember { mutableStateOf(prefs.getString("notif_style", "list") ?: "list") }
    var textSize by remember { mutableStateOf(prefs.getInt("notif_text_size", 14).coerceIn(10, 30)) }
    var media by remember { mutableStateOf(prefs.getBoolean("notif_media_enabled", true)) }
    var wake by remember { mutableStateOf(prefs.getBoolean("notif_wake_screen", false)) }
    var autoOpen by remember { mutableStateOf(prefs.getBoolean("notif_open_panel", false)) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("notif_vibrate", false)) }
    var group by remember { mutableStateOf(prefs.getBoolean("notif_group", false)) }
    var blockPersistent by remember { mutableStateOf(prefs.getBoolean("notif_block_persistent", false)) }

    var showCardStyle by remember { mutableStateOf(false) }
    var showViewStyle by remember { mutableStateOf(false) }

    val cardStyleEntries = stringArrayResource(R.array.notif_card_style_entries)
    val cardStyleValues = stringArrayResource(R.array.notif_card_style_values)
    val viewStyleEntries = stringArrayResource(R.array.notif_style_entries)
    val viewStyleValues = stringArrayResource(R.array.notif_style_values)

    // Permission status must refresh after returning from the system grant screen.
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeTick) { isNotificationListenerEnabled(ctx) }
    val running = remember(resumeTick) { CloXNotificationListenerService.isRunning() }
    val privilege = remember(resumeTick) { PrivilegeManager.level(ctx) }
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    val permissionStatus = when {
        !granted -> stringResource(R.string.notif_perm_status_not_granted)
        !running -> stringResource(R.string.notif_perm_status_service_down)
        else     -> stringResource(R.string.notif_perm_status_active)
    }

    WearSettingsScreen(title = stringResource(R.string.pref_notifications_title)) {
        SettingsSwitch(
            title = res(R.string.notif_setting_enabled_title),
            summary = res(R.string.notif_setting_enabled_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean("notif_enabled", it).apply()
            },
        )

        SectionHeader(res(R.string.notif_setting_display_category))
        SettingsButton(
            title = res(R.string.notif_setting_card_style_title),
            summary = cardStyleEntries.getOrElse(cardStyleValues.indexOf(cardStyle)) { cardStyle },
            enabled = enabled,
        ) { showCardStyle = true }
        SettingsButton(
            title = res(R.string.notif_setting_view_style_title),
            summary = viewStyleEntries.getOrElse(viewStyleValues.indexOf(viewStyle)) { viewStyle },
            enabled = enabled,
        ) { showViewStyle = true }
        SettingsSlider(
            title = res(R.string.notif_setting_text_size_title),
            value = textSize,
            range = 10..30,
            valueText = res(R.string.unit_sp, textSize),
        ) {
            textSize = it
            prefs.edit().putInt("notif_text_size", it).apply()
        }
        SettingsSwitch(
            title = res(R.string.notif_setting_media_controls_title),
            summary = res(R.string.notif_setting_media_controls_summary),
            checked = media,
            enabled = enabled,
            onCheckedChange = {
                media = it
                prefs.edit().putBoolean("notif_media_enabled", it).apply()
            },
        )

        SectionHeader(res(R.string.notif_setting_alerts_category))
        SettingsSwitch(
            title = res(R.string.notif_setting_wake_title),
            checked = wake,
            enabled = enabled,
            onCheckedChange = {
                wake = it
                prefs.edit().putBoolean("notif_wake_screen", it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.notif_setting_open_panel_title),
            checked = autoOpen,
            enabled = enabled,
            onCheckedChange = {
                autoOpen = it
                prefs.edit().putBoolean("notif_open_panel", it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.notif_setting_vibrate_title),
            checked = vibrate,
            enabled = enabled,
            onCheckedChange = {
                vibrate = it
                prefs.edit().putBoolean("notif_vibrate", it).apply()
            },
        )

        SectionHeader(res(R.string.notif_setting_filtering_category))
        SettingsSwitch(
            title = res(R.string.notif_setting_group_title),
            summary = res(R.string.notif_setting_group_summary),
            checked = group,
            enabled = enabled,
            onCheckedChange = {
                group = it
                prefs.edit().putBoolean("notif_group", it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.notif_setting_hide_persistent_title),
            summary = res(R.string.notif_setting_hide_persistent_summary),
            checked = blockPersistent,
            enabled = enabled,
            onCheckedChange = {
                blockPersistent = it
                prefs.edit().putBoolean("notif_block_persistent", it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.notif_setting_blocked_apps_title),
            summary = res(R.string.notif_setting_blocked_apps_summary),
            enabled = enabled,
        ) {
            com.ailife.clox.overlay.notifications.NotificationBlockedAppsActivity.launch(ctx)
        }

        SettingsButton(
            title = res(R.string.notif_setting_access_title),
            summary = permissionStatus,
            onClick = {
                when {
                    granted && !running -> {
                        CloXNotificationListenerService.requestRebind(ctx)
                        resumeTick++
                    }
                    !granted && privilege != PrivilegeLevel.NONE && !granting -> {
                        granting = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                grantNotificationAccessPrivileged(ctx)
                            }
                            granting = false
                            if (ok) {
                                CloXNotificationListenerService.requestRebind(ctx)
                                Toast.makeText(
                                    ctx, R.string.notif_perm_one_tap_ok, Toast.LENGTH_SHORT).show()
                                resumeTick++
                            } else {
                                Toast.makeText(
                                    ctx, R.string.notif_perm_one_tap_failed, Toast.LENGTH_SHORT).show()
                                RestrictedSettingsHelper.openGrantScreen(
                                    ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                    else -> RestrictedSettingsHelper.openGrantScreen(
                        ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
        )
        if (!granted && privilege != PrivilegeLevel.NONE) {
            SettingsNote(res(R.string.notif_perm_one_tap_hint))
        }
    }

    if (showCardStyle) {
        SettingsPickerDialog(
            title = stringResource(R.string.notif_pick_card_style),
            labels = cardStyleEntries,
            selectedIndex = cardStyleValues.indexOf(cardStyle).coerceAtLeast(0),
            onSelect = { index ->
                showCardStyle = false
                cardStyle = cardStyleValues[index]
                prefs.edit().putString("notif_card_style", cardStyle).apply()
            },
            onDismiss = { showCardStyle = false },
        )
    }
    if (showViewStyle) {
        SettingsPickerDialog(
            title = stringResource(R.string.notif_pick_view_style),
            labels = viewStyleEntries,
            selectedIndex = viewStyleValues.indexOf(viewStyle).coerceAtLeast(0),
            onSelect = { index ->
                showViewStyle = false
                viewStyle = viewStyleValues[index]
                prefs.edit().putString("notif_style", viewStyle).apply()
            },
            onDismiss = { showViewStyle = false },
        )
    }
}

/**
 * Grants notification-listener access via Shizuku/root by appending our
 * component to the existing colon-separated list (never replacing it).
 */
private fun grantNotificationAccessPrivileged(context: Context): Boolean {
    val component = ComponentName(
        context, CloXNotificationListenerService::class.java).flattenToString()
    val current = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners").orEmpty()
    val entries = current.split(':').filter { it.isNotBlank() }
    if (component in entries) return true
    val value = (entries + component).joinToString(":")
    return when (PrivilegeManager.level(context)) {
        PrivilegeLevel.SHIZUKU ->
            ShizukuOperations.writeSettings("secure", "enabled_notification_listeners", value)
        PrivilegeLevel.ROOT ->
            RootOperations.writeSettings("secure", "enabled_notification_listeners", value)
        PrivilegeLevel.NONE -> false
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners")
    val component = ComponentName(
        context, CloXNotificationListenerService::class.java).flattenToString()
    return flat?.contains(component) == true
}
