package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.ailife.clox.R

class GesturesSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_gestures, rootKey)
    }
}
