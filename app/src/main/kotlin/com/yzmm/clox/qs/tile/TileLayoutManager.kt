package com.yzmm.clox.qs.tile

import android.content.Context
import androidx.preference.PreferenceManager
import com.yzmm.clox.qs.QsPrefs
import org.json.JSONArray

object TileLayoutManager {

    // Default: two pages of the controls people reach for most often.
    val defaultActive = listOf(
        "wifi", "data", "bluetooth", "volume",
        "dnd", "flashlight", "rotate", "aod",
        "battery_saver", "sync"
    )

    // The original four-tile default; installs that never customised are upgraded.
    private val legacyDefault = listOf("wifi", "data", "bluetooth", "volume")

    fun loadActiveTiles(ctx: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = prefs.getString(QsPrefs.TILE_ORDER, null) ?: return defaultActive
        val loaded = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { return defaultActive }

        if (loaded == legacyDefault) {
            saveActiveTiles(ctx, defaultActive)
            return defaultActive
        }
        return loaded.ifEmpty { defaultActive }
    }

    fun saveActiveTiles(ctx: Context, ids: List<String>) {
        val arr = JSONArray().apply { ids.forEach { put(it) } }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(QsPrefs.TILE_ORDER, arr.toString()).apply()
    }

    fun loadCustomTiles(ctx: Context): List<CustomTileConfig> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = prefs.getString(QsPrefs.CUSTOM_TILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { CustomTileConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomTiles(ctx: Context, tiles: List<CustomTileConfig>) {
        val arr = JSONArray().apply { tiles.forEach { put(it.toJson()) } }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(QsPrefs.CUSTOM_TILES, arr.toString()).apply()
    }

    fun addCustomTile(ctx: Context, config: CustomTileConfig) {
        saveCustomTiles(ctx, loadCustomTiles(ctx) + config)
    }

    fun removeCustomTile(ctx: Context, uuid: String) {
        saveCustomTiles(ctx, loadCustomTiles(ctx).filter { it.uuid != uuid })
    }

    /** Replace the stored config sharing [config]'s uuid (used when editing an existing tile). */
    fun updateCustomTile(ctx: Context, config: CustomTileConfig) {
        saveCustomTiles(ctx, loadCustomTiles(ctx).map { if (it.uuid == config.uuid) config else it })
    }

    fun descriptorFor(id: String, ctx: Context): TileDescriptor? {
        TileRegistry.forId(id, ctx)?.let { return it }
        if (id.startsWith("custom:")) {
            val uuid = id.removePrefix("custom:")
            val config = loadCustomTiles(ctx).firstOrNull { it.uuid == uuid } ?: return null
            return TileDescriptor(
                id = id,
                defaultLabel = config.label,
                iconRes = com.yzmm.clox.R.drawable.baseline_settings_24,
                isCustom = true
            )
        }
        return null
    }

    const val COLUMNS = 3

    // Rows per page is user-controlled (2 or 3); the grid is always three columns.
    fun paginateTiles(ids: List<String>, rows: Int): List<List<String>> =
        ids.chunked(COLUMNS * rows)
}
