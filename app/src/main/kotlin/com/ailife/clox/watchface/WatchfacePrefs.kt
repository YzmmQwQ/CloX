package com.ailife.clox.watchface

import android.content.Context
import androidx.preference.PreferenceManager

object WatchfacePrefs {
    const val KEY_ACTIVE_PATH = "watchface_path"
    private const val KEY_PINNED = "watchface_pinned_set"
    const val KEY_SORT_ORDER = "watchface_sort_order"
    const val KEY_SELECTOR_MODE = "watchface_selector_mode"

    fun getActivePath(ctx: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_ACTIVE_PATH, null)

    fun setActivePath(ctx: Context, path: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().putString(KEY_ACTIVE_PATH, path).apply()
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

    enum class SortOrder { NAME, MODIFIED }
    enum class SelectorMode { PAGER, GRID }
}
