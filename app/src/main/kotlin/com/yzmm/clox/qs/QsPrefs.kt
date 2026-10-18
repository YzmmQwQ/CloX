package com.yzmm.clox.qs

object QsPrefs {
    const val QS_ENABLED            = "qs_enabled"
    const val TILE_ROWS             = "qs_tile_rows"    // rows per page: "2" or "3"; always 2 columns
    const val COLORED_TILES         = "qs_color_tiles"
    const val USE_SYSTEM_COLOR      = "qs_use_system_color"  // use Material3 dynamic colors
    const val COLOR_CONNECTED       = "qs_color_connected"
    const val COLOR_IDLE            = "qs_color_idle"
    const val COLOR_DISCONNECTED    = "qs_color_disconnected"
    const val COLOR_OFF             = "qs_color_off"
    const val ROUND_MARGIN          = "qs_round_margin"
    const val PRIVILEGE_MODE        = "qs_privilege"
    const val FLASHLIGHT_HARDWARE   = "qs_flashlight_hardware"
    const val TILE_ORDER            = "qs_tile_order"
    const val CUSTOM_TILES          = "qs_custom_tiles"
    const val PERMISSIONS_REQUESTED = "qs_permissions_done"
    const val TILE_STYLE            = "qs_tile_style"   // "stroke" | "solid"

    const val TILE_STYLE_STROKE = "stroke"
    const val TILE_STYLE_SOLID  = "solid"

    const val DEFAULT_COLOR_CONNECTED    = "#379950"
    const val DEFAULT_COLOR_IDLE         = "#2444E3"
    const val DEFAULT_COLOR_DISCONNECTED = "#AD7623"
    const val DEFAULT_COLOR_OFF          = "#AAAAAA"
}
