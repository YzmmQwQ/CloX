package com.ailife.clox.util

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Detects which app was most recently in the foreground (i.e. the app the user is returning
 * FROM) using [UsageStatsManager]. This covers return paths that send no Android gesture-nav
 * contract — Back button, hardware Home key, and chained app starts.
 *
 * Chosen over an AccessibilityService because it needs no always-on service streaming window
 * events: it is a single, cheap on-demand query made only at the moment we return home, which
 * is lighter on a smartwatch's battery.
 *
 * Requires the PACKAGE_USAGE_STATS special-access permission, granted by the user from the
 * system "Usage access" settings screen.
 */
object ClosingAppDetector {

    private const val TAG = "CloX/Return"

    /** True if the user has granted this app PACKAGE_USAGE_STATS access. */
    fun hasUsagePermission(ctx: Context): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "usage-access check failed", e)
            false
        }
    }

    /** Opens the system "Usage access" settings so the user can grant the permission. */
    fun openUsageAccessSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            // Fallback to the general settings screen if the usage-access screen is absent.
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    /**
     * Returns the package name of the app most recently used before now (excluding this app),
     * or null if usage access is missing / nothing qualifies. Uses max `lastTimeUsed`, which
     * is robust to how long the user spent inside the app.
     */
    fun lastForegroundPackage(ctx: Context, windowMs: Long = 12_000L, maxAgeMs: Long = 2_000L): String? {
        if (!hasUsagePermission(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            // Use real-time foreground/background EVENTS over a short window, not aggregated
            // lastTimeUsed. The short window makes this recency-bounded: returning from an app
            // produces a fresh transition event for it, while a plain screen-wake (no app on top
            // recently) produces no qualifying event → null → no stale/spurious animation.
            val events = usm.queryEvents(end - windowMs, end)
            val self = ctx.packageName
            val e = android.app.usage.UsageEvents.Event()
            var bestPkg: String? = null
            var bestTime = 0L
            while (events.getNextEvent(e)) {
                @Suppress("DEPRECATION")
                val isTransition = e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND
                if (!isTransition) continue
                val pkg = e.packageName ?: continue
                if (pkg == self) continue
                if (e.timeStamp >= bestTime) {
                    bestTime = e.timeStamp
                    bestPkg = pkg
                }
            }
            val age = if (bestPkg != null) end - bestTime else -1
            // A genuine return-from-app has a brand-new transition event (tens of ms). A stale
            // one (seconds old) means the last app left a while ago and this resume is really a
            // screen/keyguard cycle — don't animate.
            if (bestPkg != null && age > maxAgeMs) {
                Log.d(TAG, "lastForegroundPackage -> $bestPkg but age=${age}ms > ${maxAgeMs}ms, ignoring")
                return null
            }
            Log.d(TAG, "lastForegroundPackage -> $bestPkg (age=${age}ms)")
            bestPkg
        } catch (e: Exception) {
            Log.w(TAG, "lastForegroundPackage query failed", e)
            null
        }
    }
}
