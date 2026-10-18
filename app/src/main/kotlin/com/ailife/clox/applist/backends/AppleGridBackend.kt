package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.AppleGridView
import com.ailife.clox.applist.model.AppItem

/** Apple Watch-style pan/zoom hex grid backend (View implementation). */
class AppleGridBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private var grid: AppleGridView? = null

    override fun createContentView(context: Context): View =
        AppleGridView(context).also { view ->
            grid = view
            applyPrefs(context)
            view.onAppClick = { item, v -> onAppClick?.invoke(item, v) }
            view.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
        }

    override fun bindItems(items: List<AppItem>) = grid?.bindItems(items) ?: Unit

    override fun refreshBadges(counts: Map<String, Int>) =
        grid?.refreshBadges(counts) ?: Unit

    override fun onPrefsChanged(context: Context) = applyPrefs(context)

    private fun applyPrefs(context: Context) {
        grid?.let { view ->
            view.iconSizeDp = AppListPrefs.appleIconSize(context)
            view.showLabels = AppListPrefs.appleShowLabels(context)
            view.showBadge = AppListPrefs.notifBadge(context)
            view.showCount = AppListPrefs.notifBadgeCount(context)
        }
    }
}
