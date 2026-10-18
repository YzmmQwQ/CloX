package com.yzmm.clox.settings.compose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.cocos.bridge.SensorsPrefs
import com.yzmm.clox.util.safeStartActivity

@Composable
fun SensorsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val sensorManager = remember { ctx.getSystemService(SensorManager::class.java) }

    var calendar by remember { mutableStateOf(prefs.getBoolean(SensorsPrefs.KEY_CALENDAR, false)) }
    var accel by remember { mutableStateOf(prefs.getBoolean(SensorsPrefs.KEY_ACCELEROMETER, false)) }
    var compass by remember { mutableStateOf(prefs.getBoolean(SensorsPrefs.KEY_COMPASS, false)) }
    var barometer by remember { mutableStateOf(prefs.getBoolean(SensorsPrefs.KEY_BAROMETER, false)) }
    var gyro by remember { mutableStateOf(prefs.getBoolean(SensorsPrefs.KEY_GYROSCOPE, false)) }

    var showCalendarSettings by remember { mutableStateOf(false) }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            calendar = true
            prefs.edit().putBoolean(SensorsPrefs.KEY_CALENDAR, true).apply()
        } else {
            val activity = ctx as? android.app.Activity
            val canAsk = activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.READ_CALENDAR) == true
            if (canAsk) {
                Toast.makeText(
                    ctx, R.string.pref_calendar_permission_denied, Toast.LENGTH_SHORT).show()
            } else {
                showCalendarSettings = true
            }
        }
    }

    fun hasSensor(type: Int) = sensorManager.getDefaultSensor(type) != null

    val accelAvailable = hasSensor(Sensor.TYPE_ACCELEROMETER)
    val compassAvailable = hasSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val barometerAvailable = hasSensor(Sensor.TYPE_PRESSURE)
    val gyroAvailable = hasSensor(Sensor.TYPE_GYROSCOPE)
    val gyroSimulated = !gyroAvailable && hasSensor(Sensor.TYPE_ACCELEROMETER)

    WearSettingsScreen(title = stringResource(R.string.pref_sensors_title)) {
        SectionHeader(res(R.string.sensors_section_data))
        SettingsSwitch(
            title = res(R.string.pref_calendar_title),
            summary = res(R.string.pref_calendar_summary),
            checked = calendar,
            onCheckedChange = { checked ->
                if (!checked) {
                    calendar = false
                    prefs.edit().putBoolean(SensorsPrefs.KEY_CALENDAR, false).apply()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        calendar = true
                        prefs.edit().putBoolean(SensorsPrefs.KEY_CALENDAR, true).apply()
                    } else {
                        calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    }
                }
            },
        )

        SectionHeader(res(R.string.pref_sensors_category_motion))
        SettingsSwitch(
            title = res(R.string.pref_accelerometer_title),
            summary = if (accelAvailable) res(R.string.pref_accelerometer_summary)
                      else res(R.string.pref_sensor_unavailable),
            checked = accel && accelAvailable,
            enabled = accelAvailable,
            onCheckedChange = {
                accel = it
                prefs.edit().putBoolean(SensorsPrefs.KEY_ACCELEROMETER, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_compass_title),
            summary = if (compassAvailable) res(R.string.pref_compass_summary)
                      else res(R.string.pref_sensor_unavailable),
            checked = compass && compassAvailable,
            enabled = compassAvailable,
            onCheckedChange = {
                compass = it
                prefs.edit().putBoolean(SensorsPrefs.KEY_COMPASS, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_barometer_title),
            summary = if (barometerAvailable) res(R.string.pref_barometer_summary)
                      else res(R.string.pref_sensor_unavailable),
            checked = barometer && barometerAvailable,
            enabled = barometerAvailable,
            onCheckedChange = {
                barometer = it
                prefs.edit().putBoolean(SensorsPrefs.KEY_BAROMETER, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.pref_gyroscope_title),
            summary = when {
                gyroAvailable -> res(R.string.pref_gyroscope_summary)
                gyroSimulated -> res(R.string.pref_gyroscope_simulated)
                else -> res(R.string.pref_sensor_unavailable)
            },
            checked = gyro && (gyroAvailable || gyroSimulated),
            enabled = gyroAvailable || gyroSimulated,
            onCheckedChange = {
                gyro = it
                prefs.edit().putBoolean(SensorsPrefs.KEY_GYROSCOPE, it).apply()
            },
        )
    }

    if (showCalendarSettings) {
        SettingsConfirmDialog(
            title = stringResource(R.string.pref_calendar_permission_denied),
            message = stringResource(R.string.dialog_calendar_perm_message),
            confirmLabel = stringResource(R.string.btn_open_settings),
            onConfirm = {
                ctx.safeStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", ctx.packageName, null)
                })
            },
            onDismiss = { showCalendarSettings = false },
        )
    }
}
