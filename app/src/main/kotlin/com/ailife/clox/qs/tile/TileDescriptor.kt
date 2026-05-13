package com.ailife.clox.qs.tile

data class TileDescriptor(
    val id: String,
    val defaultLabel: String,
    val iconRes: Int,
    val hasState: Boolean = true,
    val isCustom: Boolean = false
)
