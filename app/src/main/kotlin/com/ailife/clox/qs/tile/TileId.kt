package com.ailife.clox.qs.tile

enum class TileId(val key: String) {
    WIFI("wifi"),
    DATA("data"),
    BLUETOOTH("bluetooth"),
    VOLUME("volume"),
    DND("dnd"),
    ROTATE("rotate"),
    FLASHLIGHT("flashlight"),
    LOCATION("location"),
    AOD("aod"),
    AIRPLANE("airplane"),
    HOTSPOT("hotspot");

    companion object {
        fun fromKey(key: String): TileId? = values().firstOrNull { it.key == key }
    }
}
