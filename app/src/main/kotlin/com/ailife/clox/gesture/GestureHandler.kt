package com.ailife.clox.gesture

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Window
import androidx.preference.PreferenceManager
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.WatchfaceSnapshot
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity

class GestureHandler(private val ctx: Context, private val window: Window) {

    fun onLongPress() {
        vibrate()
        when (PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("long_press_action", "none")) {
            "watchface_selector" -> openWatchfaceSelector()
        }
    }

    private fun openWatchfaceSelector() {
        // Use the active watchface's cached preview as the overlay for the open animation.
        // This replaces the close animation in reverse (preview zooms from fullscreen to card).
        val activePath = WatchfacePrefs.getActivePath(ctx)
        if (activePath != null) {
            WatchfaceSnapshot.bitmap = WatchfaceRepository.getPreviewCached(activePath)
        }
        WatchfaceSelectorActivity.start(ctx)
    }

    private fun vibrate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
