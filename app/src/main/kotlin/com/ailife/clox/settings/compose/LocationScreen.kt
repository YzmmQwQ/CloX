package com.ailife.clox.settings.compose

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.ElevationApi
import com.ailife.clox.cocos.bridge.WeatherPrefs
import kotlinx.coroutines.launch

private data class LocationSlot(
    val cityKey: String,
    val tzKey: String,
    val cityTitleRes: Int,
    val tzTitleRes: Int,
)

private data class TextEdit(
    val title: String,
    val initial: String,
    val numeric: Boolean,
    val onConfirm: (String) -> Unit,
)

@Composable
fun LocationScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val scope = rememberCoroutineScope()

    val tzEntries = stringArrayResource(R.array.timezone_offset_entries)
    val tzValues = stringArrayResource(R.array.timezone_offset_values)

    var city by remember { mutableStateOf(prefs.getString(WeatherPrefs.KEY_CITY, "") ?: "") }
    var lat by remember { mutableStateOf(prefs.getString(WeatherPrefs.KEY_LAT, "") ?: "") }
    var lon by remember { mutableStateOf(prefs.getString(WeatherPrefs.KEY_LON, "") ?: "") }
    var elevation by remember {
        mutableStateOf(prefs.getString(WeatherPrefs.KEY_ELEVATION, "") ?: "")
    }
    var gpsStatus by remember { mutableStateOf<String?>(null) }
    var autoUpdate by remember { mutableStateOf(WeatherPrefs.autoUpdateLocation(ctx)) }

    val slots = remember {
        listOf(
            LocationSlot("location_name_2", "location_timezone_1",
                R.string.pref_location_slot2_city, R.string.pref_location_slot2_tz),
            LocationSlot("location_name_3", "location_timezone_2",
                R.string.pref_location_slot3_city, R.string.pref_location_slot3_tz),
            LocationSlot("location_name_4", "location_timezone_3",
                R.string.pref_location_slot4_city, R.string.pref_location_slot4_tz),
            LocationSlot("location_name_5", "location_timezone_4",
                R.string.pref_location_slot5_city, R.string.pref_location_slot5_tz),
        )
    }
    var slotCities by remember {
        mutableStateOf(slots.associate { it.cityKey to (prefs.getString(it.cityKey, "") ?: "") })
    }
    var slotTzs by remember {
        mutableStateOf(slots.associate { it.tzKey to (prefs.getString(it.tzKey, "0") ?: "0") })
    }

    var textEdit by remember { mutableStateOf<TextEdit?>(null) }
    var tzEdit by remember { mutableStateOf<LocationSlot?>(null) }

    fun applyGpsResult(la: Double, lo: Double) {
        WeatherPrefs.savePrimaryLocation(ctx, la, lo, WeatherPrefs.city(ctx))
        lat = "%.6f".format(la)
        lon = "%.6f".format(lo)
        Toast.makeText(ctx, "%.4f, %.4f".format(la, lo), Toast.LENGTH_SHORT).show()
        scope.launch {
            ElevationApi.fetch(la, lo)?.let {
                WeatherPrefs.saveElevation(ctx, it)
                elevation = it.toString()
            }
        }
    }

    fun fetchGpsOnce() {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(ctx, R.string.pref_location_gps_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("MissingPermission")
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null) {
            applyGpsResult(last.latitude, last.longitude)
            return
        }
        gpsStatus = ctx.getString(R.string.pref_location_gps_waiting)
        @Suppress("MissingPermission")
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, object : LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                lm.removeUpdates(this)
                applyGpsResult(loc.latitude, loc.longitude)
                gpsStatus = null
            }
        })
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchGpsOnce()
        else Toast.makeText(ctx, R.string.pref_location_gps_denied, Toast.LENGTH_SHORT).show()
    }

    WearSettingsScreen(title = stringResource(R.string.pref_location_title)) {
        SectionHeader(res(R.string.pref_location_category_primary))
        SettingsButton(
            title = res(R.string.pref_location_city_title),
            summary = city.ifBlank { res(R.string.pref_location_city_summary) },
        ) {
            textEdit = TextEdit(ctx.getString(R.string.pref_location_city_title), city, false) { v ->
                city = v
                prefs.edit().putString(WeatherPrefs.KEY_CITY, v).apply()
            }
        }
        SettingsButton(
            title = res(R.string.pref_location_lat_title),
            summary = lat.ifBlank { res(R.string.pref_location_lat_summary) },
        ) {
            textEdit = TextEdit(ctx.getString(R.string.pref_location_lat_title), lat, false) { v ->
                val d = v.toDoubleOrNull()
                if (d == null || d < -90 || d > 90) {
                    Toast.makeText(ctx, R.string.pref_location_lat_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    lat = v
                    prefs.edit().putString(WeatherPrefs.KEY_LAT, v).apply()
                }
            }
        }
        SettingsButton(
            title = res(R.string.pref_location_lon_title),
            summary = lon.ifBlank { res(R.string.pref_location_lon_summary) },
        ) {
            textEdit = TextEdit(ctx.getString(R.string.pref_location_lon_title), lon, false) { v ->
                val d = v.toDoubleOrNull()
                if (d == null || d < -180 || d > 180) {
                    Toast.makeText(ctx, R.string.pref_location_lon_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    lon = v
                    prefs.edit().putString(WeatherPrefs.KEY_LON, v).apply()
                }
            }
        }
        SettingsButton(
            title = res(R.string.pref_location_elevation_title),
            summary = if (elevation.isBlank()) {
                res(R.string.pref_location_elevation_summary)
            } else {
                res(R.string.pref_location_elevation_format, elevation)
            },
        ) {
            textEdit = TextEdit(ctx.getString(R.string.pref_location_elevation_title), elevation, false) { v ->
                if (v.isBlank()) {
                    elevation = ""
                    prefs.edit().remove(WeatherPrefs.KEY_ELEVATION).apply()
                } else if (v.toDoubleOrNull() == null) {
                    Toast.makeText(
                        ctx, R.string.pref_location_elevation_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    elevation = v
                    prefs.edit().putString(WeatherPrefs.KEY_ELEVATION, v).apply()
                }
            }
        }
        SettingsButton(
            title = res(R.string.pref_location_gps_title),
            summary = gpsStatus ?: res(R.string.pref_location_gps_summary),
        ) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) fetchGpsOnce()
            else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        SettingsSwitch(
            title = res(R.string.pref_location_auto_title),
            summary = res(R.string.pref_location_auto_summary),
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                prefs.edit().putBoolean(WeatherPrefs.KEY_AUTO_UPDATE_LOC, it).apply()
            },
        )

        SectionHeader(res(R.string.pref_location_category_extra))
        for (slot in slots) {
            val slotCity = slotCities[slot.cityKey].orEmpty()
            SettingsButton(
                title = res(slot.cityTitleRes),
                summary = slotCity.ifBlank { res(R.string.location_slot_placeholder) },
            ) {
                textEdit = TextEdit(ctx.getString(slot.cityTitleRes), slotCity, false) { v ->
                    slotCities = slotCities + (slot.cityKey to v)
                    prefs.edit().putString(slot.cityKey, v).apply()
                }
            }
            val tz = slotTzs[slot.tzKey] ?: "0"
            SettingsButton(
                title = res(slot.tzTitleRes),
                summary = tzEntries.getOrElse(tzValues.indexOf(tz)) { tz },
            ) { tzEdit = slot }
        }
    }

    textEdit?.let { edit ->
        SettingsTextDialog(
            title = edit.title,
            initial = edit.initial,
            numeric = edit.numeric,
            onConfirm = { value ->
                textEdit = null
                edit.onConfirm(value)
            },
            onDismiss = { textEdit = null },
        )
    }
    tzEdit?.let { slot ->
        val current = slotTzs[slot.tzKey] ?: "0"
        SettingsPickerDialog(
            title = stringResource(slot.tzTitleRes),
            labels = tzEntries,
            selectedIndex = tzValues.indexOf(current).coerceAtLeast(0),
            onSelect = { index ->
                tzEdit = null
                val value = tzValues[index]
                slotTzs = slotTzs + (slot.tzKey to value)
                prefs.edit().putString(slot.tzKey, value).apply()
            },
            onDismiss = { tzEdit = null },
        )
    }
}
