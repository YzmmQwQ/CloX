package com.yzmm.clox.background

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.qs.privilege.PrivilegeLevel
import com.yzmm.clox.qs.privilege.PrivilegeManager
import com.yzmm.clox.qs.privilege.RootOperations
import com.yzmm.clox.qs.privilege.ShizukuOperations
import com.yzmm.clox.util.safeStartActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BgApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

private const val APPS_PER_PAGE = 6
private const val COLUMNS = 3

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

    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        apps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.bg_apps_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        else -> AppPager(
            apps = apps,
            onOpen = { openApp(ctx, it) },
            onEnd = { end(listOf(it)) },
            onEndAll = { end(apps.map { it.packageName }) },
        )
    }
}

@Composable
private fun AppPager(
    apps: List<BgApp>,
    onOpen: (String) -> Unit,
    onEnd: (String) -> Unit,
    onEndAll: () -> Unit,
) {
    val pages = remember(apps) { apps.chunked(APPS_PER_PAGE) }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    LaunchedEffect(pages.size) {
        if (pagerState.currentPage > pages.lastIndex) {
            pagerState.scrollToPage(pages.lastIndex.coerceAtLeast(0))
        }
    }

    HorizontalPagerScaffold(
        pagerState = pagerState,
        pageIndicator = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.bg_apps_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.bg_apps_kill_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onEndAll)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
                HorizontalPageIndicator(pagerState = pagerState)
            }
        },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(pagerState),
        ) { index ->
            AppPage(
                apps = pages.getOrElse(index) { emptyList() },
                onOpen = onOpen,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun AppPage(
    apps: List<BgApp>,
    onOpen: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 34.dp, bottom = 72.dp, start = 10.dp, end = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val gap = 4.dp
        val cellWidth = (maxWidth - gap * (COLUMNS - 1)) / COLUMNS

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            apps.chunked(COLUMNS).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { app ->
                        AppCell(
                            app = app,
                            modifier = Modifier.width(cellWidth),
                            onOpen = { onOpen(app.packageName) },
                            onEnd = { onEnd(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCell(
    app: BgApp,
    modifier: Modifier,
    onOpen: () -> Unit,
    onEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val killThreshold = 36.dp

    Column(
        modifier = modifier
            .graphicsLayer {
                translationY = dragY
                if (dragging) alpha = (1f + dragY / 144f).coerceIn(0.45f, 1f)
            }
            .pointerInput(app.packageName) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = { dragging = false; dragY = 0f },
                    onDragEnd = {
                        dragging = false
                        if (dragY < -killThreshold.toPx()) onEnd()
                        else dragY = 0f
                    },
                    onVerticalDrag = { change, amount ->
                        dragY = (dragY + amount).coerceIn(-144f, 0f)
                        change.consume()
                    },
                )
            }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnd()
                },
                onLongClickLabel = stringResource(R.string.bg_apps_end_app),
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                painter = BitmapPainter(icon),
                contentDescription = app.label,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun openApp(ctx: Context, packageName: String) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.safeStartActivity(intent, showError = false)
    (ctx as? Activity)?.finish()
}

/** Running tasks from dumpsys when privileged, else recently used apps via UsageStats. */
private fun loadRecentApps(ctx: Context): List<BgApp> {
    ensurePrivilegedAccess(ctx)
    return loadRunningTasks(ctx) ?: loadUsageStatsApps(ctx)
}

/**
 * Shizuku/root can grant DUMP + usage-stats as development permissions. Once granted the
 * app itself can run `dumpsys activity recents` and read the real task list.
 */
private fun ensurePrivilegedAccess(ctx: Context) {
    val granted = { permission: String ->
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    if (granted(Manifest.permission.DUMP) &&
        granted(Manifest.permission.PACKAGE_USAGE_STATS)) return
    val pkg = ctx.packageName
    fun grant(grant: (String, String) -> Boolean) {
        grant(pkg, Manifest.permission.PACKAGE_USAGE_STATS)
        grant(pkg, Manifest.permission.DUMP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            grant(pkg, "android.permission.INTERACT_ACROSS_USERS_FULL")
        }
    }
    when (PrivilegeManager.level(ctx)) {
        PrivilegeLevel.SHIZUKU -> grant(ShizukuOperations::grantPermission)
        PrivilegeLevel.ROOT -> grant(RootOperations::grantPermission)
        PrivilegeLevel.NONE -> Unit
    }
}

private fun loadRunningTasks(ctx: Context): List<BgApp>? {
    if (ctx.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    val dump = try {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys activity recents"))
        proc.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        return null
    }
    if (!dump.contains("Recent tasks")) return null

    val headerPkg = Regex("""A=\d+:([A-Za-z0-9_.]+)""")
    val componentPkg = Regex("""mActivityComponent=([^/\s]+)/""")
    val lastActive = Regex("""lastActiveTime=(\d+)""")
    val homePkg = try {
        ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    val newest = HashMap<String, Long>()
    val ended = ctx.getSharedPreferences(ENDED_PREFS, Context.MODE_PRIVATE).all
    val bootEpoch = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
    for (block in dump.split(Regex("""(?m)^\s*\* Recent #""")).drop(1)) {
        // The task's affinity (A=…) is not always the app package (the camera, for
        // example, uses "com.android.camera.CameraActivity"), so prefer the
        // activity component and only fall back to the affinity.
        val name = componentPkg.find(block)?.groupValues?.get(1)
            ?: headerPkg.find(block)?.groupValues?.get(1)
            ?: continue
        if (name == ctx.packageName || name == homePkg || name == "android") continue
        if (block.contains("type=home") || block.contains("type=recents")) continue
        val time = lastActive.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val endedAt = (ended[name] as? Long) ?: 0L
        if (bootEpoch + time <= endedAt) continue
        newest[name] = maxOf(newest[name] ?: 0L, time)
    }
    return newest.entries
        .sortedByDescending { it.value }
        .take(24)
        .mapNotNull { makeBgApp(ctx, it.key) }
}

private fun loadUsageStatsApps(ctx: Context): List<BgApp> {
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
    return stats.sortedByDescending { it.lastTimeUsed }
        .mapNotNull { stat ->
            val pkg = stat.packageName ?: return@mapNotNull null
            if (pkg == ctx.packageName || stat.lastTimeUsed < cutoff || !seen.add(pkg)) return@mapNotNull null
            val endedAt = (ended[pkg] as? Long) ?: 0L
            if (stat.lastTimeUsed <= endedAt) return@mapNotNull null
            makeBgApp(ctx, pkg)
        }
        .take(24)
}

private fun makeBgApp(ctx: Context, packageName: String): BgApp? {
    val pm = ctx.packageManager
    val info = try {
        pm.getApplicationInfo(packageName, 0)
    } catch (_: Exception) {
        return null
    }
    if (pm.getLaunchIntentForPackage(packageName) == null) return null
    val label = try {
        pm.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }
    val icon = try {
        info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
    } catch (_: Exception) {
        null
    }
    return BgApp(packageName, label, icon)
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
