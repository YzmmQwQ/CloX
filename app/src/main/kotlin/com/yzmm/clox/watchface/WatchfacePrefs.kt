package com.yzmm.clox.watchface

import android.content.Context
import androidx.preference.PreferenceManager

object WatchfacePrefs {
    const val KEY_ACTIVE_PATH = "watchface_path"
    private const val KEY_PINNED = "watchface_pinned_set"
    const val KEY_SORT_ORDER = "watchface_sort_order"
    const val KEY_SELECTOR_MODE = "watchface_selector_mode"
    const val KEY_AUTO_CHANGE_ON_SCREEN_ON = "wf_auto_change_on_screen_on"
    const val KEY_AUTO_CHANGE_INTERVAL = "wf_auto_change_interval"
    private const val KEY_AUTO_CHANGE_LAST_TIME = "wf_auto_change_last_time"

    fun getActivePath(ctx: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_ACTIVE_PATH, null)

    fun setActivePath(ctx: Context, path: String) {
        // commit(), not apply(): a face switch can be followed within
        // milliseconds by a process restart (engine recovery path), and
        // Runtime.exit() discards queued apply() writes — the freshly picked
        // face silently reverts to the previous one on relaunch.
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().putString(KEY_ACTIVE_PATH, path).commit()
    }

    fun getPinned(ctx: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    fun setPinned(ctx: Context, path: String, pinned: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val set = (prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()).toMutableSet()
        if (pinned) set.add(path) else set.remove(path)
        prefs.edit().putStringSet(KEY_PINNED, set).apply()
    }

    fun isPinned(ctx: Context, path: String): Boolean = path in getPinned(ctx)

    fun getSortOrder(ctx: Context): SortOrder {
        val v = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(KEY_SORT_ORDER, "name") ?: "name"
        return if (v == "modified") SortOrder.MODIFIED else SortOrder.NAME
    }

    fun getSelectorMode(ctx: Context): SelectorMode {
        val v = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(KEY_SELECTOR_MODE, "pager") ?: "pager"
        return if (v == "grid") SelectorMode.GRID else SelectorMode.PAGER
    }

    fun getAutoChangeOnScreenOn(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(KEY_AUTO_CHANGE_ON_SCREEN_ON, false)

    fun getAutoChangeInterval(ctx: Context): AutoChangeInterval {
        val v = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(KEY_AUTO_CHANGE_INTERVAL, "none") ?: "none"
        return AutoChangeInterval.fromValue(v)
    }

    fun getAutoChangeLastTime(ctx: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getLong(KEY_AUTO_CHANGE_LAST_TIME, 0L)

    fun setAutoChangeLastTime(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().putLong(KEY_AUTO_CHANGE_LAST_TIME, System.currentTimeMillis()).apply()
    }

    enum class SortOrder { NAME, MODIFIED }
    enum class SelectorMode { PAGER, GRID }

    enum class AutoChangeInterval(val value: String, val millis: Long) {
        NONE("none", 0L),
        HOURLY("hourly", 60 * 60 * 1000L),
        DAILY("daily", 24 * 60 * 60 * 1000L);

        companion object {
            fun fromValue(v: String) = entries.firstOrNull { it.value == v } ?: NONE
        }
    }
}
