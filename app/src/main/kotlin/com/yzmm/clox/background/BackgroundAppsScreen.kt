package com.yzmm.clox.background

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.compose.material3.Icon
import com.yzmm.clox.R
import com.yzmm.clox.qs.privilege.PrivilegeLevel
import com.yzmm.clox.qs.privilege.PrivilegeManager
import com.yzmm.clox.qs.privilege.RootOperations
import com.yzmm.clox.qs.privilege.ShizukuOperations
import com.yzmm.clox.settings.compose.WearSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BgApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

@Composable
fun BackgroundAppsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<BgApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadRecentApps(ctx) }
        loaded = true
    }

    fun end(packages: Collection<String>) {
        if (packages.isEmpty()) return
        scope.launch {
            val count = withContext(Dispatchers.IO) { endApps(ctx, packages) }
            apps = apps.filterNot { it.packageName in packages }
            Toast.makeText(
                ctx,
                ctx.getString(R.string.bg_apps_ended, count),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    WearSettingsScreen(title = stringResource(R.string.bg_apps_title)) {
        if (apps.isNotEmpty()) {
            SettingsButton(
                title = res(R.string.bg_apps_kill_all),
                primary = true,
            ) { end(apps.map { it.packageName }) }
        }
        if (loaded && apps.isEmpty()) {
            SettingsNote(res(R.string.bg_apps_empty))
        }
        apps.forEach { app ->
            val bitmap = app.icon
            SettingsButton(
                title = app.label,
                icon = if (bitmap == null) null else {
                    {
                        Icon(
                            painter = BitmapPainter(bitmap),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            ) { end(listOf(app.packageName)) }
        }
        SettingsNote(res(R.string.bg_apps_note))
    }
}

/** Recently used launchable apps, newest first. Usage access is a priv-app grant here. */
private fun loadRecentApps(ctx: Context): List<BgApp> {
    val pm = ctx.packageManager
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyList()
    val now = System.currentTimeMillis()
    val stats = try {
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now)
    } catch (_: Exception) {
        null
    } ?: return emptyList()

    val cutoff = now - 6 * 60 * 60 * 1000L
    val ended = ctx.getSharedPreferences(ENDED_PREFS, Context.MODE_PRIVATE).all
    val seen = HashSet<String>()
    val out = ArrayList<BgApp>()
    for (stat in stats.sortedByDescending { it.lastTimeUsed }) {
        val pkg = stat.packageName ?: continue
        if (pkg == ctx.packageName || stat.lastTimeUsed < cutoff || !seen.add(pkg)) continue
        val endedAt = (ended[pkg] as? Long) ?: 0L
        if (stat.lastTimeUsed <= endedAt) continue
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            continue
        }
        if (pm.getLaunchIntentForPackage(pkg) == null) continue
        val label = try {
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
        val icon = try {
            info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        } catch (_: Exception) {
            null
        }
        out.add(BgApp(pkg, label, icon))
        if (out.size >= 24) break
    }
    return out
}

private fun endApps(ctx: Context, packages: Collection<String>): Int {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val level = PrivilegeManager.level(ctx)
    val endedPrefs = ctx.getSharedPreferences(ENDED_PREFS, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    var ended = 0
    for (pkg in packages) {
        val ok = when (level) {
            PrivilegeLevel.SHIZUKU ->
                ShizukuOperations.forceStop(pkg) || killBackground(am, pkg)
            PrivilegeLevel.ROOT ->
                RootOperations.forceStop(pkg) || killBackground(am, pkg)
            PrivilegeLevel.NONE -> killBackground(am, pkg)
        }
        if (ok) {
            ended++
            endedPrefs.edit().putLong(pkg, now).apply()
        }
    }
    return ended
}

private fun killBackground(am: ActivityManager?, pkg: String): Boolean = try {
    am?.killBackgroundProcesses(pkg)
    true
} catch (_: Exception) {
    false
}

private const val ENDED_PREFS = "bg_apps_ended"
