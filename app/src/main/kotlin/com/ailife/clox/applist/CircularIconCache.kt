package com.ailife.clox.applist

import android.graphics.drawable.Drawable
import android.util.LruCache

object CircularIconCache {
    private const val MAX_ITEMS = 256
    private val cache = object : LruCache<String, Drawable>(MAX_ITEMS) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    fun obtain(drawable: Drawable, sizePx: Int, key: String): Drawable {
        val cacheKey = "${key}_${sizePx}"
        return cache.get(cacheKey) ?: run {
            val circular = drawable.toCircle(sizePx)
            cache.put(cacheKey, circular)
            circular
        }
    }

    fun invalidate(key: String) {
        val keys = cache.snapshot().keys.filter { it.startsWith("${key}_") }
        keys.forEach { cache.remove(it) }
    }

    fun evictAll() = cache.evictAll()
}
