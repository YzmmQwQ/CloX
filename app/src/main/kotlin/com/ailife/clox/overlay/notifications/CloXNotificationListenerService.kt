package com.ailife.clox.overlay.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CloXNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _notifications.value = activeNotifications?.toList() ?: emptyList()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _notifications.value = activeNotifications?.toList() ?: emptyList()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _notifications.value = activeNotifications?.toList() ?: emptyList()
    }

    companion object {
        private val _notifications = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val notifications: StateFlow<List<StatusBarNotification>> = _notifications
    }
}
