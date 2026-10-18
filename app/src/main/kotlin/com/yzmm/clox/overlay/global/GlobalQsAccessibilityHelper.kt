package com.yzmm.clox.overlay.global

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yzmm.clox.rtos.RtosAccessibilityHelper
import com.yzmm.clox.rtos.RtosManager

/**
 * Accessibility hook for the global-QS auto-replace feature: when the system QS/notification
 * shade opens, collapse it and show CloX QS instead. Own-app foreground is tracked via activity
 * lifecycle in the service (no accessibility needed), so this only handles the shade takeover.
 *
 * Skips entirely while RTOS is active (RTOS has its own handling).
 */
object GlobalQsAccessibilityHelper {

    private const val TAG = "GlobalQsA11y"
    private val handler = Handler(Looper.getMainLooper())

    fun handleEvent(event: AccessibilityEvent?, service: AccessibilityService) {
        if (RtosManager.isActive) return
        if (!GlobalOverlayPrefs.globalEnabled(service)) return
        if (!GlobalOverlayPrefs.autoReplace(service)) return

        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.systemui") return
        if (GlobalOverlayService.isPanelOpen()) return

        // Strict: only the QS / notification shade should trigger — NOT power menu, recents,
        // volume dialog, etc. The window class name is generic on many ROMs, so match on the
        // accessibility announcement text/description instead.
        val cn = event.className?.toString().orEmpty()
        val text = (event.text?.joinToString(" ") { it?.toString() ?: "" } + " " +
                (event.contentDescription?.toString() ?: "")).trim()
        Log.d(TAG, "systemui window: className=$cn text=\"$text\"")
        if (!isQsShade(cn, text)) { Log.d(TAG, "not the QS shade — ignoring"); return }

        Log.d(TAG, "QS shade detected — collapsing and opening CloX QS")
        RtosAccessibilityHelper.collapseSystemShade(service)
        handler.postDelayed({ GlobalOverlayService.onSystemQuickSettingsOpened() }, 60L)
    }

    private fun isQsShade(cn: String, text: String): Boolean {
        // The shade window announces text starting with "Notification shade." / "Quick settings.".
        // Heads-up notifications fire with the notification's own text (contains "notification"
        // but does NOT start with "notification shade"), and other noise is "100%", "Lock screen."
        // — so match strictly on the leading announcement.
        val t = text.trim().lowercase()
        return t.startsWith("notification shade") || t.startsWith("quick settings")
    }
}
