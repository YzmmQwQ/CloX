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

    fun load(ctx: Context) {
        val path = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("watchface_path", null)
        dynamicFps = WatchfaceConfigPrefs.dynamicFps(ctx, WatchfaceConfigPrefs.pathFor(path))
    }
}
