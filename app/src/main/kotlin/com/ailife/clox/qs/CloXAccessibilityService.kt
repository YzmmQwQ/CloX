package com.ailife.clox.qs

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.ailife.clox.rtos.RtosAccessibilityHelper

class CloXAccessibilityService : AccessibilityService() {

    companion object {
        // Strong reference — WeakReference gets GC'd causing "need to press twice" bug
        @Volatile private var instance: CloXAccessibilityService? = null

        fun isRunning() = instance != null

        fun triggerPowerMenu(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ?: false

        fun triggerRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun triggerNotifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        /** System Quick Settings shade (API 31+); older devices fall back to the notification shade. */
        fun triggerQuickSettings(): Boolean {
            val svc = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                svc.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else
                svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }

        // GLOBAL_ACTION_LOCK_SCREEN doesn't exist below API 28 — callers should route through
        // ScreenOffHelper, which falls back to Device Admin on older devices.
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
            }
            return false
        }
    }

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) =
        RtosAccessibilityHelper.handleEvent(event, this)

    override fun onInterrupt() {}
}
