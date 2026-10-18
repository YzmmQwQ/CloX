package com.yzmm.clox.settings.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import com.yzmm.clox.R

/** Wear M3 settings root. Mirrors the old preferences_root.xml entries. */
@Composable
fun RootSettingsScreen(
    hideAppsVisible: Boolean,
    onOpen: (String) -> Unit,
) {
    WearSettingsScreen(title = stringResource(R.string.settings_title)) {
        SettingsButton(
            title = res(R.string.pref_notifications_title),
            summary = res(R.string.pref_notifications_summary),
            icon = { NavIcon(R.drawable.baseline_notifications_24) },
        ) { onOpen(SettingsRoutes.NOTIFICATIONS) }

        SettingsButton(
            title = res(R.string.qs_title),
            summary = res(R.string.pref_quick_settings_summary),
            icon = { NavIcon(R.drawable.baseline_tune_24) },
        ) { onOpen(SettingsRoutes.QUICK_SETTINGS) }

        SettingsButton(
            title = res(R.string.pref_app_list_title),
            summary = res(R.string.pref_app_list_summary),
            icon = { NavIcon(R.drawable.baseline_apps_24) },
        ) { onOpen(SettingsRoutes.APP_LIST) }

        SettingsButton(
            title = res(R.string.applist_setting_animations_title),
            summary = res(R.string.applist_setting_animations_summary),
            icon = { NavIcon(R.drawable.baseline_animation_24) },
        ) { onOpen(SettingsRoutes.ANIMATIONS) }

        SettingsButton(
            title = res(R.string.pref_watchface_title),
            summary = res(R.string.pref_watchface_summary),
            icon = { NavIcon(R.drawable.baseline_watch_24) },
        ) { onOpen(SettingsRoutes.WATCHFACE) }

        SettingsButton(
            title = res(R.string.pref_gestures_title),
            summary = res(R.string.pref_gestures_summary),
            icon = { NavIcon(R.drawable.baseline_gesture_24) },
        ) { onOpen(SettingsRoutes.GESTURES) }

        SettingsButton(
            title = res(R.string.pref_aod_title),
            summary = res(R.string.pref_aod_summary),
            icon = { NavIcon(R.drawable.baseline_aod_24) },
        ) { onOpen(SettingsRoutes.AOD) }

        SettingsButton(
            title = res(R.string.pref_appearance_title),
            summary = res(R.string.pref_appearance_summary),
            icon = { NavIcon(R.drawable.baseline_palette_24) },
        ) { onOpen(SettingsRoutes.APPEARANCE) }

        SettingsButton(
            title = res(R.string.pref_tap_title),
            summary = res(R.string.pref_tap_summary),
            icon = { NavIcon(R.drawable.baseline_touch_app_24) },
        ) { onOpen(SettingsRoutes.TAP_ACTIONS) }

        SettingsButton(
            title = res(R.string.pref_weather_title),
            summary = res(R.string.pref_weather_summary),
            icon = { NavIcon(R.drawable.baseline_wb_cloudy_24) },
        ) { onOpen(SettingsRoutes.WEATHER) }

        SettingsButton(
            title = res(R.string.pref_location_title),
            summary = res(R.string.pref_location_summary),
            icon = { NavIcon(R.drawable.baseline_gps_fixed_24) },
        ) { onOpen(SettingsRoutes.LOCATION) }

        SettingsButton(
            title = res(R.string.pref_health_title),
            summary = res(R.string.pref_health_summary),
            icon = { NavIcon(R.drawable.baseline_monitor_heart_24) },
        ) { onOpen(SettingsRoutes.HEALTH) }

        SettingsButton(
            title = res(R.string.pref_sensors_title),
            summary = res(R.string.pref_sensors_summary),
            icon = { NavIcon(R.drawable.baseline_sensors_24) },
        ) { onOpen(SettingsRoutes.SENSORS) }

        if (hideAppsVisible) {
            SettingsButton(
                title = res(R.string.pref_hidden_apps_title),
                summary = res(R.string.pref_hidden_apps_summary),
                icon = { NavIcon(R.drawable.baseline_visibility_off_24) },
            ) { onOpen(SettingsRoutes.HIDE_APPS) }
        }

        SettingsButton(
            title = res(R.string.pref_rtos_title),
            summary = res(R.string.pref_rtos_summary),
            icon = { NavIcon(R.drawable.baseline_developer_board_24) },
        ) { onOpen(SettingsRoutes.RTOS) }

        SettingsButton(
            title = res(R.string.pref_debug_title),
            summary = res(R.string.pref_debug_summary),
            icon = { NavIcon(R.drawable.baseline_bug_report_24) },
        ) { onOpen(SettingsRoutes.DEBUG) }

        SettingsButton(
            title = res(R.string.pref_about_title),
            summary = res(R.string.pref_about_summary),
            icon = { NavIcon(R.drawable.baseline_info_24) },
        ) { onOpen(SettingsRoutes.ABOUT) }
    }
}

@Composable
private fun NavIcon(resId: Int) {
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
}
