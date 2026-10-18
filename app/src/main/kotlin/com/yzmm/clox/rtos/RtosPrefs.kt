package com.yzmm.clox.rtos

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object RtosPrefs {

    const val KEY_ENABLED            = "rtos_enabled"
    const val KEY_MODE               = "rtos_mode"           // "watchface_only" | "full"
    const val KEY_PASSWORD_TYPE      = "rtos_password_type"  // "keypad" | "morse"
    const val KEY_PASSWORD_HASH      = "rtos_password_hash"
    const val KEY_PASSWORD_SEQ       = "rtos_password_seq"   // comma-separated ints, runtime use
    const val KEY_AUTO_ENTER         = "rtos_auto_enter"     // on screen off
    const val KEY_SHOW_LAUNCHER_ICON = "rtos_launcher_icon"
    const val KEY_WHITELIST          = "rtos_whitelist"      // Set<String> pkg/activity keys
    const val KEY_BLOCK_APPS         = "rtos_block_apps"
    const val KEY_QS_TILE_ADDED      = "rtos_qs_tile_added"
    const val KEY_WAS_ACTIVE         = "rtos_was_active"   // persists across process death

    const val MODE_WATCHFACE_ONLY = "watchface_only"
    const val MODE_FULL           = "full"

    const val PASSWORD_KEYPAD = "keypad"
    const val PASSWORD_MORSE  = "morse"

    fun get(ctx: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    fun isEnabled(ctx: Context)     = get(ctx).getBoolean(KEY_ENABLED, false)
    fun mode(ctx: Context)          = get(ctx).getString(KEY_MODE, MODE_WATCHFACE_ONLY) ?: MODE_WATCHFACE_ONLY
    fun passwordType(ctx: Context)  = get(ctx).getString(KEY_PASSWORD_TYPE, PASSWORD_KEYPAD) ?: PASSWORD_KEYPAD
    fun passwordHash(ctx: Context)  = get(ctx).getString(KEY_PASSWORD_HASH, null)
    fun autoEnter(ctx: Context)     = get(ctx).getBoolean(KEY_AUTO_ENTER, false)

    fun blockApps(ctx: Context) = get(ctx).getBoolean(KEY_BLOCK_APPS, false)

    fun whitelist(ctx: Context): Set<String> =
        get(ctx).getStringSet(KEY_WHITELIST, null) ?: emptySet()

    fun setEnabled(ctx: Context, enabled: Boolean) =
        get(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun passwordSequence(ctx: Context): List<Int> {
        val raw = get(ctx).getString(KEY_PASSWORD_SEQ, null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.toIntOrNull() }
    }

    fun setPassword(ctx: Context, sequence: List<Int>, hash: String) {
        get(ctx).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putString(KEY_PASSWORD_SEQ, sequence.joinToString(","))
            .apply()
    }

    fun setPasswordHash(ctx: Context, hash: String?) =
        get(ctx).edit().putString(KEY_PASSWORD_HASH, hash).apply()

    fun setWhitelist(ctx: Context, set: Set<String>) =
        get(ctx).edit().putStringSet(KEY_WHITELIST, set).apply()
}
