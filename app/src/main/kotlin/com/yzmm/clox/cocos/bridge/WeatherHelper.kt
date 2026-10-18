package com.yzmm.clox.cocos.bridge

import org.json.JSONArray
import org.json.JSONObject

// Thin compatibility shim — real weather logic is in WeatherRepository / WeatherProvider.
object WeatherHelper {

    fun getLatestData(): JSONObject = WeatherRepository.getLatestJson() ?: emptyWeather()

    private fun emptyWeather(): JSONObject {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hourly = JSONArray().apply {
            repeat(12) { i ->
                put(JSONObject().apply {
                    put("temp", 0); put("conditionText", ""); put("conditionIcon", 0)
                    put("hourOfDay", (currentHour + i) % 24)
                })
            }
        }
        val daily = JSONArray().apply {
            repeat(6) {
                put(JSONObject().apply {
                    put("avgTemp", 0); put("high", 0); put("low", 0)
                    put("conditionText", ""); put("conditionIcon", 0)
                })
            }
        }
        return JSONObject().apply {
            put("currentTemp", 0); put("highTemp", 0); put("lowTemp", 0)
            put("conditionText", ""); put("conditionIcon", 0)
            put("humidity", 0); put("pressure", 0); put("windSpeed", 0)
            put("windDirection", 0); put("cloudiness", 0); put("rainVolume", 0)
            put("isDaytime", true); put("sunrise", ""); put("sunset", "")
            put("moonPhase", 0.0); put("lastUpdate", 0)
            put("locationName", ""); put("tempUnit", "C")
            put("hourly", hourly); put("daily", daily)
        }
    }
}
