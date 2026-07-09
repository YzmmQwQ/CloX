package com.ailife.clox.debug

import android.content.Context
import androidx.preference.PreferenceManager

object DebugPrefs {

    const val KEY_IGNORE_LUA_ERRORS = "debug_ignore_lua_errors"

    /**
     * When true, Lua evaluation failures and "tap action not found" dialogs are
     * swallowed instead of shown — for users who'd rather have a broken watchface
     * fail silently than see an error dialog.
     */
    fun ignoreLuaErrors(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_IGNORE_LUA_ERRORS, false)
}
