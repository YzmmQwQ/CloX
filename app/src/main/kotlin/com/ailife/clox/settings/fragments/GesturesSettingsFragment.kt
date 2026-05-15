package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.ailife.clox.R
import com.ailife.clox.qs.QsPermissionHelper

class GesturesSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_gestures, rootKey)

        findPreference<androidx.preference.SwitchPreferenceCompat>("home_press_screen_off")
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
                true
            }
    }
}
