package com.ailife.clox.cocos.bridge

import android.content.Context
import androidx.preference.PreferenceManager

object SensorsPrefs {

    const val KEY_CALENDAR      = "sensor_calendar_access"
    const val KEY_ACCELEROMETER = "sensor_accelerometer"
    const val KEY_COMPASS       = "sensor_compass"
    const val KEY_BAROMETER     = "sensor_barometer"
    const val KEY_GYROSCOPE     = "sensor_gyroscope"

    fun calendarEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_CALENDAR, false)

    fun accelerometerEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_ACCELEROMETER, false)

    fun compassEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_COMPASS, false)

    fun barometerEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_BAROMETER, false)

    fun gyroscopeEnabled(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_GYROSCOPE, false)

    fun sensorEnabled(ctx: Context, sensorName: String) = when (sensorName) {
        "accelerometer" -> accelerometerEnabled(ctx)
        "compass"       -> compassEnabled(ctx)
        "barometer"     -> barometerEnabled(ctx)
        "gyroscope"     -> gyroscopeEnabled(ctx)
        else            -> false
    }

    fun setCalendarEnabled(ctx: Context, value: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(KEY_CALENDAR, value).apply()
    }
}
