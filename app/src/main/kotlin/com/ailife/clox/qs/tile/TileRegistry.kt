package com.ailife.clox.qs.tile

import android.content.Context
import com.ailife.clox.R

object TileRegistry {
    private data class TileDef(val id: String, val labelRes: Int, val iconRes: Int, val hasState: Boolean = true)

    private val defs = listOf(
        TileDef("wifi",       R.string.tile_wifi,      R.drawable.ic_wifi),
        TileDef("data",       R.string.tile_data,        R.drawable.ic_data),
        TileDef("bluetooth",  R.string.tile_bluetooth,   R.drawable.ic_bluetooth),
        TileDef("volume",     R.string.tile_volume,      R.drawable.ic_volume, hasState = false),
        TileDef("dnd",        R.string.tile_dnd,         R.drawable.ic_dnd),
        TileDef("rotate",     R.string.tile_rotate,      R.drawable.ic_rotate),
        TileDef("flashlight", R.string.tile_flashlight,  R.drawable.ic_flashlight),
        TileDef("location",   R.string.tile_location,    R.drawable.ic_location),
        TileDef("aod",        R.string.tile_aod,         R.drawable.ic_aod),
        TileDef("airplane",   R.string.tile_airplane,    R.drawable.ic_airplane),
        TileDef("hotspot",    R.string.tile_hotspot,     R.drawable.ic_hotspot),
        TileDef("rtos_mode",  R.string.tile_rtos_mode,   R.drawable.ic_lock_gesture, hasState = false),
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
