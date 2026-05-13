package com.ailife.clox.qs.tile

import org.json.JSONObject

enum class CustomTileMode { LAUNCH_ACTIVITY, WRITE_SETTINGS }

data class CustomTileConfig(
    val uuid: String,
    val label: String,
    val mode: CustomTileMode,
    val activityComponent: String? = null,
    val settingsNamespace: String? = null,
    val settingsKey: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("label", label)
        put("mode", mode.name)
        activityComponent?.let { put("activityComponent", it) }
        settingsNamespace?.let { put("settingsNamespace", it) }
        settingsKey?.let { put("settingsKey", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject) = CustomTileConfig(
            uuid = obj.getString("uuid"),
            label = obj.getString("label"),
            mode = CustomTileMode.valueOf(obj.getString("mode")),
            activityComponent = obj.optString("activityComponent").ifEmpty { null },
            settingsNamespace = obj.optString("settingsNamespace").ifEmpty { null },
            settingsKey = obj.optString("settingsKey").ifEmpty { null }
        )
    }
}
