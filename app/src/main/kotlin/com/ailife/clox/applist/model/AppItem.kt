package com.ailife.clox.applist.model

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable,
    val isHidden: Boolean = false,
    val customOrder: Int = Int.MAX_VALUE,
    // Folder support: a folder is represented as a synthetic AppItem (a "pseudo-app") so it
    // rides through the flat List<AppItem> pipeline and every backend renders it for free.
    // For a folder item: packageName = FOLDER_PKG, activityName = folderId (stable key
    // "clox.folder/<id>"), label = folder name, icon = composite tile.
    val isFolder: Boolean = false,
    val folderId: String? = null
) {
    companion object {
        const val FOLDER_PKG = "clox.folder"
    }
}
