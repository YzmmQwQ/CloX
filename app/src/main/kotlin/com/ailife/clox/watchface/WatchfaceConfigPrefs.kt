package com.ailife.clox.watchface

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Per-watchface display configuration — scale, X/Y offset and max FPS — persisted
 * in the default SharedPreferences, namespaced by a hash of the watchface path so
 * every face keeps its own settings.
 *
 * Writing any value trips the OnSharedPreferenceChangeListener in
 * [com.ailife.clox.cocos.bridge.WatchfaceBridgeManager], which re-pushes the
 * "settings" snapshot to the Lua engine — giving the config dialog a live preview
 * for free. The built-in default face (no path) is keyed under [DEFAULT_PATH].
 */
object WatchfaceConfigPrefs {

    const val PREFIX = "wf_cfg_"
    const val DEFAULT_PATH = "__default__"

    // Defaults + ranges (also used by the config dialog sliders).
    const val DEF_SCALE = 1.0f
    const val DEF_OFFSET = 0
    const val DEF_MAX_FPS = 25
    const val DEF_UNLIMITED = false

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.0f
    const val OFFSET_RANGE = 200            // ± pixels

    /** FPS choices offered in the dialog (paired with the "unlimited" flag). */
    val FPS_CHOICES = intArrayOf(15, 25, 60)

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun hash(path: String): String = Integer.toHexString(path.hashCode())
    private fun key(path: String, field: String) = "$PREFIX${hash(path)}_$field"

    /** True for any per-watchface config key (used to trigger a settings re-push). */
    fun isConfigKey(key: String?): Boolean = key != null && key.startsWith(PREFIX)

    /** The config-namespace path for an active face path (null/empty → default face). */
    fun pathFor(activePath: String?): String =
        if (activePath.isNullOrEmpty()) DEFAULT_PATH else activePath

    // ── getters ───────────────────────────────────────────────────────────────
    fun scale(ctx: Context, path: String): Float = prefs(ctx).getFloat(key(path, "scale"), DEF_SCALE)
    fun xOffset(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "x_offset"), DEF_OFFSET)
    fun yOffset(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "y_offset"), DEF_OFFSET)
    fun maxFps(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "max_fps"), DEF_MAX_FPS)
    fun unlimited(ctx: Context, path: String): Boolean =
        prefs(ctx).getBoolean(key(path, "unlimited"), DEF_UNLIMITED)

    /**
     * The on-screen rect of the watchface render canvas inside a (w × h)
     * window — the Kotlin mirror of watchface.lua applyStageTransform: the 512
     * canvas fits the SMALLER dimension centred, scaled by the face's
     * configured zoom, then nudged by its offsets (positive yOffset moves the
     * face down). Overlays that must line up with the live face (selector zoom
     * animation, loading placeholder) position their preview on this rect.
     *
     * Currently always square (no canvas deforming yet), but callers must NOT
     * assume that: treat width and height independently (scaleX/scaleY, fitXY)
     * so a stretched canvas — e.g. bottom bound extended to the screen edge —
     * only ever changes this function.
     */
    fun canvasRect(ctx: Context, activePath: String?, w: Int, h: Int): android.graphics.Rect {
        val path = pathFor(activePath)
        val side = Math.round(minOf(w, h) * scale(ctx, path))
        val left = w / 2 + xOffset(ctx, path) - side / 2
        val top = h / 2 + yOffset(ctx, path) - side / 2
        return android.graphics.Rect(left, top, left + side, top + side)
    }

    // ── setters ───────────────────────────────────────────────────────────────
    fun setScale(ctx: Context, path: String, v: Float) =
        prefs(ctx).edit().putFloat(key(path, "scale"), v.coerceIn(MIN_SCALE, MAX_SCALE)).apply()

    fun setXOffset(ctx: Context, path: String, v: Int) =
        prefs(ctx).edit().putInt(key(path, "x_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()

    fun setYOffset(ctx: Context, path: String, v: Int) =
        prefs(ctx).edit().putInt(key(path, "y_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()

    fun setMaxFps(ctx: Context, path: String, v: Int) =
        prefs(ctx).edit().putInt(key(path, "max_fps"), v).apply()

    fun setUnlimited(ctx: Context, path: String, v: Boolean) =
        prefs(ctx).edit().putBoolean(key(path, "unlimited"), v).apply()

    /** Restore all fields for a face to their defaults. */
    fun reset(ctx: Context, path: String) {
        prefs(ctx).edit()
            .remove(key(path, "scale")).remove(key(path, "x_offset"))
            .remove(key(path, "y_offset")).remove(key(path, "max_fps"))
            .remove(key(path, "unlimited"))
            .apply()
    }
}
