package com.ailife.clox.aod

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.ailife.clox.gesture.GestureLockManager

/**
 * Manages AOD (Always-on Display) state for MainActivity.
 *
 * Lifecycle:
 *   onCreate  → start()
 *   onResume  → onResume()
 *   onPause   → onPause()
 *   onDestroy → stop()
 *
 * All re-wake logic lives in onScreenOff() only — no SCREEN_ON handler.
 * Decision is based solely on gesture lock state when screen turns off:
 *   gesture locked   → wake screen; if power_button_exit: exit AOD active state
 *   gesture unlocked → wake screen; immediately enter dim + lock state
 */
class AodManager(
    private val activity: Activity,
    private val window: android.view.Window,
    private val onCollapseOverlays: () -> Unit,
) {
    private val context: Context = activity.applicationContext
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val handler = Handler(Looper.getMainLooper())

    var aodActive = false
        private set
    private var dimmed = false
    private var charging = false

    private var keepOnWakeLock: PowerManager.WakeLock? = null

    private val dimRunnable     = Runnable { enterDimmedState() }
    private val gestureRunnable = Runnable { lockGesturesForAod() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (nowCharging != charging) {
                charging = nowCharging
                Log.d(TAG, "charging changed: $charging")
                applyEnabledPref()
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AodPrefs.KEY_ENABLED || key == AodPrefs.KEY_ALWAYS_WHEN_CHARGING) applyEnabledPref()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        activity.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // Sticky battery intent gives the current charging state immediately.
        val batteryIntent = activity.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
        Log.d(TAG, "start: aod_enabled=${prefs.getBoolean(AodPrefs.KEY_ENABLED, false)} charging=$charging")
        applyEnabledPref()
    }

    fun onResume() {
        if (aodActive) {
            acquireKeepOnLock()
            if (!dimmed) resetIdleTimer()
        }
    }

    fun onPause() {
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(gestureRunnable)
        releaseKeepOnLock()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try { activity.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { activity.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(gestureRunnable)
        releaseKeepOnLock()
        if (aodActive) {
            aodActive = false
            dimmed = false
            restoreBrightness()       // also calls setLuaAodActive(false)
            GestureLockManager.unlockAod()
            applyShowWhenLocked(false)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    fun onTouch() {
        if (!aodActive) return
        if (dimmed) {
            if (!GestureLockManager.isAodLocked) {
                restoreBrightness()
                resetIdleTimer()
            } else if (prefs.getBoolean(AodPrefs.KEY_SHALLOW_SLEEP, false)) {
                restoreBrightness()
                resetIdleTimer()
            }
            return
        }
        resetIdleTimer()
    }

    fun onAodGestureUnlocked() {
        if (!aodActive) return
        restoreBrightness()
        resetIdleTimer()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun applyEnabledPref() {
        if (shouldAodBeActive()) enableAod() else disableAod()
    }

    /**
     * AOD is active if the user enabled it, OR if it should always run while charging
     * and the device is currently charging (regardless of the master enable setting).
     */
    private fun shouldAodBeActive(): Boolean {
        if (charging && prefs.getBoolean(AodPrefs.KEY_ALWAYS_WHEN_CHARGING, false)) return true
        return prefs.getBoolean(AodPrefs.KEY_ENABLED, false)
    }

    private fun enableAod() {
        if (aodActive) return
        Log.d(TAG, "enableAod")
        aodActive = true
        acquireKeepOnLock()
        applyShowWhenLocked(true)
        // Lua is NOT in power-save yet — only enterDimmedState() sets that
        resetIdleTimer()
    }

    private fun disableAod() {
        if (!aodActive) return
        Log.d(TAG, "disableAod")
        aodActive = false
        dimmed = false
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(gestureRunnable)
        releaseKeepOnLock()
        restoreBrightness()       // also calls setLuaAodActive(false)
        GestureLockManager.unlockAod()
        applyShowWhenLocked(false)
    }

    private fun acquireKeepOnLock() {
        if (keepOnWakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        keepOnWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "clox:aod_keep_on")
        keepOnWakeLock?.acquire()
        Log.d(TAG, "acquireKeepOnLock isHeld=${keepOnWakeLock?.isHeld}")
    }

    private fun releaseKeepOnLock() {
        if (keepOnWakeLock?.isHeld == true) {
            keepOnWakeLock?.release()
            Log.d(TAG, "releaseKeepOnLock: released")
        }
        keepOnWakeLock = null
    }

    private fun applyShowWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(show)
            activity.setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            if (show) window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            else window.clearFlags(
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(gestureRunnable)
        val ms = prefs.getInt(AodPrefs.KEY_TIMEOUT_SECONDS, AodPrefs.DEFAULT_TIMEOUT_SECONDS) * 1000L
        handler.postDelayed(dimRunnable, ms)
    }

    private fun enterDimmedState() {
        if (!aodActive) return
        dimmed = true
        onCollapseOverlays()
        dimScreen()
        setLuaAodActive(true)   // watchface enters power-save mode
        scheduleLockGesture(immediate = false)
    }

    private fun scheduleLockGesture(immediate: Boolean) {
        if (!prefs.getBoolean(AodPrefs.KEY_LOCK_GESTURE, true)) return
        handler.removeCallbacks(gestureRunnable)
        if (immediate) {
            lockGesturesForAod()
        } else {
            val ms = prefs.getInt(AodPrefs.KEY_LOCK_GESTURE_DELAY, AodPrefs.DEFAULT_LOCK_GESTURE_DELAY) * 1000L
            if (ms <= 0) lockGesturesForAod() else handler.postDelayed(gestureRunnable, ms)
        }
    }

    private fun lockGesturesForAod() {
        if (aodActive) GestureLockManager.lock(GestureLockManager.LockSource.AOD)
    }

    private fun dimScreen() {
        val brightness = prefs.getInt(AodPrefs.KEY_BRIGHTNESS, AodPrefs.DEFAULT_BRIGHTNESS)
        val lp = window.attributes
        lp.screenBrightness = brightness.coerceIn(1, 255) / 255f
        window.attributes = lp
    }

    private fun restoreBrightness() {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        dimmed = false
        setLuaAodActive(false)  // watchface exits power-save mode
    }

    // ── Screen off ────────────────────────────────────────────────────────────

    private fun onScreenOff() {
        Log.d(TAG, "onScreenOff: aodActive=$aodActive gestureLocked=${GestureLockManager.isAodLocked}")
        if (!aodActive || !prefs.getBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, true)) return

        wakeScreen()
        bringToForeground()

        if (GestureLockManager.isAodLocked) {
            // Screen turned off while in deep AOD (gesture locked)
            if (prefs.getBoolean(AodPrefs.KEY_POWER_BUTTON_EXIT, true)) {
                // Exit active AOD state: unlock gesture, restore brightness, restart idle timer
                handler.postDelayed({
                    GestureLockManager.unlockAod()
                    restoreBrightness()
                    resetIdleTimer()
                }, 800L)
            }
            // power_button_exit off: re-woke screen but stay in locked state
        } else {
            // Screen turned off before or during gesture lock delay — immediately dim and lock
            handler.postDelayed({
                if (aodActive && !dimmed) {
                    dimmed = true
                    dimScreen()
                    onCollapseOverlays()
                    scheduleLockGesture(immediate = true)
                }
            }, 500L)
        }
    }

    private fun wakeScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "clox:aod_reawaken"
        ).acquire(3_000L)
        Log.d(TAG, "wakeScreen: wake lock acquired")
    }

    private fun bringToForeground() {
        try {
            val intent = Intent(context, activity.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "bringToForeground: sent")
        } catch (e: Exception) {
            Log.e(TAG, "bringToForeground failed", e)
        }
    }

    private fun setLuaAodActive(active: Boolean) {
        prefs.edit().putBoolean(AodPrefs.KEY_LUA_AOD_ACTIVE, active).apply()
    }

    companion object {
        private const val TAG = "AodManager"
    }
}
