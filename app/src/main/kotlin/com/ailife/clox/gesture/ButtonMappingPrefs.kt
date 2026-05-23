package com.ailife.clox.gesture

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.ailife.clox.R

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

    fun keyCodeToName(ctx: Context, keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP   -> ctx.getString(R.string.btn_volume_up)
        KeyEvent.KEYCODE_VOLUME_DOWN -> ctx.getString(R.string.btn_volume_down)
        KeyEvent.KEYCODE_CAMERA      -> ctx.getString(R.string.btn_camera)
        KeyEvent.KEYCODE_MENU        -> ctx.getString(R.string.btn_menu)
        KeyEvent.KEYCODE_SEARCH      -> ctx.getString(R.string.btn_search)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ctx.getString(R.string.btn_play_pause)
        KeyEvent.KEYCODE_MEDIA_NEXT  -> ctx.getString(R.string.btn_next_track)
        KeyEvent.KEYCODE_MEDIA_PREVIOUS  -> ctx.getString(R.string.btn_prev_track)
        0 -> ctx.getString(R.string.btn_none)
        else -> ctx.getString(R.string.btn_format, keyCode)
    }
}
