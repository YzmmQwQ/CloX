package com.ailife.clox.settings.fragments

import android.os.Bundle
import com.ailife.clox.R

class AodSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_aod, rootKey)
    }
}
