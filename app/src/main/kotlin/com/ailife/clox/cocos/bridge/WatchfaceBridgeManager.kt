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

    // Guards the single-live-manager invariant (see start()/stop()).
    private var started = false

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

    // Keys that belong to the "location" Lua category (names + timezone offsets
    // + primary coordinates, which auto-location rewrites on weather fetches)
    private val locationKeys = (1..5).map { "location_name_$it" }.toSet() +
                               (1..4).map { "location_timezone_$it" }.toSet() +
                               setOf(WeatherPrefs.KEY_LAT, WeatherPrefs.KEY_LON,
                                     WeatherPrefs.KEY_ELEVATION)

    // Keys that belong to the "settings" Lua category (watchface display settings)
    private val watchfaceSettingsKeys = setOf(
        "watchface_path", "watchface_zoom", "watchface_x_offset", "watchface_y_offset",
        "watchface_fullscreen", "watchface_frame_rate", "watchface_aod_enabled"
    )

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == KEY_CLEAR_VARS -> clearWatchfaceVars()
            key == KEY_RELOAD -> reloadWatchface()
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
                // Re-resolve the cached render policy: the effective dynamic-FPS
                // value shifts on face switch, per-face override, use-default
                // flip or a defaults-editor change; the AOD dim flag flips on
                // every dim/undim — all of which land in this branch.
                com.ailife.clox.cocos.RenderPolicy.load(ctx)
                // now-off mid-session: drop a stale app-list 1 FPS cap
                if (!com.ailife.clox.cocos.RenderPolicy.dynamicFps) {
                    dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
                }
                // AOD dim ⇄ awake: slow active sensors to ~1 Hz / restore (no-op
                // when unchanged or none active)
                sensorHelper.setLowPower(com.ailife.clox.cocos.RenderPolicy.aodDimmed)
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

    /**
     * Notified whenever a face load is expected (watchface change, reload,
     * reset) — NOT for the startup liveness watch, which may never see a
     * wf_loaded on a reused engine. HomeFragment uses this to show the
     * loading placeholder; the matching hide is the wf_loaded Lua event.
     */
    var onFaceLoadExpected: ((String) -> Unit)? = null

    /** A face load is expected now: watch for wf_loaded (strike-based). */
    private fun expectFaceLoad(reason: String) {
        onFaceLoadExpected?.invoke(reason)
        startRendererWatch(reason, android.os.SystemClock.elapsedRealtime())
    }

    /**
     * Strike-based renderer watch. Every WATCHDOG_MS round it re-acquires the
     * CURRENT GL view, queues a fresh no-op probe onto it, and judges the
     * PREVIOUS round:
     *   - success (a wf_loaded arrived when one is expected, or the previous
     *     probe executed in liveness mode) → done, silently;
     *   - GL paused (screen off / backgrounded) → strikes reset, keep waiting;
     *   - otherwise a strike; TWO consecutive strikes → dialog.
     *
     * One probe/one timeout was not race-safe: a probe queued while the view
     * detaches (activity recreation, selector round-trips) lands on a dying
     * GLThread and silently never runs, and getGlView() can be legitimately
     * null for a moment mid-recreation. Re-probing the live view each round
     * self-heals both; only a renderer that stays dead across two full rounds
     * alarms.
     *
     * expectLoadAfter == null → pure liveness mode (startup on a possibly
     * reused engine, where no wf_loaded will ever come).
     */
    private var watchGen = 0
    private fun startRendererWatch(reason: String, expectLoadAfter: Long?) {
        watchGen += 1
        val gen = watchGen
        var strikes = 0
        var lastProbe: java.util.concurrent.atomic.AtomicBoolean? = null
        lateinit var round: Runnable
        round = Runnable {
            if (gen != watchGen) return@Runnable            // superseded / stopped
            if (expectLoadAfter != null && lastLoadedAt >= expectLoadAfter) return@Runnable
            if (expectLoadAfter == null && lastProbe?.get() == true) return@Runnable
            if (CocosManager.isPaused()) {
                strikes = 0
                lastProbe = null
            } else if (lastProbe != null || CocosManager.getGlView() == null) {
                // a probed round that failed, or no view to probe at all
                strikes += 1
            }
            if (strikes >= 2) {
                Log.e(TAG, "watchdog($reason): renderer unresponsive for 2 rounds (${2 * WATCHDOG_MS}ms)")
                showRendererDeadDialog(
                    reason,
                    if (expectLoadAfter != null)
                        "The watchface did not finish loading within ${2 * WATCHDOG_MS / 1000}s. " +
                        "The screen would stay black or frozen."
                    else
                        "The rendering thread has not responded for ${2 * WATCHDOG_MS / 1000}s. " +
                        "The watchface is hung or the engine is dead."
                )
                return@Runnable
            }
            val gl = CocosManager.getGlView() as? GLSurfaceView
            lastProbe = if (gl != null) {
                java.util.concurrent.atomic.AtomicBoolean(false).also { p ->
                    gl.queueEvent { p.set(true) }
                }
            } else null
            mainHandler.postDelayed(round, WATCHDOG_MS)
        }
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = round
        round.run()
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
        // Enforce the single-live-manager invariant the rest of this class
        // assumes. Activity recreation with the reused GL view (returning home,
        // config changes, AOD wake) spins up a NEW manager while the OLD one may
        // still be alive — the "create-before-destroy interleave" noted below.
        // If the old instance's stop() is ever skipped or delayed, its coroutine
        // scope is never cancelled and its 5s storage/alarm poll loop (plus every
        // listener/receiver/observer/sensor it registered) keeps running forever.
        // Orphaned managers accumulate across the process lifetime; each extra
        // poller fires sendToLua -> callLuaFunction -> lua_pcallk on the GL thread,
        // so after enough recreations the bridge floods and the device heats up on
        // EVERY face. Proactively tear down any predecessor here, and make a
        // duplicate start() on this same instance a no-op.
        if (started) return
        started = true
        active?.let { if (it !== this) it.stop() }
        active = this

        // Push the full snapshot only once Lua signals it is ready (wf_lua_ready,
        // fired at the end of the scene's onEnter). Pushing eagerly here would
        // queue WatchfaceBridgeDispatch onto the GL thread before the dispatcher /
        // bridge handlers exist — at best dropped, at worst a GL-unsafe callback.
        // Keep identity refs: during fragment recreation the OLD instance's
        // stop() can run AFTER this start() (create-before-destroy interleave)
        // and must not clobber the hooks we just registered — that silently
        // dropped every wf_action (sensor enables etc.) until a face reload.
        myReadyHandler = { pushAllLiveData() }
        LuaBridge.luaReadyHandler = myReadyHandler
        registerWatchfaceActionHandler()
        LuaBridge.addEventListner(luaEventListener)
        // If this manager is attaching to an already-running engine (activity
        // recreation with the GL view reused), Lua must re-announce its
        // feature toggles — this manager's sensor/health/weather state is
        // blank while Lua's differ remembers them as enabled, so sensors like
        // the compass would silently stay off until a face reload. On a fresh
        // engine the global doesn't exist yet: harmless, the normal
        // wf_lua_ready flow covers that path.
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnBridgeRestart", "") }
        // Startup uses liveness mode (expectLoadAfter = null), not a wf_loaded
        // demand: on a reused engine the face is already on screen and no
        // wf_loaded will ever arrive.
        startRendererWatch("startup", null)
        WeatherRepository.initialize(ctx)   // load cache so Lua gets data before first network fetch
        // Resolve elevation once at startup when auto-location is on and none is
        // stored yet — the weather cycle only re-resolves it when the position
        // moves, and never runs at all with weather disabled. The pref write
        // lands in locationKeys, so the location snapshot re-pushes itself.
        scope.launch {
            if (WeatherPrefs.autoUpdateLocation(ctx) && !WeatherPrefs.hasElevation(ctx)) {
                val lat = WeatherPrefs.lat(ctx)
                val lon = WeatherPrefs.lon(ctx)
                if (lat != 0.0 || lon != 0.0) {
                    ElevationApi.fetch(lat, lon)?.let { elev ->
                        WeatherPrefs.saveElevation(ctx, elev)
                        Log.d(TAG, "Startup elevation resolved: ${elev}m")
                    }
                }
            }
        }
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
        started = false
        if (active === this) active = null
        // Only clear the global hooks if they are still OURS — a stale stop()
        // from a destroyed-after-recreation instance must not disconnect the
        // live manager.
        if (LuaBridge.luaReadyHandler === myReadyHandler) LuaBridge.luaReadyHandler = null
        if (LuaBridge.watchfaceActionHandler === myActionHandler) LuaBridge.watchfaceActionHandler = null
        LuaBridge.removeEventListener(luaEventListener)
        watchGen += 1                       // invalidate any in-flight watch
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

    private var myReadyHandler: (() -> Unit)? = null
    private var myActionHandler: ((String) -> Unit)? = null

    private fun registerWatchfaceActionHandler() {
        myActionHandler = { payload -> handleWatchfaceAction(payload) }
        LuaBridge.watchfaceActionHandler = myActionHandler
    }

    // ── Java → Lua: watchface lifecycle commands ──────────────────────────────
    // Path switching is NOT here: a watchface_path pref change pushes a full
    // settings snapshot (prefsListener → sendToLua "settings"), and the Lua
    // scene reloads from data.watchfacePath. These commands cover only what the
    // settings push can't — forcing a re-extract / clearing the extract cache
    // for the SAME path.

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

    /**
     * Clear the current face's persisted script variables and reload it (fresh
     * state), showing the loading placeholder. Triggered from the config dialog's
     * Information tab via the [KEY_CLEAR_VARS] pref nonce.
     */
    fun clearWatchfaceVars() {
        expectFaceLoad("clear watchface vars")
        queueOnGL { LuaBridge.callLuaFunction("WatchfaceOnClearVars", "") }
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
                        when (val result = TapActionHandler.handle(ctx, shortcut)) {
                            is TapActionHandler.Result.NotConfigured ->
                                if (!com.ailife.clox.debug.DebugPrefs.ignoreLuaErrors(ctx)) showTapNotConfiguredDialog(result.def)
                            is TapActionHandler.Result.LaunchFailed  -> showTapLaunchFailedDialog(result.appLabel)
                            TapActionHandler.Result.Handled          -> { /* nothing to surface */ }
                        }
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
                ) { sensor, data ->
                    // In the AOD dim state a kick per sensor event would drive
                    // the face at the sensor rate (16-33 Hz) all night despite
                    // the 1 FPS AOD interval; queue the data un-kicked and let
                    // the 1 FPS frame consume it.
                    sendToLua("sensor_$sensor", data,
                              kick = !com.ailife.clox.cocos.RenderPolicy.aodDimmed)
                }
                "reportLayerCount" -> {
                    com.ailife.clox.watchface.WatchfaceStats.setLayerCount(
                        json.optInt("count", -1),
                        PreferenceManager.getDefaultSharedPreferences(ctx).getString("watchface_path", null)
                    )
                    com.ailife.clox.watchface.WatchfaceStats.setMerged(
                        json.optInt("merged", -1), json.optInt("runs", 0)
                    )
                }
                "reportStorage" -> com.ailife.clox.watchface.WatchfaceStats.setStorageBytes(
                    json.optInt("bytes", 0)
                )
                "requestMapTile" -> {
                    val lat = json.optDouble("lat", 0.0)
                    val lon = json.optDouble("lon", 0.0)
                    val zoom = json.optInt("zoom", 15).coerceIn(1, 19)
                    scope.launch {
                        MapTileHelper.fetch(ctx, lat, lon, zoom)?.let { sendToLua("map", it) }
                    }
                }
                "openConfig" -> Handler(Looper.getMainLooper()).post {
                    com.ailife.clox.watchface.config.WatchfaceConfigActivity.start(ctx)
                }
                "luaError" -> {
                    // Shown in all builds by default: a broken watchface should fail loudly
                    // rather than silently rendering black/partial. The Debug settings
                    // "Ignore Lua errors" toggle opts back into silent failure.
                    if (!com.ailife.clox.debug.DebugPrefs.ignoreLuaErrors(ctx)) {
                        val context = json.optString("context", "Lua error")
                        val trace   = json.optString("trace")
                        Handler(Looper.getMainLooper()).post { showLuaErrorDialog(context, trace) }
                    }
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

    /**
     * A tapped shortcut maps to a recognised action that has no app assigned yet.
     * Offer a shortcut to the Tap Actions editor so the user can pick one now.
     */
    private fun showTapNotConfiguredDialog(def: TapActionHandler.ActionDef) {
        try {
            val name = ctx.getString(def.displayNameRes)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(com.ailife.clox.R.string.tap_action_not_configured_title)
                .setMessage(ctx.getString(com.ailife.clox.R.string.tap_action_not_configured_msg, name))
                .setPositiveButton(com.ailife.clox.R.string.tap_action_configure_now) { _, _ ->
                    try {
                        ctx.startActivity(com.ailife.clox.settings.SettingsActivity.tapActionsIntent(ctx))
                    } catch (e: Exception) {
                        Log.e(TAG, "open tap actions settings failed", e)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showTapNotConfiguredDialog failed", e)
        }
    }

    /**
     * A tapped shortcut's target activity couldn't be launched (app uninstalled/
     * disabled, or no handler). Surface it instead of failing silently.
     */
    private fun showTapLaunchFailedDialog(appLabel: String?) {
        try {
            val message = if (appLabel.isNullOrBlank())
                ctx.getString(com.ailife.clox.R.string.tap_action_launch_failed_msg_generic)
            else
                ctx.getString(com.ailife.clox.R.string.tap_action_launch_failed_msg, appLabel)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(com.ailife.clox.R.string.tap_action_launch_failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showTapLaunchFailedDialog failed", e)
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

    private fun sendToLua(category: String, data: JSONObject, kick: Boolean = true) {
        val payload = JSONObject().apply {
            put("category", category)
            put("data", data)
        }.toString()
        queueOnGL(kick) { LuaBridge.callLuaFunction("WatchfaceBridgeDispatch", payload) }
    }

    // All Lua calls must run on the Axmol GL thread.
    private fun queueOnGL(kick: Boolean = true, block: () -> Unit) {
        val glView = CocosManager.getGlView() as? GLSurfaceView
        if (glView != null) {
            glView.queueEvent(block)
            // wake the render thread out of its FPS-throttle wait so dispatches
            // (face switch, settings push) don't stall a frame interval — up to
            // 1s at the static-face cadence. kick=false lets high-rate data
            // (AOD sensor streams) queue without forcing a frame per event.
            if (kick) dev.axmol.lib.AxmolRenderer.kickFrame()
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

        // The one manager whose scope/listeners/pollers are currently live. A new
        // start() evicts whatever was here; a matching stop() clears it. There must
        // only ever be one — duplicates leak pollers that flood the GL thread.
        @Volatile private var active: WatchfaceBridgeManager? = null

        // Generous: a cold start needs GL init + asset extraction + face build.
        private const val WATCHDOG_MS = 15_000L

        // Pref nonce the config dialog writes to request clearing the active
        // face's persisted variables + reload (see prefsListener/clearWatchfaceVars).
        const val KEY_CLEAR_VARS = "wf_clear_vars_nonce"

        // Pref nonce the config dialog writes to force-reload the current face
        // (re-setting the same watchface_path is a no-op — the engine skips it).
        const val KEY_RELOAD = "wf_reload_nonce"
    }
}
