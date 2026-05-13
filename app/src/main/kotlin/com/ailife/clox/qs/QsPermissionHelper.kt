package com.ailife.clox.qs

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.fragment.app.FragmentActivity

object QsPermissionHelper {

    const val REQUEST_CODE = 101

    // All runtime permissions QS needs — checked on every panel open
    private fun requiredPermissions() = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        // CAMERA not needed — CameraManager.setTorchMode() only requires FLASHLIGHT permission
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("android.permission.NEARBY_WIFI_DEVICES")
        }
    }

    fun missingPermissions(ctx: Context): List<String> =
        requiredPermissions().filter {
            ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasMissingPermissions(ctx: Context) = missingPermissions(ctx).isNotEmpty()

    /** Request all missing runtime permissions. Call on every QS panel open. */
    fun requestMissing(activity: FragmentActivity) {
        val missing = missingPermissions(activity)
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE)
        }
    }

    /** Call from onRequestPermissionsResult — returns true if any new permission was granted. */
    fun onPermissionsResult(
        grantResults: IntArray
    ): Boolean = grantResults.any { it == PackageManager.PERMISSION_GRANTED }

    fun ensureWriteSettings(ctx: Context): Boolean {
        if (Settings.System.canWrite(ctx)) return true
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    fun ensureDndAccess(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) return true
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    fun ensureAccessibilityEnabled(ctx: Context): Boolean {
        if (CloXAccessibilityService.isRunning()) return true
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
}
