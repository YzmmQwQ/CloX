package com.ailife.clox.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ailife.clox.R
import com.ailife.clox.util.safeStartActivity

object DeviceAdminHelper {

    private fun adminComponent(ctx: Context) = ComponentName(ctx, CloXDeviceAdminReceiver::class.java)

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isActive(ctx: Context): Boolean = dpm(ctx).isAdminActive(adminComponent(ctx))

    // ADD_DEVICE_ADMIN's target activity (DeviceAdminAdd) refuses to launch as a new task, so this
    // must be called with an Activity context — unlike the other permission-request helpers here.
    fun requestActivation(ctx: Context) {
        ctx.safeStartActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.dialog_device_admin_explanation))
        })
    }

    /** Pre-API-28 fallback for screen-off: locks via Device Admin. Returns false if not active. */
    fun lockScreen(ctx: Context): Boolean {
        if (!isActive(ctx)) return false
        dpm(ctx).lockNow()
        return true
    }

    /**
     * Deactivates this app's own device admin. Self-removal by a non-owner active
     * admin needs no confirmation or special permission — used to unblock the OS
     * force-stop/uninstall restrictions that apply while admin is active.
     */
    fun removeActiveAdmin(ctx: Context) {
        if (isActive(ctx)) dpm(ctx).removeActiveAdmin(adminComponent(ctx))
    }
}
