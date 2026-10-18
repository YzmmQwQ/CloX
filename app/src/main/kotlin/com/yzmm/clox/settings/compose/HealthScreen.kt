package com.yzmm.clox.settings.compose

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.cocos.bridge.BhtApiClient
import com.yzmm.clox.cocos.bridge.HealthHelper
import com.yzmm.clox.cocos.bridge.HealthPrefs
import com.yzmm.clox.util.safeStartActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val scope = rememberCoroutineScope()

    val intervalEntries = stringArrayResource(R.array.health_interval_entries)
    val intervalValues = stringArrayResource(R.array.health_interval_values)

    var installed by remember { mutableStateOf(BhtApiClient.isInstalled(ctx)) }
    var steps by remember { mutableStateOf(HealthPrefs.stepsEnabled(ctx)) }
    var hr by remember { mutableStateOf(HealthPrefs.hrEnabled(ctx)) }
    var spo2 by remember { mutableStateOf(HealthPrefs.spo2Enabled(ctx)) }
    var sleep by remember { mutableStateOf(HealthPrefs.sleepEnabled(ctx)) }
    var stress by remember { mutableStateOf(HealthPrefs.stressEnabled(ctx)) }
    var instantSteps by remember { mutableStateOf(HealthPrefs.instantSteps(ctx)) }
    var instantHrSpo2 by remember { mutableStateOf(HealthPrefs.instantHrSpo2(ctx)) }
    var interval by remember {
        mutableStateOf(prefs.getString(HealthPrefs.KEY_INTERVAL_MINUTES, "10") ?: "10")
    }
    var showInterval by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var summaries by remember {
        mutableStateOf(HealthHelper.Metric.entries.associateWith { defaultSummary(ctx, it) })
    }

    fun summaryFor(metric: HealthHelper.Metric): String {
        if (!HealthHelper.isEnabled(ctx, metric)) return defaultSummary(ctx, metric)
        val lastTime = HealthHelper.getLastFetchTimeMs(metric) ?: return defaultSummary(ctx, metric)
        val data = HealthHelper.getLatestData(metric)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastTime))
        return when (metric) {
            HealthHelper.Metric.STEPS -> "%,d steps · %.1f km · %.0f kcal · %s".format(
                data.optInt("steps"), data.optDouble("distance"),
                data.optDouble("calories"), timeStr)
            HealthHelper.Metric.HEART_RATE -> "${data.optInt("bpm")} bpm · $timeStr"
            HealthHelper.Metric.SPO2 -> "${data.optInt("percent")}% SpO2 · $timeStr"
            HealthHelper.Metric.SLEEP -> {
                if (!data.optBoolean("hasData")) ctx.getString(R.string.pref_health_no_sleep_data)
                else {
                    val asleepMin = data.optLong("asleepSec") / 60
                    val deepMin = data.optLong("deepSec") / 60
                    "%dh %02dm asleep · %dm deep · %s".format(
                        asleepMin / 60, asleepMin % 60, deepMin, timeStr)
                }
            }
            HealthHelper.Metric.STRESS -> "${data.optInt("score")}/100 · $timeStr"
        }
    }

    fun refreshSummaries() {
        summaries = HealthHelper.Metric.entries.associateWith { summaryFor(it) }
    }

    LaunchedEffect(Unit) {
        refreshSummaries()
        HealthHelper.dataUpdated.collect {
            refreshSummaries()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(ctx, R.string.pref_health_permission_toast, Toast.LENGTH_LONG).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = BhtApiClient.isInstalled(ctx)
                refreshSummaries()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.pref_health_title)) {
        SettingsButton(
            title = res(R.string.health_bht_name),
            summary = res(
                if (installed) R.string.pref_health_bht_installed
                else R.string.pref_health_bht_not_installed),
        ) {
            if (!installed) {
                ctx.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://gitlab.com/ailife8881/better-health-tracker/-/releases")))
            }
        }

        SectionHeader(res(R.string.pref_health_category_metrics))

        fun metricToggle(
            titleRes: Int,
            summary: String,
            checked: Boolean,
            onSet: (Boolean) -> Unit,
            key: String,
        ) {
            SettingsSwitch(
                title = res(titleRes),
                summary = summary,
                checked = checked,
                onCheckedChange = { next ->
                    if (next && !BhtApiClient.isInstalled(ctx)) {
                        Toast.makeText(
                            ctx, R.string.pref_health_not_installed_toast, Toast.LENGTH_SHORT).show()
                        return@SettingsSwitch
                    }
                    if (next && !BhtApiClient.hasReadPermission(ctx)) {
                        permissionLauncher.launch(BhtApiClient.PERMISSION_READ_DATA)
                    }
                    onSet(next)
                    prefs.edit().putBoolean(key, next).apply()
                },
            )
        }

        metricToggle(R.string.pref_health_steps_title,
            summaries[HealthHelper.Metric.STEPS] ?: "", steps, { steps = it },
            HealthPrefs.KEY_STEPS_ENABLED)
        if (steps) {
            SettingsSwitch(
                title = res(R.string.pref_health_instant_steps_title),
                summary = res(R.string.pref_health_instant_steps_summary),
                checked = instantSteps,
                onCheckedChange = {
                    instantSteps = it
                    prefs.edit().putBoolean(HealthPrefs.KEY_INSTANT_STEPS, it).apply()
                },
            )
        }

        metricToggle(R.string.pref_health_hr_title,
            summaries[HealthHelper.Metric.HEART_RATE] ?: "", hr, { hr = it },
            HealthPrefs.KEY_HR_ENABLED)
        metricToggle(R.string.pref_health_spo2_title,
            summaries[HealthHelper.Metric.SPO2] ?: "", spo2, { spo2 = it },
            HealthPrefs.KEY_SPO2_ENABLED)
        if (hr || spo2) {
            SettingsSwitch(
                title = res(R.string.pref_health_instant_title),
                summary = res(R.string.pref_health_instant_summary),
                checked = instantHrSpo2,
                onCheckedChange = {
                    instantHrSpo2 = it
                    prefs.edit().putBoolean(HealthPrefs.KEY_INSTANT_HR_SPO2, it).apply()
                },
            )
        }
        metricToggle(R.string.pref_health_sleep_title,
            summaries[HealthHelper.Metric.SLEEP] ?: "", sleep, { sleep = it },
            HealthPrefs.KEY_SLEEP_ENABLED)
        metricToggle(R.string.pref_health_stress_title,
            summaries[HealthHelper.Metric.STRESS] ?: "", stress, { stress = it },
            HealthPrefs.KEY_STRESS_ENABLED)

        SectionHeader(res(R.string.pref_health_category_update))
        SettingsButton(
            title = res(R.string.pref_health_interval_title),
            summary = intervalEntries.getOrElse(intervalValues.indexOf(interval)) { interval },
        ) { showInterval = true }
        SettingsButton(
            title = res(
                if (refreshing) R.string.pref_health_refreshing
                else R.string.pref_health_refresh_title),
            enabled = !refreshing && installed,
        ) {
            if (refreshing || !installed) return@SettingsButton
            refreshing = true
            scope.launch {
                for (metric in HealthHelper.Metric.entries) {
                    HealthHelper.fetchAndUpdate(metric, ctx)
                }
                refreshSummaries()
                refreshing = false
            }
        }
    }

    if (showInterval) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_health_interval_title),
            labels = intervalEntries,
            selectedIndex = intervalValues.indexOf(interval).coerceAtLeast(0),
            onSelect = { index ->
                showInterval = false
                interval = intervalValues[index]
                prefs.edit().putString(HealthPrefs.KEY_INTERVAL_MINUTES, interval).apply()
            },
            onDismiss = { showInterval = false },
        )
    }
}

private fun defaultSummary(ctx: android.content.Context, metric: HealthHelper.Metric): String =
    ctx.getString(when (metric) {
        HealthHelper.Metric.STEPS -> R.string.pref_health_steps_summary
        HealthHelper.Metric.HEART_RATE -> R.string.pref_health_hr_summary
        HealthHelper.Metric.SPO2 -> R.string.pref_health_spo2_summary
        HealthHelper.Metric.SLEEP -> R.string.pref_health_sleep_summary
        HealthHelper.Metric.STRESS -> R.string.pref_health_stress_summary
    })
