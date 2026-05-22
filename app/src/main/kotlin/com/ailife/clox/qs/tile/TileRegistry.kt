package com.ailife.clox.qs.tile

import com.ailife.clox.R

object TileRegistry {
    val builtIn: List<TileDescriptor> = listOf(
        TileDescriptor("wifi",       "Wi-Fi",      R.drawable.ic_wifi),
        TileDescriptor("data",       "Data",        R.drawable.ic_data),
        TileDescriptor("bluetooth",  "Bluetooth",   R.drawable.ic_bluetooth),
        TileDescriptor("volume",     "Volume",      R.drawable.ic_volume, hasState = false),
        TileDescriptor("dnd",        "DND",         R.drawable.ic_dnd),
        TileDescriptor("rotate",     "Rotate",      R.drawable.ic_rotate),
        TileDescriptor("flashlight", "Flashlight",  R.drawable.ic_flashlight),
        TileDescriptor("location",   "Location",    R.drawable.ic_location),
        TileDescriptor("aod",        "Always On",   R.drawable.ic_aod),
        TileDescriptor("airplane",   "Airplane",    R.drawable.ic_airplane),
        TileDescriptor("hotspot",    "Hotspot",     R.drawable.ic_hotspot),
        TileDescriptor("rtos_mode",  "RTOS Mode",   R.drawable.ic_lock_gesture, hasState = false),
    )

    fun forId(id: String): TileDescriptor? {
        if (id.startsWith("custom:")) return null // caller handles custom
        return builtIn.firstOrNull { it.id == id }
    }
}
