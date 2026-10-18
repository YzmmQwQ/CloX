package com.ailife.clox.gesture

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.ailife.clox.R

/**
 * Storage for hardware-button → action mappings. Supports multiple mapped buttons: the set of
 * recorded keycodes lives in [KEY_KEYCODES] (CSV of ints), and each button stores its own
 * single/double/long action under [actionKey]. The "Open app" component (if any) is stored by
 * [GestureAppPref] keyed on that same action key.
 *
 * Legacy single-button prefs ([KEY_KEYCODE] + [KEY_SINGLE]/[KEY_DOUBLE]/[KEY_LONG]) are migrated
 * on first read.
 */
object ButtonMappingPrefs {
    // Multi-button storage
    const val KEY_KEYCODES = "btn_map_keycodes"   // CSV of recorded keycodes

    // Legacy single-button storage (kept for one-time migration)
    const val KEY_KEYCODE = "btn_map_keycode"
    const val KEY_SINGLE  = "btn_single_press_action"
    const val KEY_DOUBLE  = "btn_double_press_action"
    const val KEY_LONG    = "btn_long_press_action"

    const val PRESS_SINGLE = "single"
    const val PRESS_DOUBLE = "double"
    const val PRESS_LONG   = "long"

    /** Pref key holding the action string for a given button + press type. */
    fun actionKey(keyCode: Int, press: String) = "btn_${keyCode}_${press}_action"

    fun getMappedKeyCodes(ctx: Context): List<Int> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.getString(KEY_KEYCODES, null)?.let { csv ->
            return csv.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }
        }
        // Migrate a legacy single mapping, if present.
        val legacy = prefs.getInt(KEY_KEYCODE, 0)
        if (legacy != 0) {
            prefs.edit()
                .putString(KEY_KEYCODES, legacy.toString())
                .putString(actionKey(legacy, PRESS_SINGLE), prefs.getString(KEY_SINGLE, "nothing"))
                .putString(actionKey(legacy, PRESS_DOUBLE), prefs.getString(KEY_DOUBLE, "nothing"))
                .putString(actionKey(legacy, PRESS_LONG),   prefs.getString(KEY_LONG, "nothing"))
                .apply()
            return listOf(legacy)
        }
        return emptyList()
    }

    fun isMapped(ctx: Context, keyCode: Int): Boolean =
        keyCode != 0 && keyCode in getMappedKeyCodes(ctx)

    fun addKeyCode(ctx: Context, keyCode: Int) {
        if (keyCode == 0) return
        val codes = getMappedKeyCodes(ctx).toMutableList()
        if (keyCode in codes) return
        codes.add(keyCode)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit()
            .putString(KEY_KEYCODES, codes.joinToString(","))
            .putString(actionKey(keyCode, PRESS_SINGLE), "nothing")
            .putString(actionKey(keyCode, PRESS_DOUBLE), "nothing")
            .putString(actionKey(keyCode, PRESS_LONG),   "nothing")
            .apply()
    }

    fun removeKeyCode(ctx: Context, keyCode: Int) {
        val codes = getMappedKeyCodes(ctx).toMutableList()
        if (!codes.remove(keyCode)) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit()
            .putString(KEY_KEYCODES, codes.joinToString(","))
            .remove(actionKey(keyCode, PRESS_SINGLE))
            .remove(actionKey(keyCode, PRESS_DOUBLE))
            .remove(actionKey(keyCode, PRESS_LONG))
            .apply()
        // Also drop any "Open app" components for this button.
        listOf(PRESS_SINGLE, PRESS_DOUBLE, PRESS_LONG).forEach {
            GestureAppPref.clear(ctx, actionKey(keyCode, it))
        }
    }

    fun getAction(ctx: Context, keyCode: Int, press: String): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(actionKey(keyCode, press), "nothing") ?: "nothing"

    fun keyCodeToName(ctx: Context, keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP   -> ctx.getString(R.string.btn_volume_up)
        KeyEvent.KEYCODE_VOLUME_DOWN -> ctx.getString(R.string.btn_volume_down)
        KeyEvent.KEYCODE_CAMERA      -> ctx.getString(R.string.btn_camera)
        KeyEvent.KEYCODE_MENU        -> ctx.getString(R.string.btn_menu)
        KeyEvent.KEYCODE_SEARCH      -> ctx.getString(R.string.btn_search)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ctx.getString(R.string.btn_play_pause)
        KeyEvent.KEYCODE_MEDIA_NEXT  -> ctx.getString(R.string.btn_next_track)
        KeyEvent.KEYCODE_MEDIA_PREVIOUS  -> ctx.getString(R.string.btn_prev_track)
        KeyEvent.KEYCODE_STEM_PRIMARY -> ctx.getString(R.string.btn_stem_primary)
        KeyEvent.KEYCODE_STEM_1       -> ctx.getString(R.string.btn_stem_1)
        KeyEvent.KEYCODE_STEM_2       -> ctx.getString(R.string.btn_stem_2)
        KeyEvent.KEYCODE_STEM_3       -> ctx.getString(R.string.btn_stem_3)
        0 -> ctx.getString(R.string.btn_none)
        else -> ctx.getString(R.string.btn_format, keyCode)
    }
}
