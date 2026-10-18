package com.yzmm.clox.cocos.bridge

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.yzmm.clox.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceInfoHelper {

    fun getInfo(ctx: Context): JSONObject {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            ctx.resources.configuration.locale
        }
        // WM splits locale into {alangcode}="en" / {alangreg}="US" / {alangfull}="English"
        val langFull = locale.getDisplayLanguage(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

        val deviceLabel = Settings.Global.getString(ctx.contentResolver, "device_name")
            ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")
            ?: Build.MODEL

        val isRound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ctx.resources.configuration.isScreenRound
        } else false

        val bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val lastReboot = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(bootTimeMs))

        return JSONObject().apply {
            put("apiLevel",    Build.VERSION.SDK_INT)
            put("osVersion",   Build.VERSION.RELEASE)   // {aosv}: "15", not the SDK int
            put("language",    locale.language)
            put("langRegion",  locale.country)
            put("langFull",    langFull)
            put("deviceLabel", deviceLabel)
            put("deviceModel", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("productName", "CloX Launcher")
            put("osType",      "Full Android")
            put("appVersion",  BuildConfig.VERSION_NAME)
            put("isRound",     isRound)
            put("lastReboot",  lastReboot)
        }
    }
}
