package com.yzmm.clox.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.yzmm.clox.admin.DeviceAdminHelper
import com.yzmm.clox.util.safeStartActivity

/**
 * A real OS-level "force stop" (ActivityManager.forceStopPackage) needs the
 * signature-level FORCE_STOP_PACKAGES permission, which no third-party app —
 * device admin or not — can hold. Killing our own process is the closest
 * practical equivalent; Android restarts the process shortly after since this
 * app is the default home app.
 */
object LauncherMaintenance {

    fun forceStopAndRelaunch() {
        Process.killProcess(Process.myPid())
    }

    /**
     * An active device admin blocks the OS uninstall flow, so admin is deactivated
     * first. Removal takes effect immediately for self-removal by a non-owner
     * admin, but a short delay before firing the uninstall intent avoids racing
     * against it on slower devices.
     */
    fun uninstall(ctx: Context) {
        DeviceAdminHelper.removeActiveAdmin(ctx)
        Handler(Looper.getMainLooper()).postDelayed({
            ctx.safeStartActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }, 300)
    }
}
