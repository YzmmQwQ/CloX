package com.yzmm.clox.overlay.global

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Preferences for the global (draw-over-other-apps) Quick Settings overlay and the
 * custom status-bar strip. Separate from [com.yzmm.clox.qs.QsPrefs] (in-launcher QS).
 */
object GlobalOverlayPrefs {

    const val GLOBAL_QS_ENABLED      = "global_qs_enabled"
    const val GLOBAL_QS_EDGE_SWIPE   = "global_qs_edge_swipe"
    const val GLOBAL_QS_EDGE_AREA    = "global_qs_edge_area_dp"
    const val GLOBAL_QS_AUTO_REPLACE = "global_qs_auto_replace"

    const val STATUS_BAR_OVERLAY_MODE = "status_bar_overlay_mode"

    // Horizontal inset (% of screen width, each side) for the status-bar content, to clear rounded corners.
    const val SB_INSET_PCT = "sb_inset_pct"
    const val DEFAULT_INSET_PCT = 10

    // status_bar_overlay_mode values
    const val SB_SHOW   = "show"    // always visible
    const val SB_SWIPE  = "swipe"   // hidden; swipe from top reveals it (auto-hides), swipe again → QS
    const val SB_HIDDEN = "hidden"  // always hidden

    // Which items the status-bar strip shows
    const val SB_ITEM_CLOCK     = "sb_item_clock"
    const val SB_ITEM_BATTERY   = "sb_item_battery"
    const val SB_ITEM_WIFI      = "sb_item_wifi"
    const val SB_ITEM_BLUETOOTH = "sb_item_bluetooth"
    const val SB_ITEM_DATA      = "sb_item_data"
    const val SB_ITEM_SPEED     = "sb_item_speed"
    const val SB_ITEM_NOTIFS    = "sb_item_notifs"

    const val DEFAULT_EDGE_AREA = 28   // dp
    const val SWIPE_REVEAL_MS   = 3500L // how long the bar stays revealed in swipe mode

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun globalEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(GLOBAL_QS_ENABLED, false)
    fun edgeSwipe(ctx: Context): Boolean     = prefs(ctx).getBoolean(GLOBAL_QS_EDGE_SWIPE, true)
    fun autoReplace(ctx: Context): Boolean   = prefs(ctx).getBoolean(GLOBAL_QS_AUTO_REPLACE, true)
    fun edgeAreaDp(ctx: Context): Int        = prefs(ctx).getInt(GLOBAL_QS_EDGE_AREA, DEFAULT_EDGE_AREA)
    fun statusBarMode(ctx: Context): String  = prefs(ctx).getString(STATUS_BAR_OVERLAY_MODE, SB_HIDDEN) ?: SB_HIDDEN

    fun itemClock(ctx: Context)     = prefs(ctx).getBoolean(SB_ITEM_CLOCK, true)
    fun itemBattery(ctx: Context)   = prefs(ctx).getBoolean(SB_ITEM_BATTERY, true)
    fun itemWifi(ctx: Context)      = prefs(ctx).getBoolean(SB_ITEM_WIFI, true)
    fun itemBluetooth(ctx: Context) = prefs(ctx).getBoolean(SB_ITEM_BLUETOOTH, true)
    fun itemData(ctx: Context)      = prefs(ctx).getBoolean(SB_ITEM_DATA, true)
    fun itemSpeed(ctx: Context)     = prefs(ctx).getBoolean(SB_ITEM_SPEED, false)
    fun itemNotifs(ctx: Context)    = prefs(ctx).getBoolean(SB_ITEM_NOTIFS, false)
    fun insetPct(ctx: Context): Int = prefs(ctx).getInt(SB_INSET_PCT, DEFAULT_INSET_PCT)
}
