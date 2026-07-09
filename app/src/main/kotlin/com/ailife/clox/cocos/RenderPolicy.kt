package com.ailife.clox.cocos

import android.content.Context
import androidx.preference.PreferenceManager
import com.ailife.clox.watchface.WatchfaceConfigPrefs

/**
 * Cached render-policy flags, readable from per-touch-event hot paths without
 * a SharedPreferences lookup. Reload on process start and whenever the active
 * face or its config changes (WatchfaceBridgeManager's prefs listener).
 */
object RenderPolicy {

    /**
     * The ACTIVE face's effective "Dynamic FPS" config (per-face override or
     * app-wide default, default on): idle faces render at 1-2 FPS, touches
     * boost to full rate, and the face is capped at 1 FPS under the expanded
     * app list. Off restores the pre-feature behavior — the face's configured
     * rate at all times.
     */
    @Volatile var dynamicFps: Boolean = WatchfaceConfigPrefs.DEF_DYNAMIC_FPS
        private set

    /**
     * AOD power-save (dim) state, mirrored from the "watchface_aod_enabled"
     * pref AodManager writes on every dim/undim. While true, the Lua engine
     * runs the face at 1 FPS — and the render-wakeup side channels (touch
     * boost, per-sensor-event frame kicks) are suppressed so they can't drive
     * frames past that interval.
     */
    @Volatile var aodDimmed: Boolean = false
        private set

    fun load(ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val path = prefs.getString("watchface_path", null)
        dynamicFps = WatchfaceConfigPrefs.dynamicFps(ctx, WatchfaceConfigPrefs.pathFor(path))
        aodDimmed = prefs.getBoolean("watchface_aod_enabled", false)
    }
}
