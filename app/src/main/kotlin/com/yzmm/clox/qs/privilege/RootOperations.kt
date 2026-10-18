package com.yzmm.clox.qs.privilege

import android.content.Context
import com.topjohnwu.superuser.Shell

object RootOperations {

    fun setWifiEnabled(enabled: Boolean): Boolean =
        Shell.cmd("svc wifi ${if (enabled) "enable" else "disable"}").exec().isSuccess

    fun setBluetoothEnabled(enabled: Boolean): Boolean =
        Shell.cmd("svc bluetooth ${if (enabled) "enable" else "disable"}").exec().isSuccess

    fun setMobileDataEnabled(enabled: Boolean): Boolean =
        Shell.cmd("svc data ${if (enabled) "enable" else "disable"}").exec().isSuccess

    fun setAirplaneModeEnabled(ctx: Context, enabled: Boolean): Boolean {
        val flag = if (enabled) "1" else "0"
        val ok = Shell.cmd("settings put global airplane_mode_on $flag").exec().isSuccess
        if (ok) Shell.cmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled").exec()
        return ok
    }

    fun setLocationEnabled(ctx: Context, enabled: Boolean): Boolean =
        Shell.cmd("settings put secure location_mode ${if (enabled) "3" else "0"}").exec().isSuccess

    fun setHotspotEnabled(enabled: Boolean): Boolean =
        Shell.cmd("svc wifi hotspot ${if (enabled) "enable" else "disable"}").exec().isSuccess

    fun writeSettings(namespace: String, key: String, value: String): Boolean =
        Shell.cmd("settings put $namespace $key $value").exec().isSuccess

    /** Official shell path for notification-listener access (API 30+). */
    fun allowNotificationListener(component: String): Boolean =
        Shell.cmd("cmd notification allow_listener $component").exec().isSuccess

    /** One-time grant so the app can write secure settings itself from then on. */
    fun grantWriteSecureSettings(pkg: String): Boolean =
        Shell.cmd("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS").exec().isSuccess

    /** Clears Android 13+'s "restricted settings" lock for [pkg] (see ShizukuOperations equivalent). */
    fun clearRestrictedSettings(pkg: String): Boolean =
        Shell.cmd("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow").exec().isSuccess ||
        Shell.cmd("appops set $pkg 119 allow").exec().isSuccess
}
