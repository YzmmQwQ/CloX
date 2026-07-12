package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.BhtApiClient
import com.ailife.clox.cocos.bridge.HealthHelper
import com.ailife.clox.cocos.bridge.HealthPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HealthSettingsFragment : BaseSettingsFragment() {

    private var refreshJob: Job? = null
    private var observeJob: Job? = null

    private lateinit var tvStepsSummary: TextView
    private lateinit var tvHrSummary: TextView
    private lateinit var tvSpo2Summary: TextView
    private lateinit var tvSleepSummary: TextView
    private lateinit var tvStressSummary: TextView
    private lateinit var switchSteps: MaterialSwitch
    private lateinit var switchHr: MaterialSwitch
    private lateinit var switchSpo2: MaterialSwitch
    private lateinit var switchSleep: MaterialSwitch
    private lateinit var switchStress: MaterialSwitch

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(requireContext(), R.string.pref_health_permission_toast, Toast.LENGTH_LONG).show()
            }
        }
    private lateinit var switchInstantSteps: MaterialSwitch
    private lateinit var switchInstantHrSpo2: MaterialSwitch
    private lateinit var rowInstantSteps: View
    private lateinit var rowInstantHrSpo2: View
    private lateinit var btnRefresh: MaterialButton
    private lateinit var tvHealthInterval: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_health, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStepsSummary    = view.findViewById(R.id.tvStepsSummary)
        tvHrSummary       = view.findViewById(R.id.tvHrSummary)
        tvSpo2Summary     = view.findViewById(R.id.tvSpo2Summary)
        tvSleepSummary    = view.findViewById(R.id.tvSleepSummary)
        tvStressSummary   = view.findViewById(R.id.tvStressSummary)
        switchSteps       = view.findViewById(R.id.switchSteps)
        switchHr          = view.findViewById(R.id.switchHr)
        switchSpo2        = view.findViewById(R.id.switchSpo2)
        switchSleep       = view.findViewById(R.id.switchSleep)
        switchStress      = view.findViewById(R.id.switchStress)
        switchInstantSteps    = view.findViewById(R.id.switchInstantSteps)
        switchInstantHrSpo2   = view.findViewById(R.id.switchInstantHrSpo2)
        rowInstantSteps   = view.findViewById(R.id.rowInstantSteps)
        rowInstantHrSpo2  = view.findViewById(R.id.rowInstantHrSpo2)
        btnRefresh        = view.findViewById(R.id.btnRefreshHealth)
        tvHealthInterval  = view.findViewById(R.id.tvHealthInterval)

        val intervalEntries = resources.getStringArray(R.array.health_interval_entries)
        val intervalValues  = resources.getStringArray(R.array.health_interval_values)

        // BHT status
        val cardBht     = view.findViewById<View>(R.id.cardBhtStatus)
        val tvBhtStatus = view.findViewById<TextView>(R.id.tvBhtStatus)
        val ivBhtAction = view.findViewById<View>(R.id.ivBhtAction)
        fun refreshBhtStatus() {
            val installed = BhtApiClient.isInstalled(requireContext())
            tvBhtStatus.text = getString(if (installed) R.string.pref_health_bht_installed else R.string.pref_health_bht_not_installed)
            ivBhtAction.visibility = if (installed) View.GONE else View.VISIBLE
            cardBht.isClickable = !installed
            cardBht.isFocusable = !installed
        }
        refreshBhtStatus()
        cardBht.setOnClickListener { openPlayStore() }

        // Metric switches
        fun bindMetricSwitch(sw: MaterialSwitch, prefKey: String, enabledNow: Boolean) {
            sw.isChecked = enabledNow
            sw.setOnCheckedChangeListener { _, checked ->
                if (checked && !BhtApiClient.isInstalled(requireContext())) {
                    Toast.makeText(requireContext(), R.string.pref_health_not_installed_toast, Toast.LENGTH_SHORT).show()
                    sw.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (checked && !BhtApiClient.hasReadPermission(requireContext())) {
                    permissionLauncher.launch(BhtApiClient.PERMISSION_READ_DATA)
                }
                prefs.edit().putBoolean(prefKey, checked).apply()
                updateVisibility()
            }
        }
        bindMetricSwitch(switchSteps,  HealthPrefs.KEY_STEPS_ENABLED,  HealthPrefs.stepsEnabled(requireContext()))
        bindMetricSwitch(switchHr,     HealthPrefs.KEY_HR_ENABLED,     HealthPrefs.hrEnabled(requireContext()))
        bindMetricSwitch(switchSpo2,   HealthPrefs.KEY_SPO2_ENABLED,   HealthPrefs.spo2Enabled(requireContext()))
        bindMetricSwitch(switchSleep,  HealthPrefs.KEY_SLEEP_ENABLED,  HealthPrefs.sleepEnabled(requireContext()))
        bindMetricSwitch(switchStress, HealthPrefs.KEY_STRESS_ENABLED, HealthPrefs.stressEnabled(requireContext()))

        switchInstantSteps.isChecked = HealthPrefs.instantSteps(requireContext())
        switchInstantSteps.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(HealthPrefs.KEY_INSTANT_STEPS, checked).apply()
        }

        switchInstantHrSpo2.isChecked = HealthPrefs.instantHrSpo2(requireContext())
        switchInstantHrSpo2.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(HealthPrefs.KEY_INSTANT_HR_SPO2, checked).apply()
        }

        // Interval picker
        fun refreshInterval() {
            val v = prefs.getString(HealthPrefs.KEY_INTERVAL_MINUTES, "10") ?: "10"
            tvHealthInterval.text = intervalEntries.getOrElse(intervalValues.indexOf(v)) { v }
        }
        refreshInterval()
        view.findViewById<View>(R.id.rowHealthInterval).setOnClickListener {
            showListPicker(getString(R.string.pref_health_interval_title),
                intervalEntries, intervalValues,
                prefs.getString(HealthPrefs.KEY_INTERVAL_MINUTES, "10") ?: "10") { v, _ ->
                prefs.edit().putString(HealthPrefs.KEY_INTERVAL_MINUTES, v).apply()
                refreshInterval()
            }
        }

        // Refresh button
        btnRefresh.setOnClickListener { startRefresh() }

        updateVisibility()
        updateAllSummaries()
    }

    override fun onResume() {
        super.onResume()
        updateAllSummaries()
        observeJob?.cancel()
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            HealthHelper.dataUpdated.collect { metric -> updateMetricSummary(metric) }
        }
    }

    override fun onPause() {
        super.onPause()
        observeJob?.cancel()
        observeJob = null
    }

    private fun openPlayStore() {
        val ctx = requireContext()
        startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://gitlab.com/ailife8881/better-health-tracker/-/releases")))
    }

    private fun startRefresh() {
        if (refreshJob?.isActive == true) return
        val ctx = requireContext()
        if (!BhtApiClient.isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.pref_health_not_installed_toast, Toast.LENGTH_SHORT).show()
            return
        }
        btnRefresh.text = getString(R.string.pref_health_refreshing)
        btnRefresh.isEnabled = false
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            for (metric in HealthHelper.Metric.entries) HealthHelper.fetchAndUpdate(metric, ctx)
            btnRefresh.text = getString(R.string.pref_health_refresh_title)
            btnRefresh.isEnabled = true
        }
    }

    private fun updateAllSummaries() {
        for (metric in HealthHelper.Metric.entries) updateMetricSummary(metric)
    }

    private fun updateMetricSummary(metric: HealthHelper.Metric) {
        val ctx = requireContext()
        val tv = when (metric) {
            HealthHelper.Metric.STEPS      -> tvStepsSummary
            HealthHelper.Metric.HEART_RATE -> tvHrSummary
            HealthHelper.Metric.SPO2       -> tvSpo2Summary
            HealthHelper.Metric.SLEEP      -> tvSleepSummary
            HealthHelper.Metric.STRESS     -> tvStressSummary
        }
        val defaultText = when (metric) {
            HealthHelper.Metric.STEPS      -> getString(R.string.pref_health_steps_summary)
            HealthHelper.Metric.HEART_RATE -> getString(R.string.pref_health_hr_summary)
            HealthHelper.Metric.SPO2       -> getString(R.string.pref_health_spo2_summary)
            HealthHelper.Metric.SLEEP      -> getString(R.string.pref_health_sleep_summary)
            HealthHelper.Metric.STRESS     -> getString(R.string.pref_health_stress_summary)
        }
        val lastTime = HealthHelper.getLastFetchTimeMs(metric)
        if (lastTime == null || !HealthHelper.isEnabled(ctx, metric)) {
            tv.text = defaultText; return
        }
        val data = HealthHelper.getLatestData(metric)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastTime))
        tv.text = when (metric) {
            HealthHelper.Metric.STEPS -> {
                "%,d steps · %.1f km · %.0f kcal · %s".format(
                    data.optInt("steps"), data.optDouble("distance"), data.optDouble("calories"), timeStr)
            }
            HealthHelper.Metric.HEART_RATE -> "${data.optInt("bpm")} bpm · $timeStr"
            HealthHelper.Metric.SPO2       -> "${data.optInt("percent")}% SpO2 · $timeStr"
            HealthHelper.Metric.SLEEP      -> {
                if (!data.optBoolean("hasData")) getString(R.string.pref_health_no_sleep_data)
                else {
                    val asleepMin = data.optLong("asleepSec") / 60
                    val deepMin   = data.optLong("deepSec") / 60
                    "%dh %02dm asleep · %dm deep · %s".format(asleepMin / 60, asleepMin % 60, deepMin, timeStr)
                }
            }
            HealthHelper.Metric.STRESS     -> "${data.optInt("score")}/100 · $timeStr"
        }
    }

    private fun updateVisibility() {
        val ctx = requireContext()
        val stepsOn  = switchSteps.isChecked
        val hrOn     = switchHr.isChecked
        val spo2On   = switchSpo2.isChecked
        val sleepOn  = switchSleep.isChecked
        val stressOn = switchStress.isChecked
        rowInstantSteps.visibility  = if (stepsOn) View.VISIBLE else View.GONE
        rowInstantHrSpo2.visibility = if (hrOn || spo2On) View.VISIBLE else View.GONE
        val anyEnabled = (stepsOn || hrOn || spo2On || sleepOn || stressOn) && BhtApiClient.isInstalled(ctx)
        btnRefresh.isEnabled = anyEnabled
    }
}
