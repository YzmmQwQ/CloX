package com.yzmm.clox.settings.compose

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.yzmm.clox.cocos.bridge.WeatherPrefs
import com.yzmm.clox.cocos.bridge.WeatherRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WeatherScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val scope = rememberCoroutineScope()

    val providerEntries = stringArrayResource(R.array.weather_provider_entries)
    val providerValues = stringArrayResource(R.array.weather_provider_values)
    val intervalEntries = stringArrayResource(R.array.weather_interval_entries)
    val intervalValues = stringArrayResource(R.array.weather_interval_values)

    var enabled by remember { mutableStateOf(WeatherPrefs.enabled(ctx)) }
    var provider by remember {
        mutableStateOf(prefs.getString(WeatherPrefs.KEY_PROVIDER, "metno") ?: "metno")
    }
    var autoLanguage by remember { mutableStateOf(WeatherPrefs.autoLanguage(ctx)) }
    var roundTemp by remember { mutableStateOf(WeatherPrefs.roundTemp(ctx)) }
    var interval by remember {
        mutableStateOf(prefs.getString(WeatherPrefs.KEY_INTERVAL_MINUTES, "30") ?: "30")
    }
    var weatherapiKey by remember {
        mutableStateOf(prefs.getString(WeatherPrefs.KEY_WEATHERAPI_KEY, "") ?: "")
    }
    var owmKey by remember {
        mutableStateOf(prefs.getString(WeatherPrefs.KEY_OWM_KEY, "") ?: "")
    }
    var refreshing by remember { mutableStateOf(false) }
    var lastFetch by remember { mutableStateOf(lastFetchSummary(ctx)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showProvider by remember { mutableStateOf(false) }
    var showInterval by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf<KeyEdit?>(null) }

    fun masked(value: String, emptyRes: Int): String =
        if (value.isBlank()) ctx.getString(emptyRes) else "····${value.takeLast(4)}"

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lastFetch = lastFetchSummary(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.pref_weather_title)) {
        SettingsSwitch(
            title = res(R.string.pref_weather_enabled_title),
            summary = res(R.string.pref_weather_enabled_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean(WeatherPrefs.KEY_ENABLED, it).apply()
            },
        )

        SectionHeader(res(R.string.pref_weather_category_source))
        SettingsButton(
            title = res(R.string.pref_weather_provider_title),
            summary = providerEntries.getOrElse(providerValues.indexOf(provider)) { provider },
        ) { showProvider = true }
        SettingsSwitch(
            title = res(R.string.pref_weather_language_title),
            summary = res(R.string.pref_weather_language_summary),
            checked = autoLanguage,
            onCheckedChange = {
                autoLanguage = it
                prefs.edit().putBoolean(WeatherPrefs.KEY_AUTO_LANGUAGE, it).apply()
            },
        )
        if (provider == "weatherapi") {
            SettingsButton(
                title = res(R.string.pref_weather_weatherapi_key_title),
                summary = masked(weatherapiKey, R.string.pref_weather_weatherapi_key_summary),
            ) {
                editKey = KeyEdit(
                    WeatherPrefs.KEY_WEATHERAPI_KEY,
                    ctx.getString(R.string.pref_weather_weatherapi_key_title),
                )
            }
        }
        if (provider == "openweathermap") {
            SettingsButton(
                title = res(R.string.pref_weather_owm_key_title),
                summary = masked(owmKey, R.string.pref_weather_owm_key_summary),
            ) {
                editKey = KeyEdit(
                    WeatherPrefs.KEY_OWM_KEY,
                    ctx.getString(R.string.pref_weather_owm_key_title),
                )
            }
        }

        SectionHeader(res(R.string.pref_weather_category_display))
        SettingsSwitch(
            title = res(R.string.pref_weather_round_title),
            summary = res(R.string.pref_weather_round_summary),
            checked = roundTemp,
            onCheckedChange = {
                roundTemp = it
                prefs.edit().putBoolean(WeatherPrefs.KEY_ROUND_TEMP, it).apply()
            },
        )

        SectionHeader(res(R.string.pref_weather_category_update))
        SettingsButton(
            title = res(R.string.pref_weather_interval_title),
            summary = intervalEntries.getOrElse(intervalValues.indexOf(interval)) { interval },
        ) { showInterval = true }
        SettingsButton(
            title = res(
                if (refreshing) R.string.pref_weather_refreshing else R.string.pref_weather_refresh_title),
            summary = lastFetch ?: res(R.string.pref_weather_refresh_summary),
            enabled = !refreshing,
        ) {
            if (!WeatherPrefs.enabled(ctx)) {
                Toast.makeText(ctx, R.string.pref_weather_disabled_toast, Toast.LENGTH_SHORT).show()
                return@SettingsButton
            }
            refreshing = true
            scope.launch {
                val error = WeatherRepository.refresh(ctx)
                refreshing = false
                if (error != null) {
                    errorMessage = error.message?.takeIf { it.isNotBlank() }
                        ?: error.javaClass.simpleName
                } else {
                    lastFetch = lastFetchSummary(ctx)
                }
            }
        }
    }

    if (showProvider) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_weather_provider_title),
            labels = providerEntries,
            selectedIndex = providerValues.indexOf(provider).coerceAtLeast(0),
            onSelect = { index ->
                showProvider = false
                provider = providerValues[index]
                prefs.edit().putString(WeatherPrefs.KEY_PROVIDER, provider).apply()
            },
            onDismiss = { showProvider = false },
        )
    }
    if (showInterval) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_weather_interval_title),
            labels = intervalEntries,
            selectedIndex = intervalValues.indexOf(interval).coerceAtLeast(0),
            onSelect = { index ->
                showInterval = false
                interval = intervalValues[index]
                prefs.edit().putString(WeatherPrefs.KEY_INTERVAL_MINUTES, interval).apply()
            },
            onDismiss = { showInterval = false },
        )
    }
    editKey?.let { edit ->
        SettingsTextDialog(
            title = edit.title,
            initial = if (edit.key == WeatherPrefs.KEY_WEATHERAPI_KEY) weatherapiKey else owmKey,
            onConfirm = { value ->
                editKey = null
                if (edit.key == WeatherPrefs.KEY_WEATHERAPI_KEY) weatherapiKey = value
                else owmKey = value
                prefs.edit().putString(edit.key, value).apply()
            },
            onDismiss = { editKey = null },
        )
    }
    errorMessage?.let { message ->
        SettingsInfoDialog(
            title = stringResource(R.string.pref_weather_refresh_error_title),
            message = message,
            onDismiss = { errorMessage = null },
        )
    }
}

private data class KeyEdit(val key: String, val title: String)

private fun lastFetchSummary(ctx: android.content.Context): String? {
    val json = WeatherRepository.getLatestJson() ?: return null
    val ts = json.optLong("lastUpdate", 0L)
    if (ts == 0L) return null
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    val temp = json.optDouble("currentTemp", 0.0)
    val text = json.optString("conditionText", "")
    return ctx.getString(R.string.pref_weather_last_fetch, temp.toInt(), text, time)
}
