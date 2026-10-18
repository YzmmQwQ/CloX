package com.yzmm.clox.qs.tile

import android.content.Context
import com.yzmm.clox.R

object TileRegistry {
    private data class TileDef(val id: String, val labelRes: Int, val iconRes: Int, val hasState: Boolean = true)

    private val defs = listOf(
        TileDef("wifi",       R.string.tile_wifi,        R.drawable.baseline_network_wifi_24),
        TileDef("data",       R.string.tile_data,        R.drawable.baseline_network_cell_24),
        TileDef("bluetooth",  R.string.tile_bluetooth,   R.drawable.baseline_bluetooth_24),
        TileDef("volume",     R.string.tile_volume,      R.drawable.baseline_volume_up_24, hasState = false),
        TileDef("dnd",        R.string.tile_dnd,         R.drawable.baseline_do_not_disturb_24),
        TileDef("rotate",     R.string.tile_rotate,      R.drawable.baseline_screen_rotation_24),
        TileDef("flashlight", R.string.tile_flashlight,  R.drawable.baseline_flashlight_on_24),
        TileDef("location",   R.string.tile_location,    R.drawable.baseline_gps_fixed_24),
        TileDef("aod",        R.string.tile_aod,         R.drawable.baseline_aod_24),
        TileDef("airplane",   R.string.tile_airplane,    R.drawable.baseline_airplanemode_active_24),
        TileDef("hotspot",    R.string.tile_hotspot,     R.drawable.baseline_wifi_tethering_24),
        TileDef("rtos_mode",  R.string.tile_rtos_mode,   R.drawable.baseline_lock_24, hasState = false),
    )

    fun builtIn(ctx: Context): List<TileDescriptor> = defs.map { d ->
        TileDescriptor(d.id, ctx.getString(d.labelRes), d.iconRes, d.hasState)
    }

    fun forId(id: String, ctx: Context): TileDescriptor? {
        if (id.startsWith("custom:")) return null // caller handles custom
        return defs.firstOrNull { it.id == id }?.let { d ->
            TileDescriptor(d.id, ctx.getString(d.labelRes), d.iconRes, d.hasState)
        }
    }
}
