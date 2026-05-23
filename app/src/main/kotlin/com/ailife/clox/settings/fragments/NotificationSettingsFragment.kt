package com.ailife.clox.settings.fragments

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import com.ailife.clox.R
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService

class NotificationSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_notifications, rootKey)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val pref = findPreference<Preference>("notif_permission_status") ?: return
        val granted = isListenerEnabled()
        val running = CloXNotificationListenerService.isRunning()
        pref.summary = when {
            !granted -> "Not granted — tap to open settings"
            !running -> "Granted but service not running — tap to fix"
            else     -> "Active"
        }
        pref.setOnPreferenceClickListener {
            if (!granted || !running) {
                if (!running && granted) {
                    CloXNotificationListenerService.requestRebind(requireContext())
                } else {
                    requireContext().startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            true
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        val component = ComponentName(requireContext(), CloXNotificationListenerService::class.java).flattenToString()
        return flat?.contains(component) == true
    }
}
