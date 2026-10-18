package com.yzmm.clox.qs.privilege

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yzmm.clox.R
import com.yzmm.clox.util.safeStartActivity
import rikka.shizuku.Shizuku

/**
 * On Android 13+ a sideloaded (non-system) app hits the "Restricted setting" block when the user
 * tries to enable its Accessibility service or Notification listener — the toggle is greyed out and
 * shows a dialog telling the user to allow restricted settings. The programmatic way to lift that
 * lock is to allow the ACCESS_RESTRICTED_SETTINGS app-op for the package, which needs a privileged
 * UID (shell via Shizuku, or root).
 *
 * This helper clears that lock (when possible) and then opens the settings screen, so by the time
 * the user arrives the toggle is actually flippable. On a system-app install (or pre-13) it's a
 * no-op and just opens the screen.
 *
 * Handling the two things the low-level [ShizukuOperations.exec] does NOT do on its own:
 *  - **binding the user service** — `exec` already binds and waits up to 3s, so `clearNow` must run
 *    off the main thread (it does).
 *  - **requesting Shizuku permission** — `exec` never prompts; it just fails when the permission
 *    isn't granted (and [PrivilegeManager.level] only reports SHIZUKU once it IS granted). So when
 *    Shizuku is installed but not yet authorised we request the permission first, then clear on grant.
 */
object RestrictedSettingsHelper {

    private const val TAG = "RestrictedSettings"
    private const val SHIZUKU_REQ_CODE = 5723

    /** True when this build is subject to the 13+ restricted-settings block (sideloaded, not system). */
    fun mayBeBlocked(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (ctx.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    /** Allow the restricted-settings app-op via Shizuku/root. Blocking — call off the main thread. */
    fun clearNow(ctx: Context, level: PrivilegeLevel = PrivilegeManager.level(ctx)): Boolean {
        val pkg = ctx.packageName
        val ok = when (level) {
            PrivilegeLevel.SHIZUKU -> ShizukuOperations.clearRestrictedSettings(pkg)
            PrivilegeLevel.ROOT    -> RootOperations.clearRestrictedSettings(pkg)
            PrivilegeLevel.NONE    -> false
        }
        Log.i(TAG, "clearRestrictedSettings via $level -> $ok")
        return ok
    }

    /**
     * Clears the restricted-settings lock (when applicable) and then opens [intent] so the user can
     * flip the toggle. If a privilege is available it's used directly; if only Shizuku is installed
     * but not yet authorised, its permission is requested first and the clear runs on grant. When
     * there's nothing we can do (system app, pre-13, or no privilege at all) it just opens the screen.
     */
    fun openGrantScreen(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!mayBeBlocked(ctx)) { ctx.safeStartActivity(intent); return }

        val level = PrivilegeManager.level(ctx)
        when {
            level != PrivilegeLevel.NONE -> clearThenOpen(ctx, intent, level)
            // Shizuku running but not authorised yet — prompt, then clear on grant.
            PrivilegeManager.isShizukuInstalled() -> requestShizukuThenClear(ctx, intent)
            else -> ctx.safeStartActivity(intent)
        }
    }

    private fun requestShizukuThenClear(ctx: Context, intent: Intent) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_REQ_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                PrivilegeManager.invalidate()
                if (grantResult == PackageManager.PERMISSION_GRANTED)
                    clearThenOpen(ctx, intent, PrivilegeLevel.SHIZUKU)
                else
                    ctx.safeStartActivity(intent)  // denied — open anyway, user can unlock manually
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku requestPermission failed: ${e.message}")
            Shizuku.removeRequestPermissionResultListener(listener)
            ctx.safeStartActivity(intent)
        }
    }

    private fun clearThenOpen(ctx: Context, intent: Intent, level: PrivilegeLevel) {
        val appCtx = ctx.applicationContext
        Thread {
            val ok = clearNow(appCtx, level)
            Handler(Looper.getMainLooper()).post {
                if (ok) Toast.makeText(appCtx, R.string.restricted_settings_unlocked, Toast.LENGTH_LONG).show()
                appCtx.safeStartActivity(intent)
            }
        }.start()
    }
}
