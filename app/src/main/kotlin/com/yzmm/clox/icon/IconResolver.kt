package com.yzmm.clox.icon

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.yzmm.clox.applist.AppListPrefs
import com.yzmm.clox.graphics.GraphicsCompat

object IconResolver {

    fun resolve(context: Context, packageName: String, activityName: String? = null): Drawable {
        // Priority 1: per-app custom icon saved via IconPickerDialog
        val appListPath = AppListPrefs.customIconPath(context, packageName, activityName ?: "")
        if (appListPath != null) {
            Drawable.createFromPath(appListPath)?.let { return it }
        }

        // Priority 2: legacy per-app custom image
        val customIcon = loadCustomIcon(context, packageName)
        if (customIcon != null) return customIcon

        // Priority 3: icon pack override / global icon pack
        val packIcon = IconPackLoader.loadIcon(context, packageName)
        if (packIcon != null) return packIcon

        // Priority 4: activity-specific icon (respects android:icon on <activity> tag)
        if (activityName != null) {
            try {
                return context.packageManager.getActivityIcon(ComponentName(packageName, activityName))
            } catch (_: Exception) {}
        }

        // Priority 5: app-level default icon
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

fun Drawable.renderToBitmap(size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    // On Android 8.x (API < 28) VectorDrawable is not thread-safe: rasterizing it off
    // the main thread mutates a bitmap cache whose SkPixelRef gen-id listener list is
    // shared, via ConstantState, with the on-screen instance — a concurrent RenderThread
    // draw then crashes natively in SkPixelRef::callGenIDChangeListeners. Draw a
    // decoupled mutate()d copy so the background instance owns its own native tree.
    // No-op on API >= 28 (Skia is thread-safe there); behaviour is byte-for-byte the same.
    val target = if (GraphicsCompat.isLegacySkia)
        (constantState?.newDrawable()?.mutate() ?: this)
    else this
    target.setBounds(0, 0, size, size)
    target.draw(canvas)
    return bmp
}
