package com.ailife.clox.cocos.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Placeholder for weather data. The actual data source (e.g., OpenWeatherMap API, system weather
 * provider) will be wired in here once the backend is ready.
 *
 * Data contract — what the real implementation must supply:
 *   current:  currentTemp, highTemp, lowTemp (°C), conditionText, conditionIcon (int code),
 *             humidity (%), pressure (hPa), windSpeed (km/h), windDirection (degrees),
 *             cloudiness (%), rainVolume (mm/h), isDaytime (bool),
 *             sunrise / sunset ("HH:mm"), moonPhase (0.0-1.0), lastUpdate (Unix ms)
 *   hourly:   list of 12 × {temp, conditionText, conditionIcon}
 *   daily:    list of 5  × {avgTemp, high, low, conditionText, conditionIcon}
 *
 * Call updateFromSource() with real data from your weather provider and store in _latestData.
 * The update interval is controlled by WatchfaceBridgeManager (async loop).
 */
object WeatherHelper {

    private var enabled = false
    private var _latestData: JSONObject = emptyWeather()

    fun isEnabled() = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Called by WatchfaceBridgeManager when it is time to fetch fresh weather. */
    suspend fun fetchAndUpdate(): JSONObject {
        if (!enabled) return _latestData
        // TODO: call weather data source here, update _latestData
        // Example: _latestData = weatherApiClient.fetch()
        return _latestData
    }

    fun getLatestData(): JSONObject = _latestData

    /**
     * Wire this in from your weather backend once available.
     * data must match the contract described above.
     */
    fun updateFromSource(data: JSONObject) {
        _latestData = data
    }

    private fun emptyWeather(): JSONObject {
        val hourly = JSONArray().apply {
            repeat(12) { put(JSONObject().apply { put("temp", 0); put("conditionText", ""); put("conditionIcon", 0) }) }
        }
        val daily = JSONArray().apply {
            repeat(5) { put(JSONObject().apply { put("avgTemp", 0); put("high", 0); put("low", 0); put("conditionText", ""); put("conditionIcon", 0) }) }
        }
        return JSONObject().apply {
            put("currentTemp", 0)
            put("highTemp", 0)
            put("lowTemp", 0)
            put("conditionText", "")
            put("conditionIcon", 0)
            put("humidity", 0)
            put("pressure", 0)
            put("windSpeed", 0)
            put("windDirection", 0)
            put("cloudiness", 0)
            put("rainVolume", 0)
            put("isDaytime", true)
            put("sunrise", "")
            put("sunset", "")
            put("moonPhase", 0.0)
            put("lastUpdate", 0)
            put("hourly", hourly)
            put("daily", daily)
        }
    }
}
