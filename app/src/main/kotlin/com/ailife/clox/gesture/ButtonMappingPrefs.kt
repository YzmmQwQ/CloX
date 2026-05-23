package com.ailife.clox.gesture

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager

object ButtonMappingPrefs {
    const val KEY_KEYCODE = "btn_map_keycode"
    const val KEY_SINGLE  = "btn_single_press_action"
    const val KEY_DOUBLE  = "btn_double_press_action"
    const val KEY_LONG    = "btn_long_press_action"

    fun getMappedKeyCode(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx).getInt(KEY_KEYCODE, 0)

    fun setMappedKeyCode(ctx: Context, keyCode: Int) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putInt(KEY_KEYCODE, keyCode).apply()

    fun clearMappedKeyCode(ctx: Context) = setMappedKeyCode(ctx, 0)

    fun getSingleAction(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_SINGLE, "nothing") ?: "nothing"

    fun getDoubleAction(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_DOUBLE, "nothing") ?: "nothing"

    fun getLongAction(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_LONG, "nothing") ?: "nothing"

    fun keyCodeToName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP   -> "Volume Up"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume Down"
        KeyEvent.KEYCODE_CAMERA      -> "Camera"
        KeyEvent.KEYCODE_MENU        -> "Menu"
        KeyEvent.KEYCODE_SEARCH      -> "Search"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
        KeyEvent.KEYCODE_MEDIA_NEXT  -> "Next Track"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS  -> "Previous Track"
        0 -> "None"
        else -> "Button ($keyCode)"
    }
}
