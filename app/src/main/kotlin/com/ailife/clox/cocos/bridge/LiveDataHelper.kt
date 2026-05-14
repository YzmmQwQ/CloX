package com.ailife.clox.cocos.bridge

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.preference.PreferenceManager
import com.ailife.clox.qs.TileState
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LiveDataHelper {

    fun batteryJson(ctx: Context, state: TileState.BatteryState): JSONObject {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        // EXTRA_PLUGGED is nonzero for any power source (USB, AC, wireless).
        // This correctly catches "plugged in but full" which BATTERY_STATUS != CHARGING.
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject().apply {
            put("level", state.level)
            put("charging", plugged != 0)
            put("temperature", temperatureTenths / 10.0)
        }
    }

    fun networkJson(ctx: Context, wifi: TileState.WifiState, data: TileState.DataState): JSONObject {
        val wifiIp = if (wifi.connected) getWifiIp(ctx) else ""
        return JSONObject().apply {
            put("wifiEnabled", wifi.enabled)
            put("wifiConnected", wifi.connected)
            put("wifiSsid", wifi.ssid ?: "")
            put("wifiFrequency", wifi.frequency ?: "")
            put("wifiSignal", wifi.signalLevel)
            put("wifiIp", wifiIp)
            put("dataEnabled", data.enabled)
            put("dataConnected", data.activelyRouting)
            put("dataType", data.networkType ?: "")
            put("dataSignal", data.signalLevel)
            put("dataOperator", data.operatorName ?: "")
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIp(ctx: Context): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wm.connectionInfo?.ipAddress ?: 0
        if (ipInt == 0) return ""
        return String.format("%d.%d.%d.%d",
            ipInt and 0xFF, (ipInt shr 8) and 0xFF,
            (ipInt shr 16) and 0xFF, (ipInt shr 24) and 0xFF)
    }

    fun connectivityJson(bt: TileState.BtState, loc: TileState.LocationState): JSONObject =
        JSONObject().apply {
            put("bluetoothEnabled", bt.enabled)
            put("bluetoothDevice", bt.connectedDeviceName ?: "")
            put("gpsEnabled", loc.enabled)
        }

    fun storageJson(ctx: Context): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes
        val usedStorage = totalStorage - stat.availableBytes

        val actMgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)

        return JSONObject().apply {
            put("totalStorage", totalStorage)
            put("usedStorage", usedStorage)
            put("totalRam", memInfo.totalMem)
            put("usedRam", memInfo.totalMem - memInfo.availMem)
        }
    }

    fun alarmJson(ctx: Context): JSONObject {
        val alarmMgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            alarmMgr.nextAlarmClock
        } else null

        val now = System.currentTimeMillis()
        val withinDay = next != null && (next.triggerTime - now) in 0..(24 * 3600 * 1000)
        val alarmTime = if (withinDay) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next!!.triggerTime))
        } else ""

        return JSONObject().apply {
            put("hasAlarm", withinDay)
            put("alarmTime", alarmTime)
        }
    }

    /**
     * Watchface display settings — these keys must match what the settings UI saves.
     * Update the key constants below to match the actual SharedPreferences keys used by the watchface settings screen.
     */
    fun watchfaceSettingsJson(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val path = prefs.getString("watchface_path", null)
        return JSONObject().apply {
            // false (not empty string) so Lua `if WF.settings.watchfacePath then` correctly gates loading
            if (path.isNullOrEmpty()) put("watchfacePath", false) else put("watchfacePath", path)
            put("zoom", prefs.getFloat("watchface_zoom", 1.0f).toDouble())
            put("xOffset", prefs.getInt("watchface_x_offset", 0))
            put("yOffset", prefs.getInt("watchface_y_offset", 0))
            put("fullscreen", prefs.getInt("watchface_fullscreen", 0))
            put("frameRate", prefs.getInt("watchface_frame_rate", 60))
            put("aodEnabled", prefs.getBoolean("watchface_aod_enabled", false))
        }
    }

    /**
     * User-configured location names and timezones.
     * Keys must match what the location/timezone settings screen saves.
     */
    fun locationSettingsJson(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val names = (1..5).map { prefs.getString("location_name_$it", "") ?: "" }
        val zones = (1..4).map { prefs.getString("location_timezone_$it", "") ?: "" }
        return JSONObject().apply {
            put("names", org.json.JSONArray(names))
            put("timezones", org.json.JSONArray(zones))
        }
    }
}
