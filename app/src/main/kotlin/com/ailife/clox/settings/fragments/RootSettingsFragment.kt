package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.Preference
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.HideAppsFragment

class RootSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)

        // Navigate to HideAppsFragment manually (it's a plain Fragment, not PreferenceFragment)
        findPreference<Preference>("nav_hide_apps")?.setOnPreferenceClickListener {
            requireActivity().supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, HideAppsFragment())
                .addToBackStack("hide_apps")
                .commit()
            requireActivity().title = getString(R.string.hidden_apps_title)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateHideAppsVisibility()
    }

    private fun updateHideAppsVisibility() {
        val pref = findPreference<Preference>("nav_hide_apps") ?: return
        val password = AppListPrefs.hideAppsPassword(requireContext())
        pref.isVisible = password == null
    }
}
