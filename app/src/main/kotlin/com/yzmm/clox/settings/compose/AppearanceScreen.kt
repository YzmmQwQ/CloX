package com.yzmm.clox.settings.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.ui.theme.ScreenShape
import com.yzmm.clox.util.safeStartActivity

/**
 * Appearance settings — first Wear M3 Compose screen; also the template for the
 * rest of the settings migration.
 */
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var darkMode by remember { mutableStateOf(prefs.getString("dark_mode", "system") ?: "system") }
    var dpi by remember { mutableStateOf(prefs.getString("app_dpi_override", "0") ?: "0") }
    var blurMode by remember { mutableStateOf(prefs.getString("blur_mode", "BLURVIEW") ?: "BLURVIEW") }
    var blurRadius by remember { mutableStateOf(prefs.getInt("blur_radius", 15).coerceIn(2, 25)) }
    var screenShape by remember { mutableStateOf(ScreenShape.read(context)) }

    var showDarkPicker by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showBlurPicker by remember { mutableStateOf(false) }

    val darkLabels = arrayOf(
        stringResource(R.string.appearance_dark_mode_system),
        stringResource(R.string.appearance_dark_mode_on),
        stringResource(R.string.appearance_dark_mode_off),
    )
    val darkValues = arrayOf("system", "on", "off")

    val blurLabels = stringArrayResource(R.array.blur_mode_entries)
    val blurValues = stringArrayResource(R.array.blur_mode_values)

    WearSettingsScreen(title = stringResource(R.string.pref_appearance_title)) {
        SectionHeader(res(R.string.screen_shape_title))
        ScreenShape.entries.forEach { shape ->
            SettingsRadio(
                title = res(shape.labelRes),
                selected = screenShape == shape,
            ) {
                screenShape = shape
                ScreenShape.write(context, shape)
            }
        }
        SettingsNote(res(R.string.screen_shape_summary))

        SectionHeader(res(R.string.appearance_section_display))

        SettingsButton(
            title = res(R.string.appearance_dark_mode_title),
            summary = darkLabels.getOrElse(darkValues.indexOf(darkMode)) { darkMode },
        ) { showDarkPicker = true }

        SettingsButton(
            title = res(R.string.appearance_dpi_label),
            summary = if (dpi.isEmpty() || dpi == "0") {
                res(R.string.appearance_dpi_default)
            } else dpi,
        ) { showDpiDialog = true }

        SectionHeader(res(R.string.appearance_section_blur))

        SettingsButton(
            title = res(R.string.pref_blur_mode_title),
            summary = blurLabels.getOrElse(blurValues.indexOf(blurMode)) { blurMode },
        ) { showBlurPicker = true }

        SettingsSlider(
            title = res(R.string.pref_blur_radius_title),
            value = blurRadius,
            range = 2..25,
            valueText = "$blurRadius",
            onChange = {
                blurRadius = it
                prefs.edit().putInt("blur_radius", it).apply()
            },
        )
    }

    if (showDarkPicker) {
        SettingsPickerDialog(
            title = stringResource(R.string.appearance_dark_mode_title),
            labels = darkLabels,
            selectedIndex = darkValues.indexOf(darkMode).coerceAtLeast(0),
            onSelect = { index ->
                showDarkPicker = false
                darkMode = darkValues[index]
                prefs.edit().putString("dark_mode", darkMode).apply()
                AppCompatDelegate.setDefaultNightMode(when (darkMode) {
                    "on"  -> AppCompatDelegate.MODE_NIGHT_YES
                    "off" -> AppCompatDelegate.MODE_NIGHT_NO
                    else  -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                })
            },
            onDismiss = { showDarkPicker = false },
        )
    }

    if (showBlurPicker) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_blur_mode_title),
            labels = blurLabels,
            selectedIndex = blurValues.indexOf(blurMode).coerceAtLeast(0),
            onSelect = { index ->
                showBlurPicker = false
                blurMode = blurValues[index]
                prefs.edit().putString("blur_mode", blurMode).apply()
            },
            onDismiss = { showBlurPicker = false },
        )
    }

    if (showDpiDialog) {
        SettingsTextDialog(
            title = stringResource(R.string.appearance_dpi_label),
            initial = dpi,
            numeric = true,
            onConfirm = { raw ->
                showDpiDialog = false
                dpi = if (raw.isBlank()) "0" else raw
                prefs.edit().putString("app_dpi_override", dpi).apply()
                restartForDpi(context)
            },
            onDismiss = { showDpiDialog = false },
        )
    }
}

/** DPI is applied per-activity, so a restart is required (same behaviour as before). */
private fun restartForDpi(context: Context) {
    val act = context as? Activity ?: return
    val restart = act.packageManager
        .getLaunchIntentForPackage(act.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    act.finish()
    if (restart != null) act.safeStartActivity(restart)
}
