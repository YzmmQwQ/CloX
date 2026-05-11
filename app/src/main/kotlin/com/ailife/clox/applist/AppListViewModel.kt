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
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        app.registerReceiver(packageReceiver, filter)
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val prefs = getApplication<Application>()
                .getSharedPreferences("app_list", Context.MODE_PRIVATE)
            val hiddenSet = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolvedApps = pm.queryIntentActivities(intent, 0)
                .filterNot { it.activityInfo.packageName == getApplication<Application>().packageName }
                .map { ri ->
                    AppItem(
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label = ri.loadLabel(pm).toString(),
                        icon = IconResolver.resolve(getApplication(), ri.activityInfo.packageName),
                        isHidden = ri.activityInfo.packageName in hiddenSet
                    )
                }
                .filterNot { it.isHidden }
                .sortedBy { it.label.lowercase() }

            _apps.emit(resolvedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(packageReceiver)
    }
}
