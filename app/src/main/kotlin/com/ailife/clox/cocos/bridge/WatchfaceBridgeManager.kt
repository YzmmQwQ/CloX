package com.ailife.clox.cocos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.ailife.clox.tapaction.TapActionHandler
import android.database.ContentObserver
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
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

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            sendToLua("system", LiveDataHelper.systemStateJson(ctx))
        }
    }

    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sendToLua("system", LiveDataHelper.systemStateJson(ctx))
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
            key in locationKeys -> {
                sendToLua("location", LiveDataHelper.locationSettingsJson(ctx))
                sendToLua("timezones", LiveDataHelper.timezonesJson(ctx))
            }
            key in watchfaceSettingsKeys ||
                com.ailife.clox.watchface.WatchfaceConfigPrefs.isConfigKey(key) -> {
                sendToLua("settings", LiveDataHelper.watchfaceSettingsJson(ctx))
                // A face switch (selector / import / random) travels this path.
                // If the renderer is gone the push above was dropped silently —
                // that must surface as a dialog, never as a black screen.
                if (key == "watchface_path") expectFaceLoad("watchface change")
            }
        }
    }

    // ── Renderer-alive watchdog ───────────────────────────────────────────────
    // Lua fires "wf_loaded" after EVERY successful face build (including the
    // built-in fallback face), so it doubles as a liveness signal: whenever a
    // load is expected (bridge start, face switch, explicit reload) and no
    // wf_loaded arrives in time — or the GL view is already gone — the user
    // gets a dialog with a restart option instead of a silent black screen.

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastLoadedAt = 0L
    private var watchdogRunnable: Runnable? = null
    private var deadDialog: android.app.Dialog? = null

    private val luaEventListener: (String, String) -> Unit = { event, _ ->
        if (event == "wf_loaded") {
            lastLoadedAt = android.os.SystemClock.elapsedRealtime()
            mainHandler.post {
                deadDialog?.dismiss()
                deadDialog = null
            }
        }
    }

    /** A face load is expected now: fail fast if the GL view is gone, otherwise
     *  demand a wf_loaded within the timeout. */
    private fun expectFaceLoad(reason: String) {
        if (CocosManager.getGlView() == null) {
            Log.e(TAG, "expectFaceLoad($reason): GL view is gone — renderer dead")
            mainHandler.post { showRendererDeadDialog(reason, "The rendering engine is not running (GL view missing).") }
            return
        }
        armWatchdog(reason)
    }

    private fun armWatchdog(reason: String) {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val armedAt = android.os.SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                if (lastLoadedAt >= armedAt) return
                if (CocosManager.isPaused()) {
                    // GL legitimately paused (screen off / backgrounded): the
                    // load will finish on resume — keep waiting, don't alert.
                    mainHandler.postDelayed(this, WATCHDOG_MS)
                    return
                }
                Log.e(TAG, "watchdog($reason): no wf_loaded within ${WATCHDOG_MS}ms")
                showRendererDeadDialog(
                    reason,
                    "The watchface engine did not finish loading within ${WATCHDOG_MS / 1000}s. " +
                    "The screen would stay black or frozen."
                )
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, WATCHDOG_MS)
    }

    /** Liveness probe: queue a no-op onto the GL thread; if it never executes
     *  (and the GL isn't just paused), the render thread is hung or gone.
     *  Unlike armWatchdog this makes no assumption that a face load is coming
     *  — correct for startup on a reused engine. */
    private fun probeRendererAlive(reason: String) {
        val glView = CocosManager.getGlView() as? GLSurfaceView
        if (glView == null) {
            Log.e(TAG, "probeRendererAlive($reason): GL view is gone")
            showRendererDeadDialog(reason, "The rendering engine is not running (GL view missing).")
            return
        }
        val alive = java.util.concurrent.atomic.AtomicBoolean(false)
        glView.queueEvent { alive.set(true) }
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (alive.get()) return
                if (CocosManager.isPaused()) {
                    mainHandler.postDelayed(this, WATCHDOG_MS)
                    return
                }
                Log.e(TAG, "watchdog($reason): GL thread did not respond within ${WATCHDOG_MS}ms")
                showRendererDeadDialog(
                    reason,
                    "The rendering thread did not respond within ${WATCHDOG_MS / 1000}s. " +
                    "The watchface is hung or the engine is dead."
                )
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, WATCHDOG_MS)
    }

    private fun showRendererDeadDialog(reason: String, detail: String) {
        if (deadDialog?.isShowing == true) return
        try {
            deadDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Watchface renderer not responding")
                .setMessage("Trigger: $reason\n\n$detail\n\nRestarting the launcher recovers it.")
                .setPositiveButton("Restart launcher") { _, _ -> CocosManager.restartProcess(ctx) }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showRendererDeadDialog failed", e)
        }
    }

    fun start() {
        // Push the full snapshot only once Lua signals it is ready (wf_lua_ready,
        // fired at the end of the scene's onEnter). Pushing eagerly here would
        // queue WatchfaceBridgeDispatch onto the GL thread before the dispatcher /
        // bridge handlers exist — at best dropped, at worst a GL-unsafe callback.
        LuaBridge.luaReadyHandler = { pushAllLiveData() }
        registerWatchfaceActionHandler()
        LuaBridge.addEventListner(luaEventListener)
        // Startup uses a GL-thread liveness PROBE, not a wf_loaded demand:
        // when the activity was recreated and the live GL view reused, the
        // face is already on screen and no wf_loaded will ever arrive —
        // demanding one false-alarmed the dialog on every recreation.
        probeRendererAlive("startup")
        WeatherRepository.initialize(ctx)   // load cache so Lua gets data before first network fetch
        subscribeToLiveData()
        startPeriodicUpdates()
        if (SensorsPrefs.calendarEnabled(ctx) && CalendarHelper.hasPermission(ctx)) {
            try {
                ctx.contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI, true, calendarObserver)
            } catch (_: Exception) {}
        }

        ctx.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver)

        val sysFilter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(systemStateReceiver, sysFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(systemStateReceiver, sysFilter)
        }

        PreferenceManager.getDefaultSharedPreferences(ctx)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun stop() {
        LuaBridge.luaReadyHandler = null
        LuaBridge.watchfaceActionHandler = null
        LuaBridge.removeEventListener(luaEventListener)
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        deadDialog?.dismiss()
        deadDialog = null
        ctx.contentResolver.unregisterContentObserver(calendarObserver)
        ctx.contentResolver.unregisterContentObserver(brightnessObserver)
        runCatching { ctx.unregisterReceiver(systemStateReceiver) }
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        sensorHelper.stop()
    }

    // ── Initial snapshot of all live categories ───────────────────────────────

    internal fun pushAllLiveData() {
        // Runs on the wf_lua_ready handshake, so the Lua VM is up and
        // WatchfaceBridgeDispatch is defined. Static device info is included here.
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
        sendToLua("system", LiveDataHelper.systemStateJson(ctx))
        sendToLua("timezones", LiveDataHelper.timezonesJson(ctx))
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

        // Storage and alarm have no system observers; poll every 5s.
        scope.launch {
            while (isActive) {
                delay(5_000)
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
    fun setActiveWatchface(path: String) {
        expectFaceLoad("set active watchface")
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnSetActive", path) }
    }

    /** Tell the Lua watchface engine to destroy and reload the current watchface. */
    fun reloadWatchface() {
        expectFaceLoad("reload watchface")
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnReload", "") }
    }

    /** Tell the Lua watchface engine to clear persistent storage and reload. */
    fun resetWatchface() {
        expectFaceLoad("reset watchface")
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnReset", "") }
    }

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
                "openConfig" -> Handler(Looper.getMainLooper()).post {
                    com.ailife.clox.watchface.config.WatchfaceConfigActivity.start(ctx)
                }
                "luaError" -> {
                    // Always shown (was debug-only): a broken watchface must
                    // fail loudly — a silent black/partial face is never OK.
                    val context = json.optString("context", "Lua error")
                    val trace   = json.optString("trace")
                    Handler(Looper.getMainLooper()).post { showLuaErrorDialog(context, trace) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleWatchfaceAction error", e)
        }
    }

    /**
     * Surface a Lua error + traceback in a visible dialog so a broken watchface
     * fails loudly instead of silently (all builds — a silent black or partial
     * face is never acceptable).
     */
    private fun showLuaErrorDialog(title: String, trace: String) {
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setMessage(if (trace.isBlank()) "(no detail)" else trace)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showLuaErrorDialog failed", e)
        }
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    private fun notificationJson(
        notifs: List<android.service.notification.StatusBarNotification>,
        listenerRunning: Boolean
    ): JSONObject {
        val sorted = notifs.sortedByDescending { it.postTime }

        fun sbnToObj(sbn: android.service.notification.StatusBarNotification?): JSONObject {
            val extras = sbn?.notification?.extras
            val appName = sbn?.let {
                try { ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(it.packageName, 0)).toString()
                } catch (_: Exception) { it.packageName }
            } ?: ""
            return JSONObject().apply {
                put("appName", appName)
                put("title",   extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "")
                put("content", extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "")
                put("time",    sbn?.postTime ?: 0L)
            }
        }

        val list = org.json.JSONArray()
        for (i in 0 until 10) list.put(sbnToObj(sorted.getOrNull(i)))

        return JSONObject().apply {
            put("count", if (listenerRunning) notifs.size else -1)
            put("list",  list)
            put("last",  sbnToObj(sorted.firstOrNull()))
        }
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

        // Generous: a cold start needs GL init + asset extraction + face build.
        private const val WATCHDOG_MS = 15_000L
    }
}
