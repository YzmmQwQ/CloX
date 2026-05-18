package com.ailife.clox.applist

import android.content.Context
import com.ailife.clox.applist.model.AppItem
import org.json.JSONArray
import org.json.JSONObject

object AppListPrefs {

    const val PREFS_NAME = "app_list"

    // ── Style ────────────────────────────────────────────────────────────────
    const val KEY_STYLE = "style"
    const val STYLE_LIST       = "list"
    const val STYLE_GRID       = "grid"
    const val STYLE_WATERFALL  = "waterfall"
    const val STYLE_HONEYCOMB  = "honeycomb"
    const val STYLE_GRID2D     = "grid2d"
    const val STYLE_PAGES      = "pages"

    // ── Sort ─────────────────────────────────────────────────────────────────
    const val KEY_SORT      = "sort"
    const val SORT_ALPHA    = "alpha"
    const val SORT_CUSTOM   = "custom"

    // ── Icon pack ────────────────────────────────────────────────────────────
    const val KEY_ICON_PACK = "icon_pack"

    // ── General appearance ───────────────────────────────────────────────────
    const val KEY_BG_IMAGE_PATH = "bg_image_path"
    const val KEY_WALLPAPER_DIM = "wallpaper_dim"

    // ── Curve types (mirrors Effect3DConfig) ─────────────────────────────────
    const val CURVE_LINEAR      = "linear"
    const val CURVE_SMOOTHSTEP  = "smoothstep"
    const val CURVE_EXPONENTIAL = "exponential"

    // ── List sub-settings ────────────────────────────────────────────────────
    const val KEY_LIST_SEARCH = "list_search"
    const val KEY_LIST_3D     = "list_3d"
    const val KEY_LIST_3D_MIN_SCALE = "list_3d_min_scale"
    const val KEY_LIST_3D_CURVE = "list_3d_curve"

    // ── Grid sub-settings ────────────────────────────────────────────────────
    const val KEY_GRID_COLS  = "grid_cols"
    const val KEY_GRID_NAMES = "grid_names"

    // ── Waterfall sub-settings ───────────────────────────────────────────────
    const val KEY_WATERFALL_3D = "waterfall_3d"
    const val KEY_WATERFALL_3D_MIN_SCALE = "waterfall_3d_min_scale"
    const val KEY_WATERFALL_3D_CURVE = "waterfall_3d_curve"

    // ── Honeycomb sub-settings ───────────────────────────────────────────────
    const val KEY_HONEYCOMB_3D        = "honeycomb_3d"
    const val KEY_HONEYCOMB_ICON_SIZE = "honeycomb_icon_size"   // dp, 0 = auto
    const val KEY_HONEYCOMB_3D_MIN_SCALE = "honeycomb_3d_min_scale"
    const val KEY_HONEYCOMB_3D_CURVE = "honeycomb_3d_curve"

    // ── Grid2D sub-settings ──────────────────────────────────────────────────
    const val KEY_GRID2D_COLS      = "grid2d_cols"
    const val KEY_GRID2D_NAMES     = "grid2d_names"
    const val KEY_GRID2D_3D        = "grid2d_3d"
    const val KEY_GRID2D_ICON_SIZE = "grid2d_icon_size"          // dp, 0 = auto
    const val KEY_GRID2D_3D_MIN_SCALE = "grid2d_3d_min_scale"
    const val KEY_GRID2D_3D_CURVE = "grid2d_3d_curve"

    // ── Pages sub-settings ───────────────────────────────────────────────────
    const val KEY_PAGES_COLS = "pages_cols"
    const val KEY_PAGES_ROWS = "pages_rows"

    // ── Persistence ──────────────────────────────────────────────────────────
    const val KEY_CUSTOM_ORDER  = "custom_order"   // JSON array of "packageName/activityName" strings
    const val KEY_CUSTOM_ICONS  = "custom_icons"   // JSON object: pkg → file path

    // ─────────────────────────────────────────────────────────────────────────

    fun get(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun bgImagePath(ctx: Context): String? = get(ctx).getString(KEY_BG_IMAGE_PATH, null)
    fun saveBgImagePath(ctx: Context, path: String?) {
        if (path == null) get(ctx).edit().remove(KEY_BG_IMAGE_PATH).apply()
        else get(ctx).edit().putString(KEY_BG_IMAGE_PATH, path).apply()
    }
    fun wallpaperDim(ctx: Context) = get(ctx).getInt(KEY_WALLPAPER_DIM, 0)

    fun style(ctx: Context)    = get(ctx).getString(KEY_STYLE, STYLE_LIST) ?: STYLE_LIST
    fun sort(ctx: Context)     = get(ctx).getString(KEY_SORT, SORT_ALPHA)  ?: SORT_ALPHA
    fun iconPack(ctx: Context) = get(ctx).getString(KEY_ICON_PACK, "")     ?: ""

    fun listSearch(ctx: Context) = get(ctx).getBoolean(KEY_LIST_SEARCH, true)
    fun list3d(ctx: Context)     = get(ctx).getBoolean(KEY_LIST_3D, false)
    fun list3dConfig(ctx: Context) = Effect3DConfig(
        curve = get(ctx).getString(KEY_LIST_3D_CURVE, CURVE_LINEAR) ?: CURVE_LINEAR,
        minScalePercent = get(ctx).getInt(KEY_LIST_3D_MIN_SCALE, 50)
    )

    fun gridCols(ctx: Context)  = get(ctx).getInt(KEY_GRID_COLS, 4)
    fun gridNames(ctx: Context) = get(ctx).getBoolean(KEY_GRID_NAMES, true)

    fun waterfall3d(ctx: Context) = get(ctx).getBoolean(KEY_WATERFALL_3D, false)
    fun waterfall3dConfig(ctx: Context) = Effect3DConfig(
        curve = get(ctx).getString(KEY_WATERFALL_3D_CURVE, CURVE_LINEAR) ?: CURVE_LINEAR,
        minScalePercent = get(ctx).getInt(KEY_WATERFALL_3D_MIN_SCALE, 50)
    )

    fun honeycomb3d(ctx: Context)       = get(ctx).getBoolean(KEY_HONEYCOMB_3D, false)
    fun honeycombIconSize(ctx: Context) = get(ctx).getInt(KEY_HONEYCOMB_ICON_SIZE, 0)
    fun honeycomb3dConfig(ctx: Context) = Effect3DConfig(
        curve = get(ctx).getString(KEY_HONEYCOMB_3D_CURVE, CURVE_LINEAR) ?: CURVE_LINEAR,
        minScalePercent = get(ctx).getInt(KEY_HONEYCOMB_3D_MIN_SCALE, 50)
    )

    fun grid2dCols(ctx: Context)      = get(ctx).getInt(KEY_GRID2D_COLS, 4)
    fun grid2dNames(ctx: Context)     = get(ctx).getBoolean(KEY_GRID2D_NAMES, false)
    fun grid2d3d(ctx: Context)        = get(ctx).getBoolean(KEY_GRID2D_3D, false)
    fun grid2dIconSize(ctx: Context)  = get(ctx).getInt(KEY_GRID2D_ICON_SIZE, 0)
    fun grid2d3dConfig(ctx: Context) = Effect3DConfig(
        curve = get(ctx).getString(KEY_GRID2D_3D_CURVE, CURVE_LINEAR) ?: CURVE_LINEAR,
        minScalePercent = get(ctx).getInt(KEY_GRID2D_3D_MIN_SCALE, 50)
    )

    fun pagesCols(ctx: Context) = get(ctx).getInt(KEY_PAGES_COLS, 4)
    fun pagesRows(ctx: Context) = get(ctx).getInt(KEY_PAGES_ROWS, 5)

    // ── Custom order ─────────────────────────────────────────────────────────

    fun customOrder(ctx: Context): List<String> {
        val json = get(ctx).getString(KEY_CUSTOM_ORDER, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomOrder(ctx: Context, order: List<String>) {
        get(ctx).edit().putString(KEY_CUSTOM_ORDER, JSONArray(order).toString()).apply()
    }

    /**
     * Saves a new order for ONLY the visible (non-hidden) items, then merges
     * hidden items back into the full order preserving their original positions.
     * This ensures hidden apps keep their place in the global order.
     */
    fun saveVisibleOrder(ctx: Context, visibleItems: List<AppItem>) {
        val fullOrder = customOrder(ctx).toMutableList()
        val visibleKeys = visibleItems.map { "${it.packageName}/${it.activityName}" }
        val visibleKeysSet = visibleKeys.toSet()

        var newIdx = 0
        val result = mutableListOf<String>()
        for (oldKey in fullOrder) {
            if (oldKey in visibleKeysSet) {
                if (newIdx < visibleKeys.size) {
                    result.add(visibleKeys[newIdx])
                    newIdx++
                }
            } else {
                result.add(oldKey)
            }
        }
        while (newIdx < visibleKeys.size) {
            result.add(visibleKeys[newIdx])
            newIdx++
        }
        saveCustomOrder(ctx, result)
    }

    // ── Hidden apps ──────────────────────────────────────────────────────────

    const val KEY_HIDE_MODE        = "hide_mode"
    const val HIDE_MODE_BLACKLIST  = "blacklist"   // selected = hidden
    const val HIDE_MODE_WHITELIST  = "whitelist"   // selected = visible, rest hidden
    const val KEY_HIDDEN_COMPONENTS = "hidden_components"   // Set<"pkg/activity">
    const val KEY_HIDE_APPS_PASSWORD = "hide_apps_password"

    /** In-process unlock flag — resets when the process dies. */
    var hideAppsUnlocked: Boolean = false

    fun hideMode(ctx: Context): String =
        get(ctx).getString(KEY_HIDE_MODE, HIDE_MODE_BLACKLIST) ?: HIDE_MODE_BLACKLIST

    fun hiddenComponents(ctx: Context): Set<String> =
        get(ctx).getStringSet(KEY_HIDDEN_COMPONENTS, emptySet()) ?: emptySet()

    fun saveHiddenComponents(ctx: Context, components: Set<String>) =
        get(ctx).edit().putStringSet(KEY_HIDDEN_COMPONENTS, components).apply()

    fun hideAppsPassword(ctx: Context): String? =
        get(ctx).getString(KEY_HIDE_APPS_PASSWORD, null)

    fun saveHideAppsPassword(ctx: Context, password: String?) {
        if (password == null) get(ctx).edit().remove(KEY_HIDE_APPS_PASSWORD).apply()
        else get(ctx).edit().putString(KEY_HIDE_APPS_PASSWORD, password).apply()
    }

    // ── Notification badges ──────────────────────────────────────────────────

    const val KEY_NOTIF_BADGE       = "notif_badge"
    const val KEY_NOTIF_BADGE_COUNT = "notif_badge_count"

    fun notifBadge(ctx: Context)      = get(ctx).getBoolean(KEY_NOTIF_BADGE, false)
    fun notifBadgeCount(ctx: Context) = get(ctx).getBoolean(KEY_NOTIF_BADGE_COUNT, true)

    // ── Per-app custom icons ─────────────────────────────────────────────────

    fun customIconPath(ctx: Context, packageName: String, activityName: String): String? {
        val key = "$packageName/$activityName"
        val json = get(ctx).getString(KEY_CUSTOM_ICONS, "{}") ?: "{}"
        return try { JSONObject(json).optString(key).takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }

    fun saveCustomIconPath(ctx: Context, packageName: String, activityName: String, path: String?) {
        val key = "$packageName/$activityName"
        val json = get(ctx).getString(KEY_CUSTOM_ICONS, "{}") ?: "{}"
        val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
        if (path == null) obj.remove(key) else obj.put(key, path)
        get(ctx).edit().putString(KEY_CUSTOM_ICONS, obj.toString()).apply()
    }
}
