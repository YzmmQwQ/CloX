package com.yzmm.clox.xposed

import android.content.Context
import android.os.Binder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * CloX is its own LSPosed module. This hook runs inside **system_server** and lets CloX call
 * several hidden `ActivityTaskManagerService` methods that require permissions ungrantable to CloX:
 *  - `getTaskSnapshot()` / `takeTaskSnapshot()` — `READ_FRAME_BUFFER` (`signature|recents`) — to
 *    capture the outgoing app's real pixels for the home-return close morph.
 *  - `getTasks()` / `getRecentTasks()` — filtered to the caller's OWN tasks unless the caller holds
 *    `REAL_GET_TASKS` (`signature|privileged`). CloX needs the FULL task list to resolve the closing
 *    app's task id (by package) so the snapshot morph captures the RIGHT task for every launch source
 *    — not just app-list launches (which are the only ones that set RemoteAppAnimator.lastOpenedTaskId).
 *
 * Approach: instead of trying to fake the permission grant (the check funnels through several
 * layers), we hook these methods and, **only for calls originating from CloX's uid**,
 * `Binder.clearCallingIdentity()` in the before-hook so the method's internal permission enforce /
 * task filtering sees the system uid (1000, which holds the perms), then restore in the after-hook.
 * This is path-independent and version-stable (API 31–35) — it doesn't depend on which internal
 * permission helper the method uses.
 *
 * Scope in LSPosed: **System Framework** (`android`). Only affects CloX; other callers are
 * untouched. Cannot ship in-system (needs LSPosed), which is accepted.
 */
class PermGrantHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return  // only hook system_server

        runCatching {
            val atms = Class.forName(
                "com.android.server.wm.ActivityTaskManagerService", false, lpparam.classLoader
            )
            var hooked = 0
            // The identity-clear hook is generic (it only keys off CloX's uid), so the same instance
            // serves every ATMS method whose permission/filtering we need to bypass for CloX.
            for (name in arrayOf("getTaskSnapshot", "takeTaskSnapshot", "getTasks", "getRecentTasks")) {
                hooked += XposedBridge.hookAllMethods(atms, name, snapshotHook).size
            }
            XposedBridge.log("CloX/PathA: perm hook installed in system_server ($hooked methods)")
        }.onFailure { XposedBridge.log("CloX/PathA: hook install failed: $it") }
    }

    private val snapshotHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val uid = Binder.getCallingUid()
            val appId = cloxAppId()
            if (appId >= 0 && (uid % PER_USER_RANGE) == appId) {
                // Run the rest of the method as the system uid so the READ_FRAME_BUFFER enforce passes.
                param.setObjectExtra(EXTRA, Binder.clearCallingIdentity())
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            (param.getObjectExtra(EXTRA) as? Long)?.let { Binder.restoreCallingIdentity(it) }
        }
    }

    // Resolved lazily (CloX may not be installed when system_server starts); cached on success only,
    // so it retries harmlessly until CloX is known.
    @Volatile private var cachedAppId = -1
    private fun cloxAppId(): Int {
        if (cachedAppId >= 0) return cachedAppId
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val ctx = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
            cachedAppId = ctx.packageManager.getPackageUid(CLOX_PKG, 0) % PER_USER_RANGE
            XposedBridge.log("CloX/PathA: resolved CloX appId=$cachedAppId")
        }
        return cachedAppId
    }

    companion object {
        private const val CLOX_PKG = "com.yzmm.clox"
        private const val PER_USER_RANGE = 100000
        private const val EXTRA = "clox_snapshot_ident"
    }
}
