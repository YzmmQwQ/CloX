package com.ailife.clox.gesture

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Window
import androidx.preference.PreferenceManager
import com.ailife.clox.qs.ScreenOffHelper
import com.ailife.clox.rtos.RtosManager
import com.ailife.clox.rtos.RtosPrefs
import com.ailife.clox.settings.SettingsActivity
import com.ailife.clox.util.safeStartActivity
import com.ailife.clox.watchface.WatchfaceAutoChanger
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.WatchfaceSnapshot
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import kotlinx.coroutines.MainScope

class GestureHandler(private val ctx: Context, private val window: Window) {

    /** Set by MainActivity: collapse the app list / overlays back to the bare watchface. */
    var onCollapseToWatchface: (() -> Unit)? = null

    /**
     * Set by MainActivity: if AOD is active, enter the dimmed AOD state directly and
     * return true (skipping the accessibility lock + re-wake round-trip). Returns
     * false when AOD is inactive so the screen-off action falls through normally.
     */
    var onScreenOffWhileAod: (() -> Boolean)? = null

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

    internal fun executeAction(action: String) {
        when (action) {
            "screen_off"         -> turnScreenOff()
            "watchface_selector" -> openWatchfaceSelector()
            "random_watchface"   -> {
                // Collapse any open overlay/app list first so the new watchface is actually visible.
                onCollapseToWatchface?.invoke()
                WatchfaceAutoChanger.pickRandom(ctx, MainScope())
            }
            "open_settings"      -> openSettings()
            "watchface_config"   -> openWatchfaceConfig()
            "lock_gesture"       -> GestureLockManager.toggleManual()
            "start_rtos"         -> enterRtosMode()
            // "nothing" — no-op
        }
    }

    private fun turnScreenOff() {
        // When AOD is active, go straight into the dimmed AOD state instead of
        // locking the screen (which would turn the display off and force a re-wake).
        if (onScreenOffWhileAod?.invoke() == true) return
        if (!ScreenOffHelper.lock(ctx)) {
            ScreenOffHelper.requestPermission(ctx)
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
        ctx.safeStartActivity(Intent(ctx, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openWatchfaceConfig() {
        com.ailife.clox.watchface.config.WatchfaceConfigActivity.start(ctx)
    }

    private fun enterRtosMode() {
        if (!RtosPrefs.isEnabled(ctx) || RtosPrefs.passwordHash(ctx) == null) return
        RtosManager.enter(ctx)
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
