package com.yzmm.clox.settings.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.gesture.ButtonMappingPrefs
import com.yzmm.clox.gesture.GestureAppPref
import com.yzmm.clox.qs.QsPermissionHelper

private data class ActionPick(
    val title: String,
    val baseKey: String,
    val default: String,
)

@Composable
fun GesturesScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    var showRecordDialog by remember { mutableStateOf(false) }

    val actionEntries = stringArrayResource(R.array.gesture_action_entries)
    val actionValues = stringArrayResource(R.array.gesture_action_values)
    val navEntries = stringArrayResource(R.array.nav_bar_mode_entries)
    val navValues = stringArrayResource(R.array.nav_bar_mode_values)
    val statusEntries = stringArrayResource(R.array.status_bar_mode_entries)
    val statusValues = stringArrayResource(R.array.status_bar_mode_values)

    var version by remember { mutableStateOf(0) }
    var mappedKeys by remember { mutableStateOf(ButtonMappingPrefs.getMappedKeyCodes(ctx)) }
    var pick by remember { mutableStateOf<ActionPick?>(null) }
    var appPick by remember { mutableStateOf<ActionPick?>(null) }
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }

    fun labelFor(value: String) =
        actionEntries.getOrElse(actionValues.indexOf(value)) { value }

    fun summaryFor(baseKey: String, default: String): String {
        @Suppress("UNUSED_VARIABLE")
        val tick = version   // state read: recompose after picking an action
        val v = prefs.getString(baseKey, default) ?: default
        return if (v == "open_app") {
            val app = GestureAppPref.getLabel(ctx, baseKey)
                ?: ctx.getString(R.string.gesture_open_app_none)
            ctx.getString(R.string.gesture_open_app_summary, app)
        } else labelFor(v)
    }

    LaunchedEffect(appPick, apps) {
        if (appPick != null && apps == null) apps = loadLaunchableApps(ctx)
    }

    var doubleTapHold by remember {
        mutableStateOf(prefs.getBoolean("double_tap_hold_taps", false))
    }
    var homeScreenOff by remember {
        mutableStateOf(prefs.getBoolean("home_press_screen_off", false))
    }
    var appListSwipe by remember {
        mutableStateOf(prefs.getBoolean("app_list_swipe_enabled", false))
    }
    var resetOnScreenOn by remember {
        mutableStateOf(prefs.getBoolean("reset_watchface_on_screen_on", false))
    }
    var navBar by remember { mutableStateOf(prefs.getString("nav_bar_mode", "applist") ?: "applist") }
    var statusBar by remember { mutableStateOf(prefs.getString("status_bar_mode", "hidden") ?: "hidden") }
    var showNavBar by remember { mutableStateOf(false) }
    var showStatusBar by remember { mutableStateOf(false) }

    WearSettingsScreen(title = stringResource(R.string.pref_gestures_title)) {
        SectionHeader(res(R.string.pref_gestures_watchface_category))
        SettingsButton(
            title = res(R.string.pref_double_tap_title),
            summary = summaryFor("double_tap_action", "screen_off"),
        ) {
            pick = ActionPick(ctx.getString(R.string.pref_double_tap_title),
                "double_tap_action", "screen_off")
        }
        SettingsSwitch(
            title = res(R.string.pref_double_tap_hold_title),
            summary = res(R.string.pref_double_tap_hold_summary),
            checked = doubleTapHold,
            onCheckedChange = {
                doubleTapHold = it
                prefs.edit().putBoolean("double_tap_hold_taps", it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.pref_long_press_title),
            summary = summaryFor("long_press_action", "watchface_selector"),
        ) {
            pick = ActionPick(ctx.getString(R.string.pref_long_press_title),
                "long_press_action", "watchface_selector")
        }
        SettingsSwitch(
            title = res(R.string.pref_home_press_screen_off_title),
            summary = res(R.string.pref_home_press_screen_off_summary),
            checked = homeScreenOff,
            onCheckedChange = {
                if (it) QsPermissionHelper.ensureAccessibilityEnabled(ctx)
                homeScreenOff = it
                prefs.edit().putBoolean("home_press_screen_off", it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_app_list_swipe_title),
            summary = res(R.string.pref_app_list_swipe_summary),
            checked = appListSwipe,
            onCheckedChange = {
                appListSwipe = it
                prefs.edit().putBoolean("app_list_swipe_enabled", it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_reset_watchface_on_screen_title),
            summary = res(R.string.pref_reset_watchface_on_screen_summary),
            checked = resetOnScreenOn,
            onCheckedChange = {
                resetOnScreenOn = it
                prefs.edit().putBoolean("reset_watchface_on_screen_on", it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.pref_nav_bar_title),
            summary = navEntries.getOrElse(navValues.indexOf(navBar)) { navBar },
        ) { showNavBar = true }
        SettingsButton(
            title = res(R.string.pref_status_bar_title),
            summary = statusEntries.getOrElse(statusValues.indexOf(statusBar)) { statusBar },
        ) { showStatusBar = true }

        SectionHeader(res(R.string.pref_swipe_category))
        SettingsNote(res(R.string.pref_swipe_category_summary))
        SettingsButton(
            title = res(R.string.pref_swipe_down_title),
            summary = summaryFor("swipe_down_action", "nothing"),
        ) {
            pick = ActionPick(ctx.getString(R.string.pref_swipe_down_title),
                "swipe_down_action", "nothing")
        }
        SettingsButton(
            title = res(R.string.pref_swipe_right_title),
            summary = summaryFor("swipe_right_action", "nothing"),
        ) {
            pick = ActionPick(ctx.getString(R.string.pref_swipe_right_title),
                "swipe_right_action", "nothing")
        }

        SectionHeader(res(R.string.pref_btn_map_category))
        for (keyCode in mappedKeys) {
            SettingsItem {
                Text(
                    text = ButtonMappingPrefs.keyCodeToName(ctx, keyCode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            for ((press, titleRes) in listOf(
                ButtonMappingPrefs.PRESS_SINGLE to R.string.pref_btn_single_title,
                ButtonMappingPrefs.PRESS_DOUBLE to R.string.pref_btn_double_title,
                ButtonMappingPrefs.PRESS_LONG to R.string.pref_btn_long_title,
            )) {
                val baseKey = ButtonMappingPrefs.actionKey(keyCode, press)
                SettingsButton(
                    title = res(titleRes),
                    summary = summaryFor(baseKey, "nothing"),
                ) {
                    pick = ActionPick(ctx.getString(titleRes), baseKey, "nothing")
                }
            }
            SettingsButton(
                title = res(R.string.pref_btn_remove),
            ) {
                ButtonMappingPrefs.removeKeyCode(ctx, keyCode)
                mappedKeys = ButtonMappingPrefs.getMappedKeyCodes(ctx)
            }
        }
        SettingsButton(
            title = res(R.string.pref_btn_add_title),
            summary = res(R.string.pref_btn_add_summary),
        ) { showRecordDialog = true }
    }

    pick?.let { current ->
        val value = prefs.getString(current.baseKey, current.default) ?: current.default
        SettingsPickerDialog(
            title = current.title,
            labels = actionEntries,
            selectedIndex = actionValues.indexOf(value).coerceAtLeast(0),
            onSelect = { index ->
                val selected = actionValues[index]
                prefs.edit().putString(current.baseKey, selected).apply()
                pick = null
                if (selected == "open_app") {
                    apps = null
                    appPick = current
                } else {
                    version++
                }
            },
            onDismiss = { pick = null },
        )
    }

    appPick?.let { current ->
        AppChoiceDialog(
            title = current.title,
            apps = apps,
            onSelect = { app ->
                GestureAppPref.setComponent(ctx, current.baseKey, app.component)
                appPick = null
                version++
            },
            onDismiss = {
                appPick = null
                version++
            },
        )
    }

    if (showNavBar) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_nav_bar_title),
            labels = navEntries,
            selectedIndex = navValues.indexOf(navBar).coerceAtLeast(0),
            onSelect = { index ->
                showNavBar = false
                navBar = navValues[index]
                prefs.edit().putString("nav_bar_mode", navBar).apply()
            },
            onDismiss = { showNavBar = false },
        )
    }
    if (showRecordDialog) {
        ButtonRecordDialog(
            onKeyRecorded = { keyCode ->
                showRecordDialog = false
                ButtonMappingPrefs.addKeyCode(ctx, keyCode)
                mappedKeys = ButtonMappingPrefs.getMappedKeyCodes(ctx)
            },
            onDismiss = { showRecordDialog = false },
        )
    }

    if (showStatusBar) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_status_bar_title),
            labels = statusEntries,
            selectedIndex = statusValues.indexOf(statusBar).coerceAtLeast(0),
            onSelect = { index ->
                showStatusBar = false
                statusBar = statusValues[index]
                prefs.edit().putString("status_bar_mode", statusBar).apply()
            },
            onDismiss = { showStatusBar = false },
        )
    }
}
