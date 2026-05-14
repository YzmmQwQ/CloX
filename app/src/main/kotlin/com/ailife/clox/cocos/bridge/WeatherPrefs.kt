package com.ailife.clox.cocos.bridge

import android.content.Context
import androidx.preference.PreferenceManager

object WeatherPrefs {

    const val KEY_ENABLED           = "weather_enabled"
    const val KEY_PROVIDER          = "weather_provider"
    const val KEY_INTERVAL_MINUTES  = "weather_interval_minutes"
    const val KEY_AUTO_LANGUAGE     = "weather_auto_language"
    const val KEY_ROUND_TEMP        = "weather_round_temp"
    const val KEY_WEATHERAPI_KEY    = "weather_weatherapi_key"
    const val KEY_OWM_KEY           = "weather_owm_key"

    // Location — primary
    const val KEY_LAT               = "location_lat"
    const val KEY_LON               = "location_lon"
    // Primary city name uses the same key as LiveDataHelper location_name_1
    const val KEY_CITY              = "location_name_1"
    const val KEY_AUTO_UPDATE_LOC   = "location_auto_update"   // refresh coords via IP on every fetch

    // Additional 4 timezone slots — city names location_name_2..5, offsets location_timezone_1..4
    // (read by LiveDataHelper.locationSettingsJson, edited in LocationSettingsFragment)

    fun enabled(ctx: Context)         = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun provider(ctx: Context)        = prefs(ctx).getString(KEY_PROVIDER, "metno") ?: "metno"
    fun intervalMs(ctx: Context): Long {
        val mins = prefs(ctx).getString(KEY_INTERVAL_MINUTES, "30")?.toLongOrNull() ?: 30L
        return mins * 60_000L
    }
    fun autoLanguage(ctx: Context)    = prefs(ctx).getBoolean(KEY_AUTO_LANGUAGE, true)
    fun roundTemp(ctx: Context)       = prefs(ctx).getBoolean(KEY_ROUND_TEMP, false)
    fun weatherApiKey(ctx: Context)   = prefs(ctx).getString(KEY_WEATHERAPI_KEY, "") ?: ""
    fun owmApiKey(ctx: Context)       = prefs(ctx).getString(KEY_OWM_KEY, "") ?: ""
    // Stored as String so EditTextPreference in LocationSettingsFragment works without conversion.
    fun lat(ctx: Context)             = prefs(ctx).getString(KEY_LAT, null)?.toDoubleOrNull() ?: 0.0
    fun lon(ctx: Context)             = prefs(ctx).getString(KEY_LON, null)?.toDoubleOrNull() ?: 0.0
    fun city(ctx: Context)            = prefs(ctx).getString(KEY_CITY, "") ?: ""
    fun autoUpdateLocation(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO_UPDATE_LOC, false)

    fun savePrimaryLocation(ctx: Context, lat: Double, lon: Double, city: String) {
        prefs(ctx).edit()
            .putString(KEY_LAT, "%.6f".format(lat))
            .putString(KEY_LON, "%.6f".format(lon))
            .putString(KEY_CITY, city)
            .apply()
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
}
