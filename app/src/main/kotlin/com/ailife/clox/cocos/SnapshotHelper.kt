package com.ailife.clox.cocos

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window

object SnapshotHelper {

    fun capture(
        window: Window,
        rect: Rect,
        onResult: (Bitmap?) -> Unit
    ) {
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: new PixelCopy.Request builder API; must deliver callback on main thread
            val mainHandler = Handler(Looper.getMainLooper())
            val request = PixelCopy.Request.Builder.ofWindow(window)
                .setSourceRect(rect)
                .setDestinationBitmap(bitmap)
                .build()
            PixelCopy.request(request, { mainHandler.post(it) }) { result ->
                onResult(if (result.status == PixelCopy.SUCCESS) result.bitmap else null)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26-33: legacy PixelCopy
            PixelCopy.request(window, rect, bitmap, { result ->
                onResult(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        } else {
            onResult(null)
        }
    }
}
