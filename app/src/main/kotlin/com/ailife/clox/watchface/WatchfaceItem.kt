package com.ailife.clox.watchface

import java.io.File

data class WatchfaceItem(
    val file: File,
    val folder: String?,
    val isPinned: Boolean,
    val isActive: Boolean,
    val lastModified: Long,
) {
    val name: String get() = file.nameWithoutExtension
    val absolutePath: String get() = file.absolutePath
}
