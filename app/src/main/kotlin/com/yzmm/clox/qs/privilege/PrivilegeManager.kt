package com.yzmm.clox.qs.privilege

import android.content.Context
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

enum class PrivilegeLevel { NONE, SHIZUKU, ROOT }

object PrivilegeManager {

    private var cached: PrivilegeLevel? = null

    fun level(ctx: Context): PrivilegeLevel {
        return cached ?: detect().also { cached = it }
    }

    fun invalidate() { cached = null }

    fun isShizukuInstalled(): Boolean = try {
        Shizuku.pingBinder()  // true only when Shizuku server is actually running
    } catch (_: Exception) { false } catch (_: Error) { false }

    fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun requestShizukuPermission(requestCode: Int) {
        try { Shizuku.requestPermission(requestCode) } catch (_: Exception) {}
    }

    fun isRootAvailable(): Boolean = try {
        Shell.cmd("id").exec().isSuccess
    } catch (_: Exception) { false }

    private fun detect(): PrivilegeLevel {
        if (isShizukuInstalled() && hasShizukuPermission()) return PrivilegeLevel.SHIZUKU
        if (isRootAvailable()) return PrivilegeLevel.ROOT
        return PrivilegeLevel.NONE
    }
}
