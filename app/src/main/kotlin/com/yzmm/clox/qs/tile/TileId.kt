package com.yzmm.clox.qs.tile

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
    HOTSPOT("hotspot"),
    BATTERY_SAVER("battery_saver"),
    SYNC("sync"),
    RTOS_MODE("rtos_mode");

    companion object {
        fun fromKey(key: String): TileId? = values().firstOrNull { it.key == key }
    }
}
