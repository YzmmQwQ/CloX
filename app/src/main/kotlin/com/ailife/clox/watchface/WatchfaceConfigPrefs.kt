package com.ailife.clox.watchface

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Per-watchface display configuration — alignment (stage deform + offsets),
 * behaviour toggles and frame rate — persisted in the default SharedPreferences,
 * namespaced by a hash of the watchface path so every face keeps its own settings.
 *
 * Three configuration GROUPS, each with a per-face "use default" flag:
 *   - alignment  (alignMode, sizeX, sizeY, xOffset, yOffset)
 *   - behaviour  (allowSound, allowVibrate, allowSensors, allowWeather, allowHealth, allowTap)
 *   - frameRate  (maxFps, unlimited)
 * When a group's use-default flag is set (the default), the face reads the
 * APP-WIDE defaults ([gdef*] keys, editable in Settings) instead of its own
 * per-face values, so most faces never need individual tuning.
 *
 * Writing any per-face OR global-default key trips the OnSharedPreferenceChangeListener
 * in [com.ailife.clox.cocos.bridge.WatchfaceBridgeManager], which re-pushes the
 * "settings" snapshot to the Lua engine — giving the config dialog a live preview
 * for free. The built-in default face (no path) is keyed under [DEFAULT_PATH].
 */
object WatchfaceConfigPrefs {

    const val PREFIX = "wf_cfg_"          // per-face keys:   wf_cfg_<hash>_<field>
    const val GDEF_PREFIX = "wf_gdef_"    // app-wide default keys: wf_gdef_<field>
    const val DEFAULT_PATH = "__default__"

    // ── alignment ──────────────────────────────────────────────────────────────
    // The stage fits the 512 canvas to the VERTICAL (Y) screen dimension at size 1,
    // then scales each axis by its size multiplier — so the canvas can be deformed.
    // A square watch screen makes every preset identical; deform only shows on a
    // non-square (portrait/landscape) screen.
    const val ALIGN_VERTICAL = "vertical"     // sizeX = sizeY = 1  (fills height; sides cut on portrait)
    const val ALIGN_HORIZONTAL = "horizontal" // uniform, fit the smaller dimension (the pre-deform behaviour)
    const val ALIGN_STRETCH = "stretch"       // height fits Y, width stretched to fill the screen
    const val ALIGN_CUSTOM = "custom"         // sizeX / sizeY set by the sliders
    val ALIGN_MODES = listOf(ALIGN_VERTICAL, ALIGN_HORIZONTAL, ALIGN_STRETCH, ALIGN_CUSTOM)

    const val DEF_ALIGN_MODE = ALIGN_VERTICAL
    const val DEF_SIZE = 1.0f
    const val DEF_OFFSET = 0

    const val MIN_SIZE = 0.5f
    const val MAX_SIZE = 2.0f
    const val OFFSET_RANGE = 200            // ± pixels

    // ── behaviour toggles ──────────────────────────────────────────────────────
    const val DEF_ALLOW_SOUND = false
    const val DEF_ALLOW_VIBRATE = true
    const val DEF_ALLOW_SENSORS = true
    const val DEF_ALLOW_WEATHER = true
    const val DEF_ALLOW_HEALTH = true
    const val DEF_ALLOW_TAP = true

    // ── frame rate ─────────────────────────────────────────────────────────────
    const val DEF_MAX_FPS = 25
    const val DEF_UNLIMITED = false
    /** FPS choices offered in the dialog (paired with the "unlimited" flag). */
    val FPS_CHOICES = intArrayOf(15, 25, 60)

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun hash(path: String): String = Integer.toHexString(path.hashCode())
    private fun key(path: String, field: String) = "$PREFIX${hash(path)}_$field"
    private fun gkey(field: String) = "$GDEF_PREFIX$field"

    /** True for any per-face OR global-default config key (triggers a settings re-push). */
    fun isConfigKey(key: String?): Boolean =
        key != null && (key.startsWith(PREFIX) || key.startsWith(GDEF_PREFIX))

    /** The config-namespace path for an active face path (null/empty → default face). */
    fun pathFor(activePath: String?): String =
        if (activePath.isNullOrEmpty()) DEFAULT_PATH else activePath

    // ── group "use default" flags ──────────────────────────────────────────────
    fun useDefaultAlign(ctx: Context, path: String): Boolean =
        prefs(ctx).getBoolean(key(path, "use_def_align"), true)
    fun useDefaultBehavior(ctx: Context, path: String): Boolean =
        prefs(ctx).getBoolean(key(path, "use_def_behavior"), true)
    fun useDefaultFrameRate(ctx: Context, path: String): Boolean =
        prefs(ctx).getBoolean(key(path, "use_def_frame"), true)

    fun setUseDefaultAlign(ctx: Context, path: String, v: Boolean) =
        prefs(ctx).edit().putBoolean(key(path, "use_def_align"), v).apply()
    fun setUseDefaultBehavior(ctx: Context, path: String, v: Boolean) =
        prefs(ctx).edit().putBoolean(key(path, "use_def_behavior"), v).apply()
    fun setUseDefaultFrameRate(ctx: Context, path: String, v: Boolean) =
        prefs(ctx).edit().putBoolean(key(path, "use_def_frame"), v).apply()

    // ── effective getters (per-face override OR app-wide default) ───────────────
    // These are what the live engine and overlays read.
    fun alignMode(ctx: Context, path: String): String =
        if (useDefaultAlign(ctx, path)) gdefAlignMode(ctx) else rawAlignMode(ctx, path)
    fun sizeX(ctx: Context, path: String): Float =
        if (useDefaultAlign(ctx, path)) gdefSizeX(ctx) else rawSizeX(ctx, path)
    fun sizeY(ctx: Context, path: String): Float =
        if (useDefaultAlign(ctx, path)) gdefSizeY(ctx) else rawSizeY(ctx, path)
    fun xOffset(ctx: Context, path: String): Int =
        if (useDefaultAlign(ctx, path)) gdefXOffset(ctx) else rawXOffset(ctx, path)
    fun yOffset(ctx: Context, path: String): Int =
        if (useDefaultAlign(ctx, path)) gdefYOffset(ctx) else rawYOffset(ctx, path)

    fun allowSound(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowSound(ctx) else rawAllowSound(ctx, path)
    fun allowVibrate(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowVibrate(ctx) else rawAllowVibrate(ctx, path)
    fun allowSensors(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowSensors(ctx) else rawAllowSensors(ctx, path)
    fun allowWeather(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowWeather(ctx) else rawAllowWeather(ctx, path)
    fun allowHealth(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowHealth(ctx) else rawAllowHealth(ctx, path)
    fun allowTap(ctx: Context, path: String): Boolean =
        if (useDefaultBehavior(ctx, path)) gdefAllowTap(ctx) else rawAllowTap(ctx, path)

    fun maxFps(ctx: Context, path: String): Int =
        if (useDefaultFrameRate(ctx, path)) gdefMaxFps(ctx) else rawMaxFps(ctx, path)
    fun unlimited(ctx: Context, path: String): Boolean =
        if (useDefaultFrameRate(ctx, path)) gdefUnlimited(ctx) else rawUnlimited(ctx, path)

    // ── per-face raw getters/setters ────────────────────────────────────────────
    fun rawAlignMode(ctx: Context, path: String): String =
        prefs(ctx).getString(key(path, "align_mode"), DEF_ALIGN_MODE) ?: DEF_ALIGN_MODE
    fun rawSizeX(ctx: Context, path: String): Float = prefs(ctx).getFloat(key(path, "size_x"), DEF_SIZE)
    fun rawSizeY(ctx: Context, path: String): Float = prefs(ctx).getFloat(key(path, "size_y"), DEF_SIZE)
    fun rawXOffset(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "x_offset"), DEF_OFFSET)
    fun rawYOffset(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "y_offset"), DEF_OFFSET)
    fun rawAllowSound(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_sound"), DEF_ALLOW_SOUND)
    fun rawAllowVibrate(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_vibrate"), DEF_ALLOW_VIBRATE)
    fun rawAllowSensors(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_sensors"), DEF_ALLOW_SENSORS)
    fun rawAllowWeather(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_weather"), DEF_ALLOW_WEATHER)
    fun rawAllowHealth(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_health"), DEF_ALLOW_HEALTH)
    fun rawAllowTap(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "allow_tap"), DEF_ALLOW_TAP)
    fun rawMaxFps(ctx: Context, path: String): Int = prefs(ctx).getInt(key(path, "max_fps"), DEF_MAX_FPS)
    fun rawUnlimited(ctx: Context, path: String): Boolean = prefs(ctx).getBoolean(key(path, "unlimited"), DEF_UNLIMITED)

    fun setAlignMode(ctx: Context, path: String, v: String) =
        prefs(ctx).edit().putString(key(path, "align_mode"), v).apply()
    fun setSizeX(ctx: Context, path: String, v: Float) =
        prefs(ctx).edit().putFloat(key(path, "size_x"), v.coerceIn(MIN_SIZE, MAX_SIZE)).apply()
    fun setSizeY(ctx: Context, path: String, v: Float) =
        prefs(ctx).edit().putFloat(key(path, "size_y"), v.coerceIn(MIN_SIZE, MAX_SIZE)).apply()
    fun setXOffset(ctx: Context, path: String, v: Int) =
        prefs(ctx).edit().putInt(key(path, "x_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()
    fun setYOffset(ctx: Context, path: String, v: Int) =
        prefs(ctx).edit().putInt(key(path, "y_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()
    fun setAllowSound(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_sound"), v).apply()
    fun setAllowVibrate(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_vibrate"), v).apply()
    fun setAllowSensors(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_sensors"), v).apply()
    fun setAllowWeather(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_weather"), v).apply()
    fun setAllowHealth(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_health"), v).apply()
    fun setAllowTap(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "allow_tap"), v).apply()
    fun setMaxFps(ctx: Context, path: String, v: Int) = prefs(ctx).edit().putInt(key(path, "max_fps"), v).apply()
    fun setUnlimited(ctx: Context, path: String, v: Boolean) = prefs(ctx).edit().putBoolean(key(path, "unlimited"), v).apply()

    // ── app-wide default getters/setters (edited in Settings) ───────────────────
    fun gdefAlignMode(ctx: Context): String = prefs(ctx).getString(gkey("align_mode"), DEF_ALIGN_MODE) ?: DEF_ALIGN_MODE
    fun gdefSizeX(ctx: Context): Float = prefs(ctx).getFloat(gkey("size_x"), DEF_SIZE)
    fun gdefSizeY(ctx: Context): Float = prefs(ctx).getFloat(gkey("size_y"), DEF_SIZE)
    fun gdefXOffset(ctx: Context): Int = prefs(ctx).getInt(gkey("x_offset"), DEF_OFFSET)
    fun gdefYOffset(ctx: Context): Int = prefs(ctx).getInt(gkey("y_offset"), DEF_OFFSET)
    fun gdefAllowSound(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_sound"), DEF_ALLOW_SOUND)
    fun gdefAllowVibrate(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_vibrate"), DEF_ALLOW_VIBRATE)
    fun gdefAllowSensors(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_sensors"), DEF_ALLOW_SENSORS)
    fun gdefAllowWeather(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_weather"), DEF_ALLOW_WEATHER)
    fun gdefAllowHealth(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_health"), DEF_ALLOW_HEALTH)
    fun gdefAllowTap(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("allow_tap"), DEF_ALLOW_TAP)
    fun gdefMaxFps(ctx: Context): Int = prefs(ctx).getInt(gkey("max_fps"), DEF_MAX_FPS)
    fun gdefUnlimited(ctx: Context): Boolean = prefs(ctx).getBoolean(gkey("unlimited"), DEF_UNLIMITED)

    fun setGdefAlignMode(ctx: Context, v: String) = prefs(ctx).edit().putString(gkey("align_mode"), v).apply()
    fun setGdefSizeX(ctx: Context, v: Float) = prefs(ctx).edit().putFloat(gkey("size_x"), v.coerceIn(MIN_SIZE, MAX_SIZE)).apply()
    fun setGdefSizeY(ctx: Context, v: Float) = prefs(ctx).edit().putFloat(gkey("size_y"), v.coerceIn(MIN_SIZE, MAX_SIZE)).apply()
    fun setGdefXOffset(ctx: Context, v: Int) = prefs(ctx).edit().putInt(gkey("x_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()
    fun setGdefYOffset(ctx: Context, v: Int) = prefs(ctx).edit().putInt(gkey("y_offset"), v.coerceIn(-OFFSET_RANGE, OFFSET_RANGE)).apply()
    fun setGdefAllowSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_sound"), v).apply()
    fun setGdefAllowVibrate(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_vibrate"), v).apply()
    fun setGdefAllowSensors(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_sensors"), v).apply()
    fun setGdefAllowWeather(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_weather"), v).apply()
    fun setGdefAllowHealth(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_health"), v).apply()
    fun setGdefAllowTap(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("allow_tap"), v).apply()
    fun setGdefMaxFps(ctx: Context, v: Int) = prefs(ctx).edit().putInt(gkey("max_fps"), v).apply()
    fun setGdefUnlimited(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(gkey("unlimited"), v).apply()

    // ── stage deform math (Kotlin mirror of watchface.lua applyStageTransform) ──
    /**
     * The effective (scaleX, scaleY) size multipliers for a mode, given a window
     * of (w × h). Reference is the VERTICAL dimension: at size 1 the canvas fills
     * the height. Must stay in lockstep with watchface.lua effectiveSizes().
     *   vertical   → (1, 1)                       fills height, sides cut on portrait
     *   horizontal → (min/h, min/h)               uniform fit-smaller (pre-deform behaviour)
     *   stretch    → (w/h, 1)                     width fills screen, height fills Y
     *   custom     → (sizeX, sizeY)               slider multipliers
     */
    fun effectiveSizes(mode: String, sizeX: Float, sizeY: Float, w: Int, h: Int): Pair<Float, Float> {
        if (h == 0) return sizeX to sizeY
        val minRatio = minOf(w, h).toFloat() / h
        return when (mode) {
            ALIGN_VERTICAL -> 1f to 1f
            ALIGN_HORIZONTAL -> minRatio to minRatio
            ALIGN_STRETCH -> (w.toFloat() / h) to 1f
            else -> sizeX to sizeY   // custom
        }
    }

    /**
     * The on-screen rect of the watchface render canvas inside a (w × h) window —
     * the 512 canvas fits the vertical dimension, scaled per-axis by the face's
     * [effectiveSizes], then nudged by its offsets (positive yOffset moves the face
     * down). Overlays that must line up with the live face (selector zoom animation,
     * loading placeholder) position their preview on this rect. Width and height are
     * independent so a deformed canvas is handled correctly.
     */
    fun canvasRect(ctx: Context, activePath: String?, w: Int, h: Int): android.graphics.Rect {
        val path = pathFor(activePath)
        val (mx, my) = effectiveSizes(alignMode(ctx, path), sizeX(ctx, path), sizeY(ctx, path), w, h)
        val base = h.toFloat()                    // size 1 fills the vertical dimension
        val cw = Math.round(base * mx)
        val ch = Math.round(base * my)
        val left = w / 2 + xOffset(ctx, path) - cw / 2
        val top = h / 2 + yOffset(ctx, path) - ch / 2
        return android.graphics.Rect(left, top, left + cw, top + ch)
    }

    // ── resets ──────────────────────────────────────────────────────────────────
    /** Restore a face's ALIGNMENT group to defaults (used by the "reset" button). */
    fun resetAlignment(ctx: Context, path: String) {
        prefs(ctx).edit()
            .remove(key(path, "align_mode")).remove(key(path, "size_x")).remove(key(path, "size_y"))
            .remove(key(path, "x_offset")).remove(key(path, "y_offset"))
            .apply()
    }

    /** Restore ALL of a face's config to defaults. */
    fun reset(ctx: Context, path: String) {
        prefs(ctx).edit()
            .remove(key(path, "align_mode")).remove(key(path, "size_x")).remove(key(path, "size_y"))
            .remove(key(path, "x_offset")).remove(key(path, "y_offset"))
            .remove(key(path, "allow_sound")).remove(key(path, "allow_vibrate"))
            .remove(key(path, "allow_sensors")).remove(key(path, "allow_weather"))
            .remove(key(path, "allow_health")).remove(key(path, "allow_tap"))
            .remove(key(path, "max_fps")).remove(key(path, "unlimited"))
            .remove(key(path, "use_def_align")).remove(key(path, "use_def_behavior"))
            .remove(key(path, "use_def_frame"))
            .apply()
    }
}
