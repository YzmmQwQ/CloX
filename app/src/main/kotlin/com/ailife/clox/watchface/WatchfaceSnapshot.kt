package com.ailife.clox.watchface

import android.graphics.Bitmap

object WatchfaceSnapshot {
    var bitmap: Bitmap? = null

    fun consume(): Bitmap? {
        val bmp = bitmap
        bitmap = null
        return bmp
    }
}
