package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.BhtApiClient
import com.ailife.clox.cocos.bridge.HealthHelper
import com.ailife.clox.cocos.bridge.HealthPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HealthSettingsFragment : BasePreferenceFragment() {

    private var refreshJob: Job? = null
    private var observeJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_health, rootKey)

        setupBhtStatus()
        setupMetricSwitch(HealthPrefs.KEY_STEPS_ENABLED)
        setupMetricSwitch(HealthPrefs.KEY_HR_ENABLED)
        setupMetricSwitch(HealthPrefs.KEY_SPO2_ENABLED)
        setupRefreshButton()
        updateDependentVisibility()
    }

    override fun onResume() {
        super.onResume()
        setupBhtStatus()
        updateDependentVisibility()
        updateAllSummaries()

        // Observe dataUpdated so summaries refresh automatically after any fetch
        observeJob?.cancel()
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            HealthHelper.dataUpdated.collect { metric ->
                updateMetricSummary(metric)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        observeJob?.cancel()
        observeJob = null
    }

    // ── BHT status ────────────────────────────────────────────────────────────

    private fun setupBhtStatus() {
        val pref = findPreference<Preference>("health_bht_status") ?: return
        val ctx  = requireContext()
        if (BhtApiClient.isInstalled(ctx)) {
            pref.summary = getString(R.string.pref_health_bht_installed)
            pref.onPreferenceClickListener = null
        } else {
            pref.summary = getString(R.string.pref_health_bht_not_installed)
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                openPlayStore()
                true
            }
        }
    }

    private fun openPlayStore() {
        val ctx = requireContext()
        val marketIntent = Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${BhtApiClient.PACKAGE}"))
        if (marketIntent.resolveActivity(ctx.packageManager) != null) {
            startActivity(marketIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=${BhtApiClient.PACKAGE}")))
        }
    }

    // ── Metric switches ───────────────────────────────────────────────────────

    private fun setupMetricSwitch(key: String) {
        val pref = findPreference<SwitchPreferenceCompat>(key) ?: return
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling && !BhtApiClient.isInstalled(requireContext())) {
                Toast.makeText(requireContext(),
                    R.string.pref_health_not_installed_toast, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }
            view?.post { updateDependentVisibility() }
            true
        }
    }

    // ── Refresh button ────────────────────────────────────────────────────────

    private fun setupRefreshButton() {
        findPreference<Preference>("health_refresh_now")?.setOnPreferenceClickListener {
            startRefresh()
            true
        }
    }

    private fun startRefresh() {
        if (refreshJob?.isActive == true) return
        val refreshPref = findPreference<Preference>("health_refresh_now") ?: return
        val ctx = requireContext()

        if (!BhtApiClient.isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.pref_health_not_installed_toast, Toast.LENGTH_SHORT).show()
            return
        }

        refreshPref.title = getString(R.string.pref_health_refreshing)
        refreshPref.isEnabled = false

        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            for (metric in HealthHelper.Metric.entries) {
                // fetchAndUpdate emits dataUpdated → WatchfaceBridgeManager pushes to Lua,
                // and our observeJob collector updates the summary UI.
                HealthHelper.fetchAndUpdate(metric, ctx)
            }
            refreshPref.title = getString(R.string.pref_health_refresh_title)
            refreshPref.isEnabled = true
        }
    }

    // ── Summaries ─────────────────────────────────────────────────────────────

    private fun updateAllSummaries() {
        for (metric in HealthHelper.Metric.entries) updateMetricSummary(metric)
    }

    private fun updateMetricSummary(metric: HealthHelper.Metric) {
        val ctx = requireContext()
        val prefKey = when (metric) {
            HealthHelper.Metric.STEPS      -> HealthPrefs.KEY_STEPS_ENABLED
            HealthHelper.Metric.HEART_RATE -> HealthPrefs.KEY_HR_ENABLED
            HealthHelper.Metric.SPO2       -> HealthPrefs.KEY_SPO2_ENABLED
        }
        val pref = findPreference<SwitchPreferenceCompat>(prefKey) ?: return

        val lastTime = HealthHelper.getLastFetchTimeMs(metric)
        if (lastTime == null || !HealthHelper.isEnabled(ctx, metric)) {
            pref.summary = defaultSummary(metric)
            return
        }

        val data = HealthHelper.getLatestData(metric)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastTime))
        pref.summary = when (metric) {
            HealthHelper.Metric.STEPS -> {
                val steps = data.optInt("steps")
                val dist  = data.optDouble("distance")
                val cal   = data.optDouble("calories")
                "%,d steps · %.1f km · %.0f kcal · %s".format(steps, dist, cal, timeStr)
            }
            HealthHelper.Metric.HEART_RATE -> {
                val bpm = data.optInt("bpm")
                "$bpm bpm · $timeStr"
            }
            HealthHelper.Metric.SPO2 -> {
                val pct = data.optInt("percent")
                "$pct% SpO2 · $timeStr"
            }
        }
    }

    private fun defaultSummary(metric: HealthHelper.Metric): String = when (metric) {
        HealthHelper.Metric.STEPS      -> getString(R.string.pref_health_steps_summary)
        HealthHelper.Metric.HEART_RATE -> getString(R.string.pref_health_hr_summary)
        HealthHelper.Metric.SPO2       -> getString(R.string.pref_health_spo2_summary)
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    private fun updateDependentVisibility() {
        val ctx = requireContext()

        val stepsOn = findPreference<SwitchPreferenceCompat>(HealthPrefs.KEY_STEPS_ENABLED)?.isChecked
            ?: HealthPrefs.stepsEnabled(ctx)
        findPreference<SwitchPreferenceCompat>(HealthPrefs.KEY_INSTANT_STEPS)?.isVisible = stepsOn

        val hrOn   = findPreference<SwitchPreferenceCompat>(HealthPrefs.KEY_HR_ENABLED)?.isChecked
            ?: HealthPrefs.hrEnabled(ctx)
        val spo2On = findPreference<SwitchPreferenceCompat>(HealthPrefs.KEY_SPO2_ENABLED)?.isChecked
            ?: HealthPrefs.spo2Enabled(ctx)
        findPreference<SwitchPreferenceCompat>(HealthPrefs.KEY_INSTANT_HR_SPO2)?.isVisible = hrOn || spo2On

        // Refresh button only useful when at least one metric is enabled
        findPreference<Preference>("health_refresh_now")?.isEnabled =
            (stepsOn || hrOn || spo2On) && BhtApiClient.isInstalled(ctx)
    }
}
