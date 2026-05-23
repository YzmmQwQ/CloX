package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import com.ailife.clox.R
import com.ailife.clox.icon.IconPackLoader

class IconSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_icon, rootKey)

        val packPref = findPreference<ListPreference>("icon_pack")
        val packs = IconPackLoader.getInstalledPacks(requireContext())
        val entries = arrayOf(getString(R.string.pref_icon_pack_none)) + packs.map { it.second }.toTypedArray()
        val values = arrayOf("") + packs.map { it.first }.toTypedArray()
        packPref?.entries = entries
        packPref?.entryValues = values

        // Invalidate icon pack cache and reload app list when selection changes
        packPref?.setOnPreferenceChangeListener { _, newValue ->
            IconPackLoader.setSelectedPack(requireContext(), (newValue as String).ifEmpty { null })
            true
        }
    }
}
