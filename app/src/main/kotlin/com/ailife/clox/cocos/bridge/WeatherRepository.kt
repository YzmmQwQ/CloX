package com.ailife.clox.cocos.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * Single source of truth for weather data.
 *
 * - Persists last successful fetch to SharedPreferences so it survives restarts
 * - Emits to [weatherFlow] on every update; replay=1 so new collectors get the latest immediately
 * - Broadcasts [ACTION_WEATHER_UPDATED] so app widgets can refresh
 * - [getLatestJson] allows future in-app widgets to read without collecting a flow
 */
object WeatherRepository {

    private const val TAG          = "WeatherRepository"
    private const val PREF_CACHE   = "weather_cache_json"
    private const val PREF_FILE    = "weather_cache"
    const val ACTION_WEATHER_UPDATED = "com.ailife.clox.WEATHER_UPDATED"

    // replay=1: new subscribers instantly get the most recent emission
    val weatherFlow = MutableSharedFlow<JSONObject>(replay = 1, extraBufferCapacity = 1)

    /** Returns the latest cached JSONObject, or null if never fetched. */
    fun getLatestJson(): JSONObject? = weatherFlow.replayCache.firstOrNull()

    /**
     * Load the persisted cache and emit it to the flow so Lua gets data immediately on boot
     * without waiting for the first network fetch. Call once from WatchfaceBridgeManager.start().
     */
    fun initialize(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val cached = prefs.getString(PREF_CACHE, null) ?: return
        try {
            weatherFlow.tryEmit(JSONObject(cached))
        } catch (_: Exception) {}
    }

    /**
     * Fetch fresh weather data for the given coordinates.
     * - If [autoUpdateLocation] is true in prefs, resolves lat/lon from IP first and saves them.
     * - Selects the provider from [WeatherPrefs.provider].
     * - On success: updates the flow, persists to SharedPreferences, broadcasts update.
     * - On failure: logs the error; the cached data remains in the flow.
     *
     * Must be called from a coroutine (suspend).
     */
    suspend fun refresh(ctx: Context) {
        Log.d(TAG, "refresh() called, enabled=${WeatherPrefs.enabled(ctx)}")
        if (!WeatherPrefs.enabled(ctx)) return

        // Optionally refresh the primary coordinates via IP geolocation
        var lat = WeatherPrefs.lat(ctx)
        var lon = WeatherPrefs.lon(ctx)
        Log.d(TAG, "Location before auto-update: lat=$lat lon=$lon autoUpdate=${WeatherPrefs.autoUpdateLocation(ctx)}")
        if (WeatherPrefs.autoUpdateLocation(ctx)) {
            val loc = LocationByIp.fetch()
            if (loc != null) {
                lat = loc.first; lon = loc.second
                WeatherPrefs.savePrimaryLocation(ctx, lat, lon, loc.third.ifEmpty { WeatherPrefs.city(ctx) })
                Log.d(TAG, "Auto-location updated: $lat, $lon (${loc.third})")
            } else {
                Log.w(TAG, "IP geolocation returned null, using stored coords lat=$lat lon=$lon")
            }
        }

        if (lat == 0.0 && lon == 0.0) {
            Log.w(TAG, "No location set — skipping weather fetch")
            return
        }

        try {
            val provider = WeatherProvider.forKey(WeatherPrefs.provider(ctx))
            val result   = provider.fetch(ctx, lat, lon)
            val json     = result.toJson(WeatherPrefs.roundTemp(ctx))

            weatherFlow.emit(json)
            persist(ctx, json)
            broadcast(ctx)
            Log.d(TAG, "Weather refreshed via ${WeatherPrefs.provider(ctx)}")
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
        }
    }

    private fun persist(ctx: Context, json: JSONObject) {
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_CACHE, json.toString()).apply()
    }

    private fun broadcast(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_WEATHER_UPDATED).setPackage(ctx.packageName))
    }
}
