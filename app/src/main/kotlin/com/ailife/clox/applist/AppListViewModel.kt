package com.ailife.clox.applist

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.icon.IconResolver
import com.ailife.clox.applist.ScaledIconCache
import com.ailife.clox.rtos.RtosAppGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps

    /** Full (unfiltered) cached list of AppItems so reopening doesn't re-query PackageManager. */
    private var cachedAllItems: List<AppItem>? = null
    private var needsPackageRefresh = true

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            needsPackageRefresh = true
            // The scaled/circular caches key by "pkg/activity_size", which doesn't change when
            // the app's icon does — drop this package's entries so the reload re-renders them.
            ScaledIconCache.invalidatePackage(pkg)
            CircularIconCache.invalidatePackage(pkg)
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> if (!replacing) appendNewApp(pkg) else loadApps()
                else -> loadApps()  // REMOVED or REPLACED
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)   // catches component enable/disable
            addDataScheme("package")
        }
        app.registerReceiver(packageReceiver, filter)
        loadApps()
    }

    fun loadApps(force: Boolean = false) {
        if (!force && cachedAllItems != null && !needsPackageRefresh) {
            emitFilteredSorted(cachedAllItems!!)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (force) { ScaledIconCache.evictAll(); CircularIconCache.evictAll() }
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            val sortMode = AppListPrefs.sort(ctx)
            val rawOrder = AppListPrefs.customOrder(ctx)
            val hiddenComponents = AppListPrefs.hiddenComponents(ctx)
            val hideMode = AppListPrefs.hideMode(ctx)

            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val ownPkg = ctx.packageName
            val activities = pm.queryIntentActivities(intent, 0)
                .filterNot { ri ->
                    ri.activityInfo.packageName == ownPkg &&
                    ri.activityInfo.name == "$ownPkg.MainActivity"
                }

            // Migrate old format (package names only) to pkg/activity keys
            val customOrder = if (rawOrder.isNotEmpty() && rawOrder.none { '/' in it }) {
                val byPkg = activities.groupBy { it.activityInfo.packageName }
                val migrated = rawOrder.flatMap { pkg ->
                    byPkg[pkg]?.map { "${it.activityInfo.packageName}/${it.activityInfo.name}" } ?: emptyList()
                }
                AppListPrefs.saveCustomOrder(ctx, migrated)
                migrated
            } else {
                rawOrder
            }

            val maxIconSize = ctx.resources.displayMetrics.density.let { (96 * it).toInt() }
            val all = activities
                .map { ri ->
                    val key = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                    AppItem(
                        packageName  = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label        = ri.loadLabel(pm).toString(),
                        icon         = downsampleIcon(IconResolver.resolve(ctx, ri.activityInfo.packageName, ri.activityInfo.name), maxIconSize),
                        isHidden     = key in hiddenComponents,
                        customOrder  = customOrder.indexOf(key)
                            .let { if (it < 0) Int.MAX_VALUE else it }
                    )
                }

            // Ensure every app is in the order list (append missing ones at the end)
            val orderSet = customOrder.toSet()
            val missing = all.map { "${it.packageName}/${it.activityName}" }.filterNot { it in orderSet }
            if (missing.isNotEmpty()) {
                AppListPrefs.saveCustomOrder(ctx, customOrder + missing)
            }

            // Pre-warm the icon cache at the list-mode icon size (40dp, fixed by item_app_list.xml)
            // so onBindViewHolder never does bitmap allocation on the main thread.
            val density = ctx.resources.displayMetrics.density
            val listIconPx = (40 * density + 0.5f).toInt()
            for (item in all) {
                val key = "${item.packageName}/${item.activityName}"
                ScaledIconCache.obtain(item.icon, listIconPx, key)
            }

            cachedAllItems = all
            needsPackageRefresh = false
            emitFilteredSorted(all)
        }
    }

    private fun emitFilteredSorted(all: List<AppItem>) {
        val ctx = getApplication<Application>()
        val sortMode = AppListPrefs.sort(ctx)
        val hiddenComponents = AppListPrefs.hiddenComponents(ctx)
        val hideMode = AppListPrefs.hideMode(ctx)
        val customOrder = AppListPrefs.customOrder(ctx)

        // Recompute isHidden from the *current* hidden set (not the cached value captured at
        // load time) so hiding/unhiding an app takes effect on a plain loadApps() without a
        // full reload. Also recalculate customOrder indices from the full order.
        val items = all.map { item ->
            val key = "${item.packageName}/${item.activityName}"
            item.copy(
                isHidden = key in hiddenComponents,
                customOrder = customOrder.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }
            )
        }

        val resolved = when (hideMode) {
            AppListPrefs.HIDE_MODE_WHITELIST ->
                if (hiddenComponents.isEmpty()) items
                else items.filter { it.isHidden }
            else -> items.filterNot { it.isHidden }
        }

        val sorted = when (sortMode) {
            AppListPrefs.SORT_CUSTOM -> resolved.sortedWith(
                compareBy<AppItem> { it.customOrder }.thenBy { it.label.lowercase() }
            )
            else -> resolved.sortedBy { it.label.lowercase() }
        }

        _apps.value = RtosAppGuard.filterDisplay(sorted, ctx)
    }

    /** Called after drag-reorder completes: save new visible order and force full reload. */
    fun saveCustomOrder(items: List<AppItem>) {
        val ctx = getApplication<Application>()
        AppListPrefs.saveVisibleOrder(ctx, items)
        loadApps(force = true)
    }

    private fun appendNewApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (AppListPrefs.sort(ctx) == AppListPrefs.SORT_CUSTOM) {
                val pm = ctx.packageManager
                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val newKeys = pm.queryIntentActivities(launchIntent, 0)
                    .filter { it.activityInfo.packageName == packageName }
                    .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
                if (newKeys.isNotEmpty()) {
                    val order = AppListPrefs.customOrder(ctx).toMutableList()
                    val toAdd = newKeys.filterNot { it in order }
                    if (toAdd.isNotEmpty()) {
                        order.addAll(toAdd)
                        AppListPrefs.saveCustomOrder(ctx, order)
                    }
                }
            }
            loadApps(force = true)
        }
    }

    private fun downsampleIcon(drawable: Drawable, maxSize: Int): Drawable {
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap ?: return drawable
            if (bmp.width <= maxSize && bmp.height <= maxSize) return drawable
            val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            return BitmapDrawable(getApplication<Application>().resources, scaled)
        }
        // Render non-bitmap drawables (AdaptiveIconDrawable, etc.) to bitmap here on the
        // IO thread so onBindViewHolder never has to do it on the main thread.
        val bmp = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888)
        Canvas(bmp).also { canvas ->
            drawable.setBounds(0, 0, maxSize, maxSize)
            drawable.draw(canvas)
        }
        return BitmapDrawable(getApplication<Application>().resources, bmp)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(packageReceiver)
    }
}
