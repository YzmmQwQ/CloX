package com.yzmm.clox.applist

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
import com.yzmm.clox.applist.model.AppItem
import com.yzmm.clox.icon.IconResolver
import com.yzmm.clox.applist.ScaledIconCache
import com.yzmm.clox.rtos.RtosAppGuard
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

    /** Memoised folder composite icons, keyed by folderId|style|members so re-emits don't recomposite. */
    private val folderIconCache = HashMap<String, Drawable>()

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
            if (force) { ScaledIconCache.evictAll(); CircularIconCache.evictAll(); folderIconCache.clear() }
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

        // ── Folders ──────────────────────────────────────────────────────────
        // Pull folder members out of the top level and inject a synthetic folder item for each
        // folder. Members that are no longer installed are dropped (and the cleanup persisted);
        // empty folders are removed.
        val installedKeys = all.map { "${it.packageName}/${it.activityName}" }.toSet()
        val folders = AppListPrefs.folders(ctx)
        var foldersDirty = false
        folders.forEach { f ->
            val before = f.members.size
            f.members.retainAll { it in installedKeys }
            if (f.members.size != before) foldersDirty = true
        }
        if (folders.removeAll { it.members.isEmpty() }) foldersDirty = true
        if (foldersDirty) AppListPrefs.saveFolders(ctx, folders)

        val memberKeys = folders.flatMap { it.members }.toSet()
        val loose = resolved.filterNot { "${it.packageName}/${it.activityName}" in memberKeys }

        val style = AppListPrefs.style(ctx)
        val shape = FolderIconFactory.shapeForStyle(style)
        val folderIconPx = (96 * ctx.resources.displayMetrics.density).toInt()
        val folderItems = folders.map { f ->
            val fKey = AppListPrefs.folderKey(f.id)
            val cacheKey = "${f.id}|$style|${f.members.joinToString(",")}"
            val icon = folderIconCache[cacheKey] ?: FolderIconFactory
                .build(ctx, f.members, folderIconPx, shape)
                .also { built ->
                    folderIconCache[cacheKey] = built
                    // The composite was rebuilt (members/style changed). The downstream scaled/
                    // circular caches key folder icons by folder tag ("clox.folder/<id>") only and
                    // would keep serving the stale bitmap (an uninstalled/removed/added member),
                    // so drop this folder's entries and force the backend to re-scale.
                    ScaledIconCache.invalidate(fKey)
                    CircularIconCache.invalidate(fKey)
                }
            AppItem(
                packageName  = AppItem.FOLDER_PKG,
                activityName = f.id,
                label        = f.name,
                icon         = icon,
                isFolder     = true,
                folderId     = f.id,
                customOrder  = customOrder.indexOf(fKey).let { if (it < 0) Int.MAX_VALUE else it }
            )
        }

        val sorted = when (sortMode) {
            // Custom: folders sit at their saved drag position, interleaved with apps.
            AppListPrefs.SORT_CUSTOM -> (loose + folderItems).sortedWith(
                compareBy<AppItem> { it.customOrder }.thenBy { it.label.lowercase() }
            )
            // Alphabetical: folders pinned to the top, then apps alphabetically.
            else -> folderItems.sortedBy { it.label.lowercase() } +
                    loose.sortedBy { it.label.lowercase() }
        }

        _apps.value = RtosAppGuard.filterDisplay(sorted, ctx)
    }

    /** Returns the AppItems for a folder's members (installed apps only), in folder order. */
    fun folderMemberItems(folderId: String): List<AppItem> {
        val ctx = getApplication<Application>()
        val folder = AppListPrefs.folders(ctx).firstOrNull { it.id == folderId } ?: return emptyList()
        val byKey = (cachedAllItems ?: return emptyList())
            .associateBy { "${it.packageName}/${it.activityName}" }
        return folder.members.mapNotNull { byKey[it] }
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
