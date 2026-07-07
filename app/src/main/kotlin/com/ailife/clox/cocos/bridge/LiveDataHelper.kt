package com.ailife.clox.cocos.bridge

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.ailife.clox.qs.TileState
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LiveDataHelper {

    fun batteryJson(ctx: Context, state: TileState.BatteryState): JSONObject {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltageMilliV = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentMicroA = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Exception) { 0L }
        } else 0L

        return JSONObject().apply {
            put("level",       state.level)
            put("charging",    plugged != 0)
            put("temperature", temperatureTenths / 10.0)
            put("voltage",     voltageMilliV / 1000.0)
            put("current",     currentMicroA / 1000.0)
        }
    }

    fun networkJson(ctx: Context, wifi: TileState.WifiState, data: TileState.DataState): JSONObject {
        val wifiIp = if (wifi.connected) getWifiIp(ctx) else ""
        return JSONObject().apply {
            put("wifiEnabled",   wifi.enabled)
            put("wifiConnected", wifi.connected)
            put("wifiSsid",      wifi.ssid ?: "")
            put("wifiFrequency", wifi.frequency ?: "")
            put("wifiSignal",    wifi.signalLevel)
            put("wifiIp",        wifiIp)
            put("dataEnabled",   data.enabled)
            put("dataConnected", data.activelyRouting)
            put("dataType",      data.networkType ?: "")
            put("dataSignal",    data.signalLevel)
            put("dataOperator",  data.operatorName ?: "")
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
            put("bluetoothDevice",  bt.connectedDeviceName ?: "")
            put("gpsEnabled",       loc.enabled)
        }

    fun storageJson(ctx: Context): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes
        val usedStorage  = totalStorage - stat.availableBytes

        val actMgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem
        val usedRam  = totalRam - memInfo.availMem

        return JSONObject().apply {
            put("totalStorage", totalStorage)
            put("usedStorage",  usedStorage)
            put("totalRam",     totalRam)
            put("usedRam",      usedRam)
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
            put("hasAlarm",      withinDay)
            put("alarmTime",     alarmTime)
            put("alarmTimestamp", if (withinDay) next!!.triggerTime else 0L)
        }
    }

    fun watchfaceSettingsJson(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val path = prefs.getString("watchface_path", null)
        // Scale/offset/FPS come from the per-watchface config namespace (keyed by
        // path hash); the default face uses WatchfaceConfigPrefs.DEFAULT_PATH.
        val cfgPath = com.ailife.clox.watchface.WatchfaceConfigPrefs.pathFor(path)
        val cfg = com.ailife.clox.watchface.WatchfaceConfigPrefs
        return JSONObject().apply {
            if (path.isNullOrEmpty()) put("watchfacePath", false) else put("watchfacePath", path)
            put("zoom",            cfg.scale(ctx, cfgPath).toDouble())
            put("xOffset",         cfg.xOffset(ctx, cfgPath))
            put("yOffset",         cfg.yOffset(ctx, cfgPath))
            put("fullscreen",      prefs.getInt("watchface_fullscreen", 0))
            put("maxFps",          cfg.maxFps(ctx, cfgPath))
            put("maxFpsUnlimited", cfg.unlimited(ctx, cfgPath))
            put("aodEnabled",      prefs.getBoolean("watchface_aod_enabled", false))
        }
    }

    fun locationSettingsJson(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val names = (1..5).map { prefs.getString("location_name_$it", "") ?: "" }
        val zones = (1..4).map { prefs.getString("location_timezone_$it", "") ?: "" }
        return JSONObject().apply {
            put("names",     org.json.JSONArray(names))
            put("timezones", org.json.JSONArray(zones))
        }
    }

    /** Mutable system state: volume, brightness, dark mode, power saver. */
    fun systemStateJson(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPercent = if (maxVol > 0) curVol * 100 / maxVol else 0

        val brightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Exception) { 128 }

        val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLowPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pm.isPowerSaveMode
        } else false

        return JSONObject().apply {
            put("volume",       volPercent)
            put("brightness",   brightness)
            put("darkMode",     isDarkMode)
            put("lowPowerMode", isLowPower)
        }
    }

    /** Computed current-time data for 3 timezone slots:
     *  tz1 = device local timezone, tz2/tz3 = user-configured (location_timezone_1/2). */
    fun timezonesJson(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val tz2Id = prefs.getString("location_timezone_1", "") ?: ""
        val tz3Id = prefs.getString("location_timezone_2", "") ?: ""
        return JSONObject().apply {
            put("tz1", timezoneInfo(TimeZone.getDefault()))
            put("tz2", if (tz2Id.isNotEmpty()) timezoneInfo(safeTimeZone(tz2Id) ?: TimeZone.getDefault()) else emptyTimezone())
            put("tz3", if (tz3Id.isNotEmpty()) timezoneInfo(safeTimeZone(tz3Id) ?: TimeZone.getDefault()) else emptyTimezone())
        }
    }

    private fun safeTimeZone(id: String): TimeZone? {
        val tz = TimeZone.getTimeZone(id)
        return if (tz.id == "GMT" && id != "GMT" && id != "UTC") null else tz
    }

    private fun timezoneInfo(tz: TimeZone): JSONObject {
        val now = System.currentTimeMillis()
        val offsetMs  = tz.getOffset(now)
        val offsetMin = offsetMs / 60_000
        val offsetHrs = offsetMin / 60
        val offsetMinsRem = Math.abs(offsetMin % 60)
        // WM formats: {tzNo} without a leading zero ("+8:00", "-7:00"), and
        // {tzNl} is the last segment of the zone ID ("US/Pacific" → "Pacific")
        val offsetStr = "%+d:%02d".format(offsetHrs, offsetMinsRem)
        val shortName = tz.id.substringAfterLast('/').replace('_', ' ')
        return JSONObject().apply {
            put("locationShort",    shortName)
            put("locationLong",     tz.id)
            put("utcOffset",        offsetStr)
            put("utcOffsetMinutes", offsetMin)
            put("dstActive",        tz.inDaylightTime(Date(now)))
        }
    }

    private fun emptyTimezone() = JSONObject().apply {
        put("locationShort", ""); put("locationLong", "")
        put("utcOffset", ""); put("utcOffsetMinutes", 0)
        put("dstActive", false)
    }
}
