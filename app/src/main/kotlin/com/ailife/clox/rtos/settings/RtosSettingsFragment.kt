package com.ailife.clox.rtos.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.ailife.clox.R
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.rtos.RtosPrefs

class RtosSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_rtos, rootKey)

        findPreference<SwitchPreferenceCompat>(RtosPrefs.KEY_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && RtosPrefs.passwordHash(requireContext()) == null) {
                    Toast.makeText(requireContext(), "Set a password first", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                true
            }

        findPreference<SwitchPreferenceCompat>(RtosPrefs.KEY_SHOW_LAUNCHER_ICON)
            ?.setOnPreferenceChangeListener { _, newValue ->
                setRtosEntryLauncherEnabled(newValue == true)
                true
            }

        findPreference<ListPreference>(RtosPrefs.KEY_PASSWORD_TYPE)
            ?.setOnPreferenceChangeListener { _, _ ->
                // Clear stored password so the user must re-set it for the new type
                RtosPrefs.setPassword(requireContext(), emptyList(), "")
                RtosPrefs.get(requireContext()).edit()
                    .remove(RtosPrefs.KEY_PASSWORD_HASH)
                    .remove(RtosPrefs.KEY_PASSWORD_SEQ)
                    .apply()
                // Disable RTOS mode if it was on — can't lock with no password
                RtosPrefs.setEnabled(requireContext(), false)
                findPreference<SwitchPreferenceCompat>(RtosPrefs.KEY_ENABLED)?.isChecked = false
                Toast.makeText(requireContext(),
                    "Password cleared — set a new password for the selected type",
                    Toast.LENGTH_LONG).show()
                true
            }

        findPreference<Preference>("rtos_nav_password")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RtosPasswordSetupActivity::class.java))
            true
        }

        findPreference<Preference>("rtos_nav_whitelist")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RtosWhitelistActivity::class.java))
            true
        }

        updateAccessibilityStatus()

        findPreference<Preference>("rtos_accessibility")?.setOnPreferenceClickListener {
            QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
            true
        }

        findPreference<SwitchPreferenceCompat>(RtosPrefs.KEY_BLOCK_APPS)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !com.ailife.clox.qs.CloXAccessibilityService.isRunning()) {
                    Toast.makeText(requireContext(),
                        "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
                    QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
                    return@setOnPreferenceChangeListener false
                }
                true
            }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val running = com.ailife.clox.qs.CloXAccessibilityService.isRunning()
        findPreference<Preference>("rtos_accessibility")?.summary =
            if (running) "✓ Enabled" else "Tap to enable — required for app blocking"
    }

    private fun setRtosEntryLauncherEnabled(enabled: Boolean) {
        val ctx = requireContext()
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, "com.ailife.clox.rtos.RtosEntryLauncher"),
                state, PackageManager.DONT_KILL_APP
            )
        }
    }
}
