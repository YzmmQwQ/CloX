package com.ailife.clox.overlay.quicksettings

import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

class FlashlightOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private var savedBrightness: Int = 128
    private var savedBrightnessMode: Int = 1
    var onDismiss: (() -> Unit)? = null

    fun show() {
        val cr = context.contentResolver
        savedBrightnessMode = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) } catch (_: Exception) { 1 }
        savedBrightness = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255)
        } catch (_: Exception) {}
        visibility = VISIBLE
    }

    fun dismiss() {
        val cr = context.contentResolver
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, savedBrightnessMode)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, savedBrightness)
        } catch (_: Exception) {}
        visibility = GONE
        onDismiss?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) dismiss()
        return true
    }
}
