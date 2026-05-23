package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.gesture.ButtonMappingPrefs
import com.ailife.clox.gesture.ButtonRecordDialogFragment
import com.ailife.clox.qs.QsPermissionHelper

class GesturesSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_gestures, rootKey)

        findPreference<androidx.preference.SwitchPreferenceCompat>("home_press_screen_off")
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
                true
            }

        setupButtonMapping()
    }

    private fun setupButtonMapping() {
        val recordPref    = findPreference<Preference>("btn_map_record") ?: return
        val singlePref    = findPreference<ListPreference>("btn_single_press_action")
        val doublePref    = findPreference<ListPreference>("btn_double_press_action")
        val longPref      = findPreference<ListPreference>("btn_long_press_action")
        val clearPref     = findPreference<Preference>("btn_map_clear")

        fun refreshState() {
            val ctx = requireContext()
            val keyCode = ButtonMappingPrefs.getMappedKeyCode(ctx)
            val hasButton = keyCode != 0
            val name = ButtonMappingPrefs.keyCodeToName(keyCode)

            recordPref.summary = if (hasButton) "Mapped: $name — tap to change" else "Tap to record a button"
            singlePref?.isEnabled = hasButton
            doublePref?.isEnabled = hasButton
            longPref?.isEnabled = hasButton
            clearPref?.isVisible = hasButton
        }

        refreshState()

        recordPref.setOnPreferenceClickListener {
            val dialog = ButtonRecordDialogFragment()
            dialog.onKeyRecorded = { keyCode ->
                ButtonMappingPrefs.setMappedKeyCode(requireContext(), keyCode)
                // Reset actions to "nothing" when button changes
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putString(ButtonMappingPrefs.KEY_SINGLE, "nothing")
                    .putString(ButtonMappingPrefs.KEY_DOUBLE, "nothing")
                    .putString(ButtonMappingPrefs.KEY_LONG, "nothing")
                    .apply()
                singlePref?.value = "nothing"
                doublePref?.value = "nothing"
                longPref?.value = "nothing"
                refreshState()
            }
            dialog.show(childFragmentManager, ButtonRecordDialogFragment.TAG)
            true
        }

        clearPref?.setOnPreferenceClickListener {
            ButtonMappingPrefs.clearMappedKeyCode(requireContext())
            refreshState()
            true
        }

        listOf(singlePref, doublePref, longPref).forEach { pref ->
            pref?.setOnPreferenceChangeListener { _, _ -> refreshState(); true }
        }
    }
}
