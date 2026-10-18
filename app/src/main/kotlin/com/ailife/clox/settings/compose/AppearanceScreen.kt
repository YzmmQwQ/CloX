package com.ailife.clox.settings.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.ailife.clox.R
import com.ailife.clox.util.safeStartActivity

/**
 * Wear Material 3 version of the Appearance settings page.
 *
 * First Compose screen of the migration: pure-black screen, M3 buttons as
 * setting rows, M3 dialogs for pickers, and the Wear M3 slider for the blur
 * radius. Shares the same SharedPreferences keys as the old View screen.
 */
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var darkMode by remember { mutableStateOf(prefs.getString("dark_mode", "system") ?: "system") }
    var dpi by remember { mutableStateOf(prefs.getString("app_dpi_override", "0") ?: "0") }
    var blurMode by remember { mutableStateOf(prefs.getString("blur_mode", "BLURVIEW") ?: "BLURVIEW") }
    var blurRadius by remember { mutableStateOf(prefs.getInt("blur_radius", 15).coerceIn(2, 25)) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader(stringResource(R.string.appearance_section_display))

        SettingRow(
            title = stringResource(R.string.appearance_dark_mode_title),
            value = darkLabels.getOrElse(darkValues.indexOf(darkMode)) { darkMode },
        ) { showDarkPicker = true }

        SettingRow(
            title = stringResource(R.string.appearance_dpi_label),
            value = if (dpi.isEmpty() || dpi == "0") {
                stringResource(R.string.appearance_dpi_default)
            } else dpi,
        ) { showDpiDialog = true }

        SectionHeader(stringResource(R.string.appearance_section_blur))

        SettingRow(
            title = stringResource(R.string.pref_blur_mode_title),
            value = blurLabels.getOrElse(blurValues.indexOf(blurMode)) { blurMode },
        ) { showBlurPicker = true }

        Text(
            text = "${stringResource(R.string.pref_blur_radius_title)} — $blurRadius",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
        Slider(
            value = blurRadius,
            onValueChange = {
                blurRadius = it
                prefs.edit().putInt("blur_radius", it).apply()
            },
            valueProgression = 2..25,
        )
    }

    if (showDarkPicker) {
        PickerDialog(
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
        PickerDialog(
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
        DpiDialog(
            initial = dpi,
            onDismiss = { showDpiDialog = false },
            onConfirm = { raw ->
                showDpiDialog = false
                dpi = raw
                prefs.edit().putString("app_dpi_override", raw).apply()
                restartForDpi(context)
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        label = { Text(text = title) },
    )
}

/** Single-choice picker using the button-less Wear M3 dialog + full-width buttons. */
@Composable
private fun PickerDialog(
    title: String,
    labels: Array<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
    ) {
        labels.forEachIndexed { index, label ->
            item {
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (index == selectedIndex) {
                        androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors()
                    } else {
                        androidx.wear.compose.material3.ButtonDefaults.buttonColors()
                    },
                    label = { Text(text = label) },
                )
            }
        }
    }
}

@Composable
private fun DpiDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.appearance_dpi_label)) },
    ) {
        item {
            BasicTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        item {
            Button(
                onClick = { onConfirm(if (text.isBlank()) "0" else text) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(android.R.string.ok)) },
            )
        }
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
