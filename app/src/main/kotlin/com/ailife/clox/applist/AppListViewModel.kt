package com.ailife.clox.applist

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.icon.IconResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
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
            addDataScheme("package")
        }
        app.registerReceiver(packageReceiver, filter)
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            val prefs = AppListPrefs.get(ctx)
            val sortMode = AppListPrefs.sort(ctx)
            val customOrder = AppListPrefs.customOrder(ctx)
            val hiddenComponents = AppListPrefs.hiddenComponents(ctx)
            val hideMode = AppListPrefs.hideMode(ctx)

            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val ownMain = pm.getLaunchIntentForPackage(ctx.packageName)?.component?.className
            val all = pm.queryIntentActivities(intent, 0)
                .filterNot { ri ->
                    ri.activityInfo.packageName == ctx.packageName && ri.activityInfo.name == ownMain
                }
                .map { ri ->
                    val key = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                    AppItem(
                        packageName  = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label        = ri.loadLabel(pm).toString(),
                        icon         = IconResolver.resolve(ctx, ri.activityInfo.packageName, ri.activityInfo.name),
                        isHidden     = key in hiddenComponents,
                        customOrder  = customOrder.indexOf(ri.activityInfo.packageName)
                            .let { if (it < 0) Int.MAX_VALUE else it }
                    )
                }

            val resolved = when (hideMode) {
                AppListPrefs.HIDE_MODE_WHITELIST ->
                    if (hiddenComponents.isEmpty()) all  // whitelist empty = show all
                    else all.filter { it.isHidden }
                else -> all.filterNot { it.isHidden }   // blacklist
            }

            val sorted = when (sortMode) {
                AppListPrefs.SORT_CUSTOM -> resolved.sortedWith(
                    compareBy<AppItem> { it.customOrder }.thenBy { it.label.lowercase() }
                )
                else -> resolved.sortedBy { it.label.lowercase() }
            }
            _apps.emit(sorted)
        }
    }

    fun saveCustomOrder(items: List<AppItem>) {
        val ctx = getApplication<Application>()
        AppListPrefs.saveCustomOrder(ctx, items.map { it.packageName })
    }

    private fun appendNewApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val sortMode = AppListPrefs.sort(ctx)
            if (sortMode == AppListPrefs.SORT_CUSTOM) {
                val order = AppListPrefs.customOrder(ctx).toMutableList()
                if (!order.contains(packageName)) {
                    order.add(packageName)
                    AppListPrefs.saveCustomOrder(ctx, order)
                }
            }
            loadApps()
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(packageReceiver)
    }
}
