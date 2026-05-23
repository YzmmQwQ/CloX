package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.EditTextPreference
import com.ailife.clox.MainActivity
import com.ailife.clox.R

class AppearanceSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_appearance, rootKey)

        findPreference<EditTextPreference>("app_dpi_override")
            ?.setOnPreferenceChangeListener { _, _ ->
                // Restart MainActivity so applyOverrideConfiguration picks up the new value
                activity?.let {
                    val intent = it.packageManager
                        .getLaunchIntentForPackage(it.packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    it.finish()
                    if (intent != null) it.startActivity(intent)
                }
                true
            }
    }
}
