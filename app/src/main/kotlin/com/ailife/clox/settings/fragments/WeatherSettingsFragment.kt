package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.WeatherPrefs
import com.ailife.clox.cocos.bridge.WeatherRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_weather, rootKey)
        setupProviderDependencies()
        setupApiKeyInputType()
        setupRefreshButton()
        updateLastFetchSummary()
    }

    override fun onResume() {
        super.onResume()
        updateLastFetchSummary()
    }

    // Show/hide API key fields based on selected provider
    private fun setupProviderDependencies() {
        val providerPref = findPreference<ListPreference>(WeatherPrefs.KEY_PROVIDER) ?: return
        updateKeyVisibility(providerPref.value)
        providerPref.setOnPreferenceChangeListener { _, newValue ->
            view?.post { updateKeyVisibility(newValue as String) }
            true
        }
    }

    private fun updateKeyVisibility(provider: String) {
        findPreference<EditTextPreference>(WeatherPrefs.KEY_WEATHERAPI_KEY)?.isVisible =
            provider == "weatherapi"
        findPreference<EditTextPreference>(WeatherPrefs.KEY_OWM_KEY)?.isVisible =
            provider == "openweathermap"
    }

    private fun setupApiKeyInputType() {
        for (key in listOf(WeatherPrefs.KEY_WEATHERAPI_KEY, WeatherPrefs.KEY_OWM_KEY)) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
    }

    private fun setupRefreshButton() {
        findPreference<Preference>("weather_refresh_now")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            if (!WeatherPrefs.enabled(ctx)) {
                Toast.makeText(ctx, R.string.pref_weather_disabled_toast, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            val pref = it
            pref.isEnabled = false
            pref.title = getString(R.string.pref_weather_refreshing)
            viewLifecycleOwner.lifecycleScope.launch {
                val error = WeatherRepository.refresh(ctx)
                pref.title = getString(R.string.pref_weather_refresh_title)
                pref.isEnabled = true
                if (error != null) {
                    val msg = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.pref_weather_refresh_error_title)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    updateLastFetchSummary()
                }
            }
            true
        }
    }

    private fun updateLastFetchSummary() {
        val json = WeatherRepository.getLatestJson() ?: return
        val ts   = json.optLong("lastUpdate", 0L)
        if (ts == 0L) return
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        val temp = json.optDouble("currentTemp", 0.0)
        val text = json.optString("conditionText", "")
        findPreference<Preference>("weather_refresh_now")?.summary =
            getString(R.string.pref_weather_last_fetch, temp.toInt(), text, time)
    }
}
