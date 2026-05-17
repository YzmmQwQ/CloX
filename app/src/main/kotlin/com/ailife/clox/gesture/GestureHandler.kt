package com.ailife.clox.gesture

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Window
import androidx.preference.PreferenceManager
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.settings.SettingsActivity
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.WatchfaceSnapshot
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity

class GestureHandler(private val ctx: Context, private val window: Window) {

    fun onLongPress() {
        vibrate()
        val action = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("long_press_action", "watchface_selector") ?: "watchface_selector"
        executeAction(action)
    }

    fun onDoubleTap() {
        val action = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("double_tap_action", "screen_off") ?: "screen_off"
        executeAction(action)
    }

    private fun executeAction(action: String) {
        when (action) {
            "screen_off"         -> turnScreenOff()
            "watchface_selector" -> openWatchfaceSelector()
            "open_settings"      -> openSettings()
            "lock_gesture"       -> GestureLockManager.toggleManual()
            // "nothing" — no-op
        }
    }

    private fun turnScreenOff() {
        if (!CloXAccessibilityService.lockScreen()) {
            QsPermissionHelper.ensureAccessibilityEnabled(ctx)
        }
    }

    private fun openWatchfaceSelector() {
        val activePath = WatchfacePrefs.getActivePath(ctx)
        if (activePath != null) {
            WatchfaceSnapshot.bitmap = WatchfaceRepository.getPreviewCached(activePath)
        }
        WatchfaceSelectorActivity.start(ctx)
    }

    private fun openSettings() {
        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
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
