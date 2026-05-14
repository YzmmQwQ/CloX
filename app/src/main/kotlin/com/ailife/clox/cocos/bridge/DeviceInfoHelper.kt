package com.ailife.clox.cocos.bridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ailife.clox.BuildConfig
import org.json.JSONObject

object DeviceInfoHelper {

    fun getInfo(ctx: Context): JSONObject {
        val lang = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ctx.resources.configuration.locales[0].language
        } else {
            @Suppress("DEPRECATION")
            ctx.resources.configuration.locale.language
        }
        return JSONObject().apply {
            put("apiLevel", Build.VERSION.SDK_INT)
            put("language", lang)
            // Settings.Global.DEVICE_NAME is the user-visible label set in About Phone
            val deviceLabel = Settings.Global.getString(ctx.contentResolver, "device_name")
                ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")
                ?: Build.MODEL
            put("deviceLabel", deviceLabel)
            put("deviceModel", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("productName", "CloX Launcher")
            put("osType", "Full Android")
            put("appVersion", BuildConfig.VERSION_NAME)
        }
    }
}
