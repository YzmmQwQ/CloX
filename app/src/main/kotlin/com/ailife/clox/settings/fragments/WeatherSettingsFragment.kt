package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.WeatherPrefs
import com.ailife.clox.cocos.bridge.WeatherRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherSettingsFragment : BaseSettingsFragment() {

    private lateinit var tvRefreshTitle: TextView
    private lateinit var tvRefreshSummary: TextView
    private lateinit var rowWeatherRefresh: View
    private lateinit var rowWeatherApiKey: View
    private lateinit var rowOwmKey: View
    private lateinit var tvWeatherApiKey: TextView
    private lateinit var tvOwmKey: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_weather, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvRefreshTitle   = view.findViewById(R.id.tvRefreshTitle)
        tvRefreshSummary = view.findViewById(R.id.tvRefreshSummary)
        rowWeatherRefresh = view.findViewById(R.id.rowWeatherRefresh)
        rowWeatherApiKey = view.findViewById(R.id.rowWeatherApiKey)
        rowOwmKey        = view.findViewById(R.id.rowOwmKey)
        tvWeatherApiKey  = view.findViewById(R.id.tvWeatherApiKey)
        tvOwmKey         = view.findViewById(R.id.tvOwmKey)

        val providerEntries  = resources.getStringArray(R.array.weather_provider_entries)
        val providerValues   = resources.getStringArray(R.array.weather_provider_values)
        val intervalEntries  = resources.getStringArray(R.array.weather_interval_entries)
        val intervalValues   = resources.getStringArray(R.array.weather_interval_values)

        // Master switch
        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchWeatherEnabled)
        switchEnabled.isChecked = WeatherPrefs.enabled(requireContext())
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WeatherPrefs.KEY_ENABLED, checked).apply()
        }

        // Provider picker
        val tvProvider = view.findViewById<TextView>(R.id.tvWeatherProvider)
        fun refreshProvider() {
            val v = prefs.getString(WeatherPrefs.KEY_PROVIDER, "metno") ?: "metno"
            tvProvider.text = providerEntries.getOrElse(providerValues.indexOf(v)) { v }
            rowWeatherApiKey.visibility = if (v == "weatherapi") View.VISIBLE else View.GONE
            rowOwmKey.visibility        = if (v == "openweathermap") View.VISIBLE else View.GONE
        }
        refreshProvider()
        view.findViewById<View>(R.id.rowWeatherProvider).setOnClickListener {
            showListPicker(getString(R.string.pref_weather_provider_title),
                providerEntries, providerValues,
                prefs.getString(WeatherPrefs.KEY_PROVIDER, "metno") ?: "metno") { v, _ ->
                prefs.edit().putString(WeatherPrefs.KEY_PROVIDER, v).apply()
                refreshProvider()
            }
        }

        // Auto language
        val switchLang = view.findViewById<MaterialSwitch>(R.id.switchWeatherLanguage)
        switchLang.isChecked = WeatherPrefs.autoLanguage(requireContext())
        switchLang.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WeatherPrefs.KEY_AUTO_LANGUAGE, checked).apply()
        }

        // API key rows
        fun maskedKey(key: String) = prefs.getString(key, "")?.let {
            if (it.isBlank()) getString(R.string.pref_weather_weatherapi_key_summary) else "••••${it.takeLast(4)}"
        } ?: ""
        tvWeatherApiKey.text = maskedKey(WeatherPrefs.KEY_WEATHERAPI_KEY)
        rowWeatherApiKey.setOnClickListener {
            showTextInputDialog(getString(R.string.pref_weather_weatherapi_key_title),
                prefs.getString(WeatherPrefs.KEY_WEATHERAPI_KEY, "") ?: "",
                hint = "API key",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            ) { v ->
                prefs.edit().putString(WeatherPrefs.KEY_WEATHERAPI_KEY, v).apply()
                tvWeatherApiKey.text = maskedKey(WeatherPrefs.KEY_WEATHERAPI_KEY)
            }
        }

        tvOwmKey.text = prefs.getString(WeatherPrefs.KEY_OWM_KEY, "")
            ?.let { if (it.isBlank()) getString(R.string.pref_weather_owm_key_summary) else "••••${it.takeLast(4)}" } ?: ""
        rowOwmKey.setOnClickListener {
            showTextInputDialog(getString(R.string.pref_weather_owm_key_title),
                prefs.getString(WeatherPrefs.KEY_OWM_KEY, "") ?: "",
                hint = "API key",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            ) { v ->
                prefs.edit().putString(WeatherPrefs.KEY_OWM_KEY, v).apply()
            }
        }

        // Round temp
        val switchRound = view.findViewById<MaterialSwitch>(R.id.switchWeatherRound)
        switchRound.isChecked = WeatherPrefs.roundTemp(requireContext())
        switchRound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WeatherPrefs.KEY_ROUND_TEMP, checked).apply()
        }

        // Interval picker
        val tvInterval = view.findViewById<TextView>(R.id.tvWeatherInterval)
        fun refreshInterval() {
            val v = prefs.getString(WeatherPrefs.KEY_INTERVAL_MINUTES, "30") ?: "30"
            tvInterval.text = intervalEntries.getOrElse(intervalValues.indexOf(v)) { v }
        }
        refreshInterval()
        view.findViewById<View>(R.id.rowWeatherInterval).setOnClickListener {
            showListPicker(getString(R.string.pref_weather_interval_title),
                intervalEntries, intervalValues,
                prefs.getString(WeatherPrefs.KEY_INTERVAL_MINUTES, "30") ?: "30") { v, _ ->
                prefs.edit().putString(WeatherPrefs.KEY_INTERVAL_MINUTES, v).apply()
                refreshInterval()
            }
        }

        // Refresh
        rowWeatherRefresh.setOnClickListener {
            val ctx = requireContext()
            if (!WeatherPrefs.enabled(ctx)) {
                Toast.makeText(ctx, R.string.pref_weather_disabled_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rowWeatherRefresh.isEnabled = false
            tvRefreshTitle.text = getString(R.string.pref_weather_refreshing)
            viewLifecycleOwner.lifecycleScope.launch {
                val error = WeatherRepository.refresh(ctx)
                tvRefreshTitle.text = getString(R.string.pref_weather_refresh_title)
                rowWeatherRefresh.isEnabled = true
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
        }

        updateLastFetchSummary()
    }

    override fun onResume() {
        super.onResume()
        updateLastFetchSummary()
    }

    private fun updateLastFetchSummary() {
        val json = WeatherRepository.getLatestJson() ?: return
        val ts   = json.optLong("lastUpdate", 0L)
        if (ts == 0L) return
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        val temp = json.optDouble("currentTemp", 0.0)
        val text = json.optString("conditionText", "")
        tvRefreshSummary.text = getString(R.string.pref_weather_last_fetch, temp.toInt(), text, time)
    }
}
