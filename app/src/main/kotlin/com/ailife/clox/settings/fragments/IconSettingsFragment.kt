package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.ailife.clox.R
import com.ailife.clox.icon.IconPackLoader

class IconSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_icon, rootKey)

        val packPref = findPreference<ListPreference>("icon_pack")
        val packs = IconPackLoader.getInstalledPacks(requireContext())
        val entries = arrayOf("System default") + packs.map { it.second }.toTypedArray()
        val values = arrayOf("") + packs.map { it.first }.toTypedArray()
        packPref?.entries = entries
        packPref?.entryValues = values
    }
}
