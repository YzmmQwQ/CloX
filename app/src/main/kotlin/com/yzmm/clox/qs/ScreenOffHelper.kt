package com.yzmm.clox.qs

import android.content.Context
import android.os.Build
import com.yzmm.clox.admin.DeviceAdminHelper

/**
 * GLOBAL_ACTION_LOCK_SCREEN (used by [CloXAccessibilityService]) only exists from API 28
 * onward, so devices below that fall back to Device Admin's lockNow().
 */
object ScreenOffHelper {

    fun lock(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) CloXAccessibilityService.lockScreen()
        else DeviceAdminHelper.lockScreen(ctx)

    fun requestPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) QsPermissionHelper.ensureAccessibilityEnabled(ctx)
        else DeviceAdminHelper.requestActivation(ctx)
    }
}
