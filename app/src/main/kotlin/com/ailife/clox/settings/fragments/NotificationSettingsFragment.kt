package com.ailife.clox.settings.fragments

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.util.safeStartActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class NotificationSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_notifications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchEnabled       = view.findViewById<MaterialSwitch>(R.id.switchNotifEnabled)
        val cardOptions         = view.findViewById<View>(R.id.cardNotifOptions)
        val cardAlerts          = view.findViewById<View>(R.id.cardNotifAlerts)
        val cardFilter          = view.findViewById<View>(R.id.cardNotifFilter)
        val cardPermission      = view.findViewById<View>(R.id.cardNotifPermission)
        val tvNotifCardStyle    = view.findViewById<TextView>(R.id.tvNotifCardStyle)
        val tvNotifViewStyle    = view.findViewById<TextView>(R.id.tvNotifViewStyle)
        val sliderTextSize      = view.findViewById<Slider>(R.id.sliderNotifTextSize)
        val tvTextSize          = view.findViewById<TextView>(R.id.tvNotifTextSize)
        val switchMedia         = view.findViewById<MaterialSwitch>(R.id.switchNotifMedia)
        val switchWake          = view.findViewById<MaterialSwitch>(R.id.switchNotifWake)
        val switchAutoOpen      = view.findViewById<MaterialSwitch>(R.id.switchNotifAutoOpen)
        val switchVibrate       = view.findViewById<MaterialSwitch>(R.id.switchNotifVibrate)
        val switchGroup         = view.findViewById<MaterialSwitch>(R.id.switchNotifGroup)
        val switchBlockPersist  = view.findViewById<MaterialSwitch>(R.id.switchNotifBlockPersistent)
        val tvPermission        = view.findViewById<TextView>(R.id.tvNotifPermission)

        val cardStyleEntries = resources.getStringArray(R.array.notif_card_style_entries)
        val cardStyleValues  = resources.getStringArray(R.array.notif_card_style_values)
        val viewStyleEntries = resources.getStringArray(R.array.notif_style_entries)
        val viewStyleValues  = resources.getStringArray(R.array.notif_style_values)

        fun updateDependentCards(enabled: Boolean) {
            val alpha = if (enabled) 1f else 0.4f
            listOf(cardOptions, cardAlerts, cardFilter).forEach { it.alpha = alpha; it.isEnabled = enabled }
        }

        // Master switch
        val enabled = prefs.getBoolean("notif_enabled", true)
        switchEnabled.isChecked = enabled
        updateDependentCards(enabled)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_enabled", checked).apply()
            updateDependentCards(checked)
        }

        // Card style picker
        fun refreshCardStyleLabel() {
            val v = prefs.getString("notif_card_style", "glass") ?: "glass"
            tvNotifCardStyle.text = cardStyleEntries.getOrElse(cardStyleValues.indexOf(v)) { v }
        }
        refreshCardStyleLabel()
        view.findViewById<View>(R.id.rowNotifCardStyle).setOnClickListener {
            showListPicker(getString(R.string.notif_pick_card_style), cardStyleEntries, cardStyleValues,
                prefs.getString("notif_card_style", "glass") ?: "glass") { v, _ ->
                prefs.edit().putString("notif_card_style", v).apply()
                refreshCardStyleLabel()
            }
        }

        // View style picker
        fun refreshViewStyleLabel() {
            val v = prefs.getString("notif_style", "list") ?: "list"
            tvNotifViewStyle.text = viewStyleEntries.getOrElse(viewStyleValues.indexOf(v)) { v }
        }
        refreshViewStyleLabel()
        view.findViewById<View>(R.id.rowNotifViewStyle).setOnClickListener {
            showListPicker(getString(R.string.notif_pick_view_style), viewStyleEntries, viewStyleValues,
                prefs.getString("notif_style", "list") ?: "list") { v, _ ->
                prefs.edit().putString("notif_style", v).apply()
                refreshViewStyleLabel()
            }
        }

        // Text size slider
        val textSize = prefs.getInt("notif_text_size", 14).toFloat()
        sliderTextSize.value = textSize.coerceIn(10f, 30f)
        tvTextSize.text = getString(R.string.unit_sp, textSize.toInt())
        sliderTextSize.addOnChangeListener { _, value, _ ->
            tvTextSize.text = getString(R.string.unit_sp, value.toInt())
            prefs.edit().putInt("notif_text_size", value.toInt()).apply()
        }

        // Switches
        switchMedia.isChecked = prefs.getBoolean("notif_media_enabled", true)
        switchMedia.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_media_enabled", c).apply() }

        switchWake.isChecked = prefs.getBoolean("notif_wake_screen", false)
        switchWake.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_wake_screen", c).apply() }

        switchAutoOpen.isChecked = prefs.getBoolean("notif_open_panel", false)
        switchAutoOpen.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_open_panel", c).apply() }

        switchVibrate.isChecked = prefs.getBoolean("notif_vibrate", false)
        switchVibrate.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_vibrate", c).apply() }

        switchGroup.isChecked = prefs.getBoolean("notif_group", false)
        switchGroup.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_group", c).apply() }

        switchBlockPersist.isChecked = prefs.getBoolean("notif_block_persistent", false)
        switchBlockPersist.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notif_block_persistent", c).apply() }

        // Permission card
        cardPermission.setOnClickListener {
            val granted = isListenerEnabled()
            val running = CloXNotificationListenerService.isRunning()
            if (!running && granted) {
                CloXNotificationListenerService.requestRebind(requireContext())
            } else {
                // 13+ sideloaded builds: clear the restricted-settings lock (Shizuku/root) first.
                com.ailife.clox.qs.privilege.RestrictedSettingsHelper.openGrantScreen(
                    requireContext(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        updatePermissionStatus(tvPermission)
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView>(R.id.tvNotifPermission)?.let { updatePermissionStatus(it) }
    }

    private fun updatePermissionStatus(tv: TextView) {
        val granted = isListenerEnabled()
        val running = CloXNotificationListenerService.isRunning()
        tv.text = when {
            !granted -> getString(R.string.notif_perm_status_not_granted)
            !running -> getString(R.string.notif_perm_status_service_down)
            else     -> getString(R.string.notif_perm_status_active)
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        val comp = ComponentName(requireContext(), CloXNotificationListenerService::class.java).flattenToString()
        return flat?.contains(comp) == true
    }
}
