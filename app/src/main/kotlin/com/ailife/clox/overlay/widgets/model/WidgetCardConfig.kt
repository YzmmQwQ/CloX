package com.ailife.clox.overlay.widgets.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class WidgetCardConfig {
    abstract val id: String

    data class FullWidth(
        override val id: String = UUID.randomUUID().toString(),
        val appWidgetId: Int,
        val heightUnits: Int = 2
    ) : WidgetCardConfig()

    data class SingleCellRow(
        override val id: String = UUID.randomUUID().toString(),
        val slots: List<CellSlotConfig?>   // always 3 elements, null = empty
    ) : WidgetCardConfig()

    data class BuiltInClock(
        override val id: String = UUID.randomUUID().toString()
    ) : WidgetCardConfig()

    data class BuiltInWeather(
        override val id: String = UUID.randomUUID().toString()
    ) : WidgetCardConfig()

    fun toJson(): JSONObject = when (this) {
        is BuiltInClock -> JSONObject().apply {
            put("type", "builtin_clock"); put("id", id)
        }
        is BuiltInWeather -> JSONObject().apply {
            put("type", "builtin_weather"); put("id", id)
        }
        is FullWidth -> JSONObject().apply {
            put("type", "full_width")
            put("id", id)
            put("appWidgetId", appWidgetId)
            put("heightUnits", heightUnits)
        }
        is SingleCellRow -> JSONObject().apply {
            put("type", "single_cell")
            put("id", id)
            val arr = JSONArray()
            slots.forEach { slot -> arr.put(slot?.toJson() ?: JSONObject.NULL) }
            put("slots", arr)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): WidgetCardConfig? = when (obj.optString("type")) {
            "builtin_clock"   -> BuiltInClock(id = obj.getString("id"))
            "builtin_weather" -> BuiltInWeather(id = obj.getString("id"))
            "full_width" -> FullWidth(
                id = obj.getString("id"),
                appWidgetId = obj.getInt("appWidgetId"),
                heightUnits = obj.optInt("heightUnits", 2).coerceIn(1, 4)
            )
            "single_cell" -> {
                val arr = obj.getJSONArray("slots")
                val slots = (0 until arr.length()).map { i ->
                    if (arr.isNull(i)) null else CellSlotConfig.fromJson(arr.getJSONObject(i))
                }
                SingleCellRow(id = obj.getString("id"), slots = slots)
            }
            else -> null
        }
    }
}

sealed class CellSlotConfig {
    data class AppWidget(val appWidgetId: Int) : CellSlotConfig()
    data class ActivityShortcut(
        val packageName: String,
        val activityName: String,
        val label: String
    ) : CellSlotConfig()

    fun toJson(): JSONObject = when (this) {
        is AppWidget -> JSONObject().apply {
            put("type", "app_widget")
            put("appWidgetId", appWidgetId)
        }
        is ActivityShortcut -> JSONObject().apply {
            put("type", "activity")
            put("package", packageName)
            put("activity", activityName)
            put("label", label)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): CellSlotConfig? = when (obj.optString("type")) {
            "app_widget" -> AppWidget(obj.getInt("appWidgetId"))
            "activity" -> ActivityShortcut(
                packageName = obj.getString("package"),
                activityName = obj.getString("activity"),
                label = obj.optString("label", "")
            )
            else -> null
        }
    }
}
