package com.ailife.clox.applist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

object ScaledIconCache {
    private const val MAX_ITEMS = 256
    private val cache = object : LruCache<String, Drawable>(MAX_ITEMS) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    fun obtain(drawable: Drawable, sizePx: Int, tag: String): Drawable {
        if (sizePx <= 0) return drawable
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && bmp.width == sizePx && bmp.height == sizePx) return drawable
        }
        val key = "${tag}_${sizePx}"
        cache.get(key)?.let { return it }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(this)
        }
        val result = BitmapDrawable(null, bitmap).apply { setFilterBitmap(false) }
        cache.put(key, result)
        return result
    }

    fun evictAll() = cache.evictAll()
}
