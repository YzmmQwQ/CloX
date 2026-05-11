package com.ailife.clox.icon

import android.content.Context
import android.graphics.drawable.Drawable
import com.ailife.clox.applist.AppListPrefs

object IconResolver {

    fun resolve(context: Context, packageName: String): Drawable {
        // Priority 1: per-app custom icon saved via IconPickerDialog
        val appListPath = AppListPrefs.customIconPath(context, packageName)
        if (appListPath != null) {
            Drawable.createFromPath(appListPath)?.let { return it }
        }

        // Priority 2: legacy per-app custom image
        val customIcon = loadCustomIcon(context, packageName)
        if (customIcon != null) return customIcon

        // Priority 2 & 3: icon pack override / global icon pack
        val packIcon = IconPackLoader.loadIcon(context, packageName)
        if (packIcon != null) return packIcon

        // Priority 4: system default
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon
        }
    }

    private fun loadCustomIcon(context: Context, packageName: String): Drawable? {
        val prefs = context.getSharedPreferences("icon_overrides", Context.MODE_PRIVATE)
        val path = prefs.getString(packageName, null) ?: return null
        return try {
            Drawable.createFromPath(path)
        } catch (e: Exception) {
            null
        }
    }
}
