package com.ailife.clox.gesture

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Storage for the app chosen by an "Open app" gesture/button action.
 *
 * Any action-holding pref key (e.g. "double_tap_action", "long_press_action", a swipe
 * action key, or a per-button press key) can carry an associated app component. The
 * component is stored under `${baseKey}_app` as a "package/activity" string. This keeps
 * one app-per-gesture without threading which gesture fired through the action dispatcher.
 */
object GestureAppPref {

    private fun appKey(baseKey: String) = "${baseKey}_app"

    /** "package/activity" for this action key, or null if none selected. */
    fun getComponent(ctx: Context, baseKey: String): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(appKey(baseKey), null)?.takeIf { it.isNotBlank() }

    fun setComponent(ctx: Context, baseKey: String, component: String) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(appKey(baseKey), component).apply()

    fun clear(ctx: Context, baseKey: String) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(appKey(baseKey)).apply()

    /** Human-readable label of the selected app, or null if unset / uninstalled. */
    fun getLabel(ctx: Context, baseKey: String): String? {
        val pkg = getComponent(ctx, baseKey)?.split("/", limit = 2)?.firstOrNull() ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }
}
