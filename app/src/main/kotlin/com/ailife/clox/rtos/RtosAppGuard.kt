package com.ailife.clox.rtos

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.ailife.clox.applist.model.AppItem

/**
 * Single entry point for all RTOS app-list decisions.
 * Called from AppListFragment (launch blocking) and AppListViewModel (display filtering).
 */
object RtosAppGuard {

    /** Returns false if RTOS Mode 2 is active and this app is not allowed to launch. */
    fun allowLaunch(ctx: Context, item: AppItem): Boolean {
        if (!RtosManager.isFullMode()) return true
        val whitelist = RtosPrefs.whitelist(ctx)
        if (whitelist.isEmpty()) return true
        return "${item.packageName}/${item.activityName}" in whitelist
    }

    /** Returns true if long-press context menu is allowed (disabled in Mode 2). */
    val isLongPressAllowed get() = !RtosManager.isFullMode()

    /**
     * Filters the display list for Mode 2.
     * Browser/camera/settings apps are detected by intent resolution (not package name).
     * Whitelist overrides default system-app filter; fake settings always passes through.
     */
    fun filterDisplay(items: List<AppItem>, ctx: Context): List<AppItem> {
        if (!RtosManager.isFullMode()) return items
        val whitelist = RtosPrefs.whitelist(ctx)
        val pm = ctx.packageManager
        val ownPkg = ctx.packageName
        val blockedPkgs = blockedPackages(pm)

        return items.filter { item ->
            val key = "${item.packageName}/${item.activityName}"

            // Always show our own fake settings launcher
            if (item.packageName == ownPkg &&
                item.activityName.contains("FakeSettings")) return@filter true

            // Block packages detected as browser / camera / settings by intent
            if (item.packageName in blockedPkgs) return@filter false

            // Custom whitelist overrides default system-app filter
            if (whitelist.isNotEmpty()) return@filter key in whitelist

            // Default: non-user-installed apps only
            val flags = runCatching {
                pm.getApplicationInfo(item.packageName, 0).flags
            }.getOrDefault(0)
            (flags and (ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }
    }

    // ── Cached intent-based blocked-package resolution ────────────────────────

    private var cachedBlockedPkgs: Set<String>? = null
    private var cacheTimestamp = 0L

    /**
     * Returns packages that handle browser / camera / settings intents.
     * Cached for 60 s — cheap to call on every accessibility event.
     */
    fun blockedPackages(pm: PackageManager): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedBlockedPkgs
        if (cached != null && now - cacheTimestamp < 60_000L) return cached
        return resolveBlockedPackages(pm).also {
            cachedBlockedPkgs = it
            cacheTimestamp = now
        }
    }

    private fun resolveBlockedPackages(pm: PackageManager): Set<String> {
        val blocked = mutableSetOf<String>()
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")), 0)
            .forEach { blocked += it.activityInfo.packageName }
        pm.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
            .forEach { blocked += it.activityInfo.packageName }
        pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0)
            .forEach { blocked += it.activityInfo.packageName }
        return blocked
    }
}
