package com.ailife.clox.settings.fragments

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPrefs
import com.ailife.clox.qs.privilege.PrivilegeLevel
import com.ailife.clox.qs.privilege.PrivilegeManager

class QuickSettingsSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_quick_settings, rootKey)
        setupColorPreferences()
        setupPrivilege()
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegeStatus()
        updateAccessibilityStatus()
    }

    private fun setupPrivilege() {
        findPreference<ListPreference>(QsPrefs.PRIVILEGE_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "shizuku") {
                PrivilegeManager.invalidate()
                PrivilegeManager.requestShizukuPermission(99)
            }
            PrivilegeManager.invalidate()
            true
        }
    }

    private fun updatePrivilegeStatus() {
        val pref = findPreference<Preference>("qs_privilege_status") ?: return
        val level = PrivilegeManager.level(requireContext())
        pref.summary = when (level) {
            PrivilegeLevel.SHIZUKU -> "Shizuku active"
            PrivilegeLevel.ROOT    -> "Root available"
            PrivilegeLevel.NONE    -> "No elevated privileges"
        }
    }

    private fun updateAccessibilityStatus() {
        val pref = findPreference<Preference>("qs_accessibility_status") ?: return
        val running = CloXAccessibilityService.isRunning()
        pref.summary = if (running) "Enabled — power menu and recents available"
                       else "Tap to enable in Accessibility Settings"
        pref.setOnPreferenceClickListener {
            requireContext().startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    }

    private fun setupColorPreferences() {
        val colorKeys = listOf(
            QsPrefs.COLOR_CONNECTED    to QsPrefs.DEFAULT_COLOR_CONNECTED,
            QsPrefs.COLOR_IDLE         to QsPrefs.DEFAULT_COLOR_IDLE,
            QsPrefs.COLOR_DISCONNECTED to QsPrefs.DEFAULT_COLOR_DISCONNECTED,
            QsPrefs.COLOR_OFF          to QsPrefs.DEFAULT_COLOR_OFF,
        )
        colorKeys.forEach { (key, default) ->
            findPreference<Preference>(key)?.apply {
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                summary = prefs.getString(key, default) ?: default
                setOnPreferenceClickListener {
                    showColorDialog(key, default)
                    true
                }
            }
        }
    }

    private fun showColorDialog(key: String, default: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val current = prefs.getString(key, default) ?: default
        val input = EditText(requireContext()).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Enter hex color (e.g. #FF0000)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                    prefs.edit().putString(key, value).apply()
                    findPreference<Preference>(key)?.summary = value
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
