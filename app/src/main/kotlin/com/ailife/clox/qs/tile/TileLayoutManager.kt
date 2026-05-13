package com.ailife.clox.qs.tile

import android.content.Context
import androidx.preference.PreferenceManager
import com.ailife.clox.qs.QsPrefs
import org.json.JSONArray

object TileLayoutManager {

    // Default: WiFi, Data, Bluetooth, Volume — same as old app's 2×2 main grid
    val defaultActive = listOf("wifi", "data", "bluetooth", "volume")

    // Tiles removed from the default set — strip from any saved list on load
    private val removedFromDefault = setOf("dnd", "rotate", "flashlight")

    fun loadActiveTiles(ctx: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = prefs.getString(QsPrefs.TILE_ORDER, null) ?: return defaultActive
        val loaded = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { return defaultActive }

        // Migration: strip tiles that were removed from the default set
        val migrated = loaded.filter { it !in removedFromDefault }
        if (migrated.size != loaded.size) saveActiveTiles(ctx, migrated)
        return migrated.ifEmpty { defaultActive }
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

    fun descriptorFor(id: String, ctx: Context): TileDescriptor? {
        TileRegistry.forId(id)?.let { return it }
        if (id.startsWith("custom:")) {
            val uuid = id.removePrefix("custom:")
            val config = loadCustomTiles(ctx).firstOrNull { it.uuid == uuid } ?: return null
            return TileDescriptor(
                id = id,
                defaultLabel = config.label,
                iconRes = com.ailife.clox.R.drawable.ic_settings,
                isCustom = true
            )
        }
        return null
    }

    // Always 2 columns; rows per page is user-controlled (2 or 3)
    fun paginateTiles(ids: List<String>, rows: Int): List<List<String>> =
        ids.chunked(2 * rows)
}
