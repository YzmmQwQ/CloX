package com.ailife.clox.qs

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager

object QsPermissionHelper {

    const val REQUEST_CODE = 101

    // SharedPrefs key prefix — records every permission we have ever requested
    private const val PREF_REQUESTED_PREFIX = "qs_perm_requested_"

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
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

    /**
     * Request all missing runtime permissions.
     *
     * If a permission has been permanently denied (the user tapped "Don't ask again"),
     * Android silently returns DENIED without showing a dialog — which causes an infinite
     * onResume → requestMissing → onResume loop. We detect this case by tracking which
     * permissions we have previously requested; if shouldShowRequestPermissionRationale
     * returns false AND we already requested it before, it's permanently denied.
     * In that case we show a dialog directing the user to App Settings instead.
     */
    fun requestMissing(activity: FragmentActivity) {
        val missing = missingPermissions(activity)
        if (missing.isEmpty()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

        val permanentlyDenied = missing.filter { perm ->
            val wasRequested = prefs.getBoolean(PREF_REQUESTED_PREFIX + perm, false)
            wasRequested && !activity.shouldShowRequestPermissionRationale(perm)
        }

        val requestable = missing - permanentlyDenied.toSet()

        if (permanentlyDenied.isNotEmpty()) {
            showSettingsDialog(activity)
        } else if (requestable.isNotEmpty()) {
            // Mark as requested before launching the dialog
            prefs.edit().apply {
                requestable.forEach { putBoolean(PREF_REQUESTED_PREFIX + it, true) }
            }.apply()
            activity.requestPermissions(requestable.toTypedArray(), REQUEST_CODE)
        }
    }

    private fun showSettingsDialog(activity: FragmentActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage(
                "Some permissions were permanently denied. " +
                "Please open App Settings and grant them under Permissions."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                )
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    /** Call from onRequestPermissionsResult — returns true if any new permission was granted. */
    fun onPermissionsResult(grantResults: IntArray): Boolean =
        grantResults.any { it == PackageManager.PERMISSION_GRANTED }

    fun ensureWriteSettings(ctx: Context): Boolean {
        if (Settings.System.canWrite(ctx)) return true
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
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
