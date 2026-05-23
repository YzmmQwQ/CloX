package com.ailife.clox.cocos.bridge

import android.content.Context
import android.content.SharedPreferences
import com.ailife.clox.tapaction.TapActionHandler
import android.database.ContentObserver
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.preference.PreferenceManager
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.LuaBridge
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.overlay.widgets.WidgetPrefs
import com.ailife.clox.overlay.widgets.model.WidgetCardConfig
import com.ailife.clox.qs.QsStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Central coordinator for the Lua ↔ Android data bridge.
 *
 * Lifecycle: create in HomeFragment (or wherever CocosManager is initialized),
 * call start() after the Axmol GL view is ready, call stop() in onDestroyView().
 *
 *   val bridge = WatchfaceBridgeManager(requireContext(), qsStateManager)
 *   bridge.start()
 *   // ...
 *   bridge.stop()
 */
class WatchfaceBridgeManager(
    private val ctx: Context,
    private val qsStateManager: QsStateManager,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sensorHelper = SensorHelper(ctx)

    /** Set to true by Lua when the active watchface declares it uses weather data. */
    @Volatile private var watchfaceWantsWeather = false

    /** Set by Lua per-metric when the active watchface declares it uses health data. */
    private val watchfaceWantsHealth = mutableMapOf(
        HealthHelper.Metric.STEPS      to false,
        HealthHelper.Metric.HEART_RATE to false,
        HealthHelper.Metric.SPO2       to false,
    )

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope.launch {
                val data = withContext(Dispatchers.IO) { CalendarHelper.getEvents(ctx) }
                sendToLua("calendar", data)
            }
        }
    }

    // Keys that belong to the "location" Lua category (names + timezone offsets)
    private val locationKeys = (1..5).map { "location_name_$it" }.toSet() +
                               (1..4).map { "location_timezone_$it" }.toSet()

    // Keys that belong to the "settings" Lua category (watchface display settings)
    private val watchfaceSettingsKeys = setOf(
        "watchface_path", "watchface_zoom", "watchface_x_offset", "watchface_y_offset",
        "watchface_fullscreen", "watchface_frame_rate", "watchface_aod_enabled"
    )

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == SensorsPrefs.KEY_CALENDAR -> {
                if (SensorsPrefs.calendarEnabled(ctx) && CalendarHelper.hasPermission(ctx)) {
                    scope.launch {
                        val data = withContext(Dispatchers.IO) { CalendarHelper.getEvents(ctx) }
                        sendToLua("calendar", data)
                    }
                }
            }
            key in locationKeys -> sendToLua("location", LiveDataHelper.locationSettingsJson(ctx))
            key in watchfaceSettingsKeys -> sendToLua("settings", LiveDataHelper.watchfaceSettingsJson(ctx))
        }
    }

    fun start() {
        LuaBridge.luaReadyHandler = { pushAllLiveData() }
        registerWatchfaceActionHandler()
        WeatherRepository.initialize(ctx)   // load cache so Lua gets data before first network fetch
        pushStaticData()
        pushAllLiveData()
        subscribeToLiveData()
        startPeriodicUpdates()
        if (SensorsPrefs.calendarEnabled(ctx) && CalendarHelper.hasPermission(ctx)) {
            try {
                ctx.contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI, true, calendarObserver)
            } catch (_: Exception) {
                // No calendar provider on this device (e.g. watches without a calendar app)
            }
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun stop() {
        LuaBridge.luaReadyHandler = null
        LuaBridge.watchfaceActionHandler = null
        ctx.contentResolver.unregisterContentObserver(calendarObserver)
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        sensorHelper.stop()
    }

    // ── Static data (one-time) ────────────────────────────────────────────────

    private fun pushStaticData() {
        sendToLua("device", DeviceInfoHelper.getInfo(ctx))
    }

    // ── Initial snapshot of all live categories ───────────────────────────────

    internal fun pushAllLiveData() {
        // Static device info — must be included here because pushStaticData() runs before
        // the Lua VM is ready (WatchfaceBridgeDispatch isn't defined yet at that point).
        sendToLua("device", DeviceInfoHelper.getInfo(ctx))

        // Settings pushed first — watchface engine must not load until watchfacePath is valid.
        // These are cheap reads from SharedPreferences (main thread is fine).
        sendToLua("settings", LiveDataHelper.watchfaceSettingsJson(ctx))
        sendToLua("location", LiveDataHelper.locationSettingsJson(ctx))

        // StateFlow current values — always populated since QsStateManager is attached at app start.
        sendToLua("battery", LiveDataHelper.batteryJson(ctx, qsStateManager.battery.value))
        sendToLua("network", LiveDataHelper.networkJson(ctx, qsStateManager.wifi.value, qsStateManager.data.value))
        sendToLua("connectivity", LiveDataHelper.connectivityJson(qsStateManager.bluetooth.value, qsStateManager.location.value))
        sendToLua("notification", notificationJson(
            com.ailife.clox.overlay.notifications.CloXNotificationListenerService.currentNotifications,
            com.ailife.clox.overlay.notifications.CloXNotificationListenerService.isRunning()
        ))
        sendToLua("thirdParty", ExternalDataStore.toJson(ctx))
        sendToLua("weather", WeatherHelper.getLatestData())   // cached data; weatherFlow collector keeps it fresh
        for (metric in HealthHelper.Metric.entries) {
            sendToLua(HealthHelper.metricCategory(metric), HealthHelper.getLatestData(metric))
        }

        // IO-bound initial reads
        scope.launch {
            val storage = withContext(Dispatchers.IO) { LiveDataHelper.storageJson(ctx) }
            val alarm   = withContext(Dispatchers.IO) { LiveDataHelper.alarmJson(ctx) }
            val cal     = withContext(Dispatchers.IO) { CalendarHelper.getEvents(ctx) }
            sendToLua("storage", storage)
            sendToLua("alarm", alarm)
            sendToLua("calendar", cal)
        }
    }

    // ── Observer-based live updates ───────────────────────────────────────────

    private fun subscribeToLiveData() {
        qsStateManager.battery.onEach { state ->
            sendToLua("battery", LiveDataHelper.batteryJson(ctx, state))
        }.launchIn(scope)

        qsStateManager.wifi.onEach { wifi ->
            sendToLua("network", LiveDataHelper.networkJson(ctx, wifi, qsStateManager.data.value))
        }.launchIn(scope)

        qsStateManager.data.onEach { data ->
            sendToLua("network", LiveDataHelper.networkJson(ctx, qsStateManager.wifi.value, data))
        }.launchIn(scope)

        qsStateManager.bluetooth.onEach { bt ->
            sendToLua("connectivity", LiveDataHelper.connectivityJson(bt, qsStateManager.location.value))
        }.launchIn(scope)

        qsStateManager.location.onEach { loc ->
            sendToLua("connectivity", LiveDataHelper.connectivityJson(qsStateManager.bluetooth.value, loc))
        }.launchIn(scope)

        com.ailife.clox.overlay.notifications.CloXNotificationListenerService.notifications.onEach { notifs ->
            val running = com.ailife.clox.overlay.notifications.CloXNotificationListenerService.isRunning()
            sendToLua("notification", notificationJson(notifs, running))
        }.launchIn(scope)
    }

    // ── Periodic updates for data that doesn't have a push observer ───────────

    private fun startPeriodicUpdates() {
        // Re-push thirdParty whenever any external slot is written via broadcast
        scope.launch {
            ExternalDataStore.slotUpdated.collect {
                sendToLua("thirdParty", ExternalDataStore.toJson(ctx))
            }
        }

        // Storage and alarm don't have system observers; poll every 30s.
        scope.launch {
            while (isActive) {
                delay(30_000)
                val storage = withContext(Dispatchers.IO) { LiveDataHelper.storageJson(ctx) }
                val alarm   = withContext(Dispatchers.IO) { LiveDataHelper.alarmJson(ctx) }
                sendToLua("storage", storage)
                sendToLua("alarm", alarm)
            }
        }

        // Calendar is observer-driven (CalendarContract ContentObserver registered in start()).
        // No polling loop needed here.

        // Push to Lua whenever WeatherRepository emits a fresh result (network fetch or cache load)
        scope.launch {
            WeatherRepository.weatherFlow.collect { json ->
                sendToLua("weather", json)
            }
        }

        // Weather fetch loop — ticks every minute, fetches when the configured interval
        // has elapsed since the last successful update and the feature is in use.
        scope.launch {
            while (isActive) {
                val enabled      = WeatherPrefs.enabled(ctx)
                val wantsWeather = watchfaceWantsWeather
                val hasWidget    = hasWeatherWidget()
                if (enabled && (wantsWeather || hasWidget)) {
                    val lastUpdate = WeatherRepository.getLatestJson()?.optLong("lastUpdate", 0L) ?: 0L
                    val intervalMs = WeatherPrefs.intervalMs(ctx)
                    val elapsed    = System.currentTimeMillis() - lastUpdate
                    Log.d(TAG, "Weather tick: elapsed=${elapsed / 60_000}min interval=${intervalMs / 60_000}min")
                    if (elapsed >= intervalMs) {
                        WeatherRepository.refresh(ctx)
                    }
                }
                delay(60_000)
            }
        }

        // Push to Lua whenever a health metric is updated — covers both periodic and manual refresh.
        scope.launch {
            HealthHelper.dataUpdated.collect { metric ->
                sendToLua(HealthHelper.metricCategory(metric), HealthHelper.getLatestData(metric))
            }
        }

        // Health periodic fetch loop — ticks every minute, fetches each metric when
        // its configured interval has elapsed and the watchface requests it.
        scope.launch {
            while (isActive) {
                val intervalMs = HealthPrefs.intervalMs(ctx)
                for (metric in HealthHelper.Metric.entries) {
                    if (watchfaceWantsHealth[metric] != true) continue
                    val lastFetch = HealthHelper.getLastFetchTimeMs(metric) ?: 0L
                    val elapsed   = System.currentTimeMillis() - lastFetch
                    if (elapsed >= intervalMs) {
                        HealthHelper.fetchAndUpdate(metric, ctx) { interim ->
                            sendToLua(HealthHelper.metricCategory(metric), interim)
                        }
                    }
                }
                delay(60_000)
            }
        }
    }

    // ── Lua → Android action handler ──────────────────────────────────────────

    private fun registerWatchfaceActionHandler() {
        LuaBridge.watchfaceActionHandler = { payload -> handleWatchfaceAction(payload) }
    }

    // ── Java → Lua: watchface lifecycle commands ──────────────────────────────

    /** Tell the Lua watchface engine to load a different watchface. */
    fun setActiveWatchface(path: String) =
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnSetActive", path) }

    /** Tell the Lua watchface engine to destroy and reload the current watchface. */
    fun reloadWatchface() =
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnReload", "") }

    /** Tell the Lua watchface engine to clear persistent storage and reload. */
    fun resetWatchface() =
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnReset", "") }

    // ── Lua → Android: action handler ────────────────────────────────────────

    private fun handleWatchfaceAction(payload: String) {
        try {
            val json = JSONObject(payload)
            when (json.optString("action")) {
                "tapAction" -> {
                    val shortcut = json.optString("shortcut")
                    // Must run on main thread — startActivity and AudioManager require it
                    android.os.Handler(Looper.getMainLooper()).post {
                        TapActionHandler.handle(ctx, shortcut)
                    }
                }
                "setWeatherEnabled" -> {
                    val enabled = json.optBoolean("enabled")
                    watchfaceWantsWeather = enabled
                    if (enabled && WeatherPrefs.enabled(ctx)) {
                        scope.launch { checkAndRefreshWeather() }
                    }
                }
                "setHealthEnabled" -> {
                    val metric = HealthHelper.metricFromName(json.optString("metric"))
                    val enabled = json.optBoolean("enabled")
                    if (metric != null) {
                        watchfaceWantsHealth[metric] = enabled
                        if (enabled) scope.launch { checkAndRefreshHealth(metric) }
                    }
                }
                "setSensorEnabled" -> sensorHelper.setEnabled(
                    json.optString("sensor"),
                    json.optBoolean("enabled")
                ) { sensor, data -> sendToLua("sensor_$sensor", data) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleWatchfaceAction error", e)
        }
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    private fun notificationJson(
        notifs: List<android.service.notification.StatusBarNotification>,
        listenerRunning: Boolean
    ): JSONObject = JSONObject().apply {
        put("count", if (listenerRunning) notifs.size else -1)
        val last = notifs.maxByOrNull { it.postTime }
        put("last", JSONObject().apply {
            val extras = last?.notification?.extras
            put("appName", last?.let {
                try { ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(it.packageName, 0)).toString()
                } catch (e: Exception) { it.packageName }
            } ?: "")
            put("title",   extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "")
            put("content", extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "")
            put("time",    last?.postTime ?: 0L)
        })
    }

    private fun sendToLua(category: String, data: JSONObject) {
        val payload = JSONObject().apply {
            put("category", category)
            put("data", data)
        }.toString()
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceBridgeDispatch", payload) }
    }

    // All Lua calls must run on the Axmol GL thread.
    private fun queueOnGL(block: () -> Unit) {
        val glView = CocosManager.getGlView() as? GLSurfaceView
        if (glView != null) {
            glView.queueEvent(block)
        } else {
            Log.w(TAG, "queueOnGL skipped — GL view not ready")
        }
    }

    private suspend fun checkAndRefreshWeather() {
        val lastUpdate = WeatherRepository.getLatestJson()?.optLong("lastUpdate", 0L) ?: 0L
        val elapsed    = System.currentTimeMillis() - lastUpdate
        if (elapsed >= WeatherPrefs.intervalMs(ctx)) WeatherRepository.refresh(ctx)
    }

    private suspend fun checkAndRefreshHealth(metric: HealthHelper.Metric) {
        val lastFetch = HealthHelper.getLastFetchTimeMs(metric) ?: 0L
        val elapsed   = System.currentTimeMillis() - lastFetch
        if (elapsed >= HealthPrefs.intervalMs(ctx)) {
            HealthHelper.fetchAndUpdate(metric, ctx) { interim ->
                sendToLua(HealthHelper.metricCategory(metric), interim)
            }
        }
    }

    private fun hasWeatherWidget(): Boolean =
        WidgetPrefs.loadCards(ctx).any { it is WidgetCardConfig.BuiltInWeather }

    companion object {
        private const val TAG = "WatchfaceBridgeMgr"
    }
}
