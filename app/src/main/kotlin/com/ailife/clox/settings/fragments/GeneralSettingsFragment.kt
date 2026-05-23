package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import com.ailife.clox.R

class GeneralSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_general, rootKey)
    }
}
