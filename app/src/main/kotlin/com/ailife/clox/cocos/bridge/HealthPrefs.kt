package com.ailife.clox.cocos.bridge

import android.content.Context
import androidx.preference.PreferenceManager

object HealthPrefs {

    const val KEY_STEPS_ENABLED    = "health_steps_enabled"
    const val KEY_HR_ENABLED       = "health_hr_enabled"
    const val KEY_SPO2_ENABLED     = "health_spo2_enabled"
    const val KEY_SLEEP_ENABLED    = "health_sleep_enabled"
    const val KEY_STRESS_ENABLED   = "health_stress_enabled"
    const val KEY_INSTANT_STEPS    = "health_instant_steps"
    const val KEY_INSTANT_HR_SPO2  = "health_instant_hr_spo2"
    const val KEY_INTERVAL_MINUTES = "health_interval_minutes"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun stepsEnabled(ctx: Context)   = prefs(ctx).getBoolean(KEY_STEPS_ENABLED,   false)
    fun hrEnabled(ctx: Context)      = prefs(ctx).getBoolean(KEY_HR_ENABLED,       false)
    fun spo2Enabled(ctx: Context)    = prefs(ctx).getBoolean(KEY_SPO2_ENABLED,     false)
    fun sleepEnabled(ctx: Context)   = prefs(ctx).getBoolean(KEY_SLEEP_ENABLED,    false)
    fun stressEnabled(ctx: Context)  = prefs(ctx).getBoolean(KEY_STRESS_ENABLED,   false)
    fun instantSteps(ctx: Context)   = prefs(ctx).getBoolean(KEY_INSTANT_STEPS,    false)
    fun instantHrSpo2(ctx: Context)  = prefs(ctx).getBoolean(KEY_INSTANT_HR_SPO2,  false)

    fun intervalMs(ctx: Context): Long {
        val minutes = prefs(ctx).getString(KEY_INTERVAL_MINUTES, "10")?.toLongOrNull() ?: 10L
        return minutes * 60 * 1000L
    }
}
