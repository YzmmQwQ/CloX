package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.ailife.clox.R

class RootSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)
    }
}
