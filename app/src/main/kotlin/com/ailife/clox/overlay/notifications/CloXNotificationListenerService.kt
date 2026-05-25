package com.ailife.clox.overlay.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class CloXNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldFilter(sbn)) return
        refreshList()
        handleNewNotificationEffects(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshList()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        refreshList()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _connected.value = false
        _notifications.tryEmit(emptyList())
    }

    private fun refreshList() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val blockPersistent = prefs.getBoolean(NotificationPrefs.NOTIF_BLOCK_PERSISTENT, false)
        val blockedApps = blockedApps(prefs)

        val filtered = (activeNotifications?.toList() ?: emptyList())
            .filter { sbn ->
                sbn.packageName !in blockedApps &&
                        !(blockPersistent && isPersistent(sbn)) &&
                        !isContentless(sbn)
            }
        _notifications.tryEmit(filtered)
    }

    private fun shouldFilter(sbn: StatusBarNotification): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val blockPersistent = prefs.getBoolean(NotificationPrefs.NOTIF_BLOCK_PERSISTENT, false)
        val blockedApps = blockedApps(prefs)
        return sbn.packageName in blockedApps ||
                (blockPersistent && isPersistent(sbn)) ||
                isContentless(sbn)
    }

    private fun isPersistent(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        return (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
    }

    private fun isContentless(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.trim()
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.trim()
        return title.isNullOrEmpty() && text.isNullOrEmpty()
    }

    private fun handleNewNotificationEffects(sbn: StatusBarNotification) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(NotificationPrefs.NOTIF_ENABLED, true)) return

        if (prefs.getBoolean(NotificationPrefs.NOTIF_WAKE_SCREEN, false)) {
            wakeScreen()
        }
        if (prefs.getBoolean(NotificationPrefs.NOTIF_VIBRATE, false)) {
            vibrate()
        }
        if (prefs.getBoolean(NotificationPrefs.NOTIF_OPEN_PANEL, false)) {
            _openPanelEvent.tryEmit(Unit)
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "clox:notif_wake"
        )
        wl.acquire(3000L)
        wl.release()
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(200)
            }
        }
    }

    companion object {
        // SharedFlow(replay=1) avoids StateFlow's equality dedup — SBN objects use reference
        // equality, so the same notification list reference would be silently dropped by StateFlow
        // even when the content changed.
        private val _notifications = MutableSharedFlow<List<StatusBarNotification>>(
            replay = 1, extraBufferCapacity = 16
        )
        val notifications: SharedFlow<List<StatusBarNotification>> = _notifications.asSharedFlow()
        val currentNotifications: List<StatusBarNotification>
            get() = _notifications.replayCache.lastOrNull() ?: emptyList()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _openPanelEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val openPanelEvent: SharedFlow<Unit> = _openPanelEvent.asSharedFlow()

        fun isRunning() = _connected.value

        fun requestRebind(context: Context) {
            requestRebind(ComponentName(context, CloXNotificationListenerService::class.java))
        }

        fun blockedApps(prefs: android.content.SharedPreferences): Set<String> {
            val json = prefs.getString(NotificationPrefs.NOTIF_BLOCKED_APPS, "[]") ?: "[]"
            return try {
                val arr = JSONArray(json)
                buildSet { repeat(arr.length()) { add(arr.getString(it)) } }
            } catch (e: Exception) { emptySet() }
        }

        fun setAppBlocked(prefs: android.content.SharedPreferences, packageName: String, blocked: Boolean) {
            val current = blockedApps(prefs).toMutableSet()
            if (blocked) current.add(packageName) else current.remove(packageName)
            val arr = JSONArray(current.toList())
            prefs.edit().putString(NotificationPrefs.NOTIF_BLOCKED_APPS, arr.toString()).apply()
        }

        // Direct cancel via static instance — no broadcast latency, no delivery uncertainty
        private var instance: CloXNotificationListenerService? = null

        fun dismissNotification(key: String) {
            try { instance?.cancelNotification(key) } catch (e: Exception) {}
        }

        fun dismissAllNotifications() {
            try { instance?.cancelAllNotifications() } catch (e: Exception) {}
        }
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == NotificationPrefs.NOTIF_BLOCK_PERSISTENT || key == NotificationPrefs.NOTIF_BLOCKED_APPS) {
            refreshList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        _connected.value = false
    }
}
