package com.yzmm.clox.qs.privilege

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.yzmm.clox.BuildConfig
import com.yzmm.clox.qs.IQsUserService
import com.yzmm.clox.qs.QsUserService
import rikka.shizuku.Shizuku

object ShizukuOperations {

    private const val TAG = "ShizukuOperations"

    @Volatile private var service: IQsUserService? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, QsUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("qs_user_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IQsUserService.Stub.asInterface(binder)
                Log.i(TAG, "QsUserService connected")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "QsUserService disconnected")
        }
    }

    fun bind() {
        if (!isAvailable()) return
        try {
            Shizuku.bindUserService(serviceArgs, serviceConn)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
        }
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(serviceArgs, serviceConn, true)
        } catch (_: Exception) {}
        service = null
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun isConnected() = service != null

    // ── Shell helpers ──────────────────────────────────────────────────────────

    /** Ensure service is bound; blocks up to [timeoutMs] ms waiting for connection. */
    private fun ensureConnected(timeoutMs: Long = 3000): Boolean {
        if (isConnected()) return true
        if (!isAvailable()) return false
        bind()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return isConnected()
    }

    /** Run command via the UserService; auto-binds if not yet connected. Call on IO thread. */
    private fun exec(command: String): Boolean {
        if (!ensureConnected()) return false
        return try {
            service?.execShell(command) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${e.message}")
            service = null  // service died; clear so next call rebinds
            false
        }
    }

    // ── Public operations — same shell commands as rtosify UserService ─────────

    fun setWifiEnabled(enabled: Boolean) =
        exec("svc wifi ${if (enabled) "enable" else "disable"}")

    fun setBluetoothEnabled(enabled: Boolean): Boolean {
        return exec("svc bluetooth ${if (enabled) "enable" else "disable"}") ||
               exec("cmd bluetooth_manager ${if (enabled) "enable" else "disable"}")
    }

    fun setMobileDataEnabled(enabled: Boolean) =
        exec("svc data ${if (enabled) "enable" else "disable"}")

    fun setAirplaneModeEnabled(enabled: Boolean): Boolean {
        val flag = if (enabled) "1" else "0"
        val ok = exec("settings put global airplane_mode_on $flag")
        if (ok) exec("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled")
        return ok
    }

    fun setLocationEnabled(enabled: Boolean): Boolean =
        exec("settings put secure location_mode ${if (enabled) "3" else "0"}")

    fun setHotspotEnabled(enabled: Boolean) =
        exec("svc wifi hotspot ${if (enabled) "enable" else "disable"}")

    fun writeSettings(namespace: String, key: String, value: String) =
        exec("settings put $namespace $key $value")

    /**
     * Clears Android 13+'s "restricted settings" lock for [pkg] by allowing the
     * ACCESS_RESTRICTED_SETTINGS app-op, so its Accessibility / Notification-listener toggles become
     * grantable on a sideloaded build. Falls back to the numeric op code if the name isn't recognised.
     */
    fun clearRestrictedSettings(pkg: String): Boolean =
        exec("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") ||
        exec("appops set $pkg 119 allow")
}
