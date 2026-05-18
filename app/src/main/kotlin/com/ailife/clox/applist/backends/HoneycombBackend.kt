package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.HoneycombView
import com.ailife.clox.applist.model.AppItem

class HoneycombBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private var honeycombView: HoneycombView? = null

    override fun createContentView(context: Context): View {
        return HoneycombView(context).also { v ->
            honeycombView = v
            v.enable3d      = AppListPrefs.honeycomb3d(ctx)
            v.effect3DConfig = AppListPrefs.honeycomb3dConfig(ctx)
            v.iconSizeDp    = AppListPrefs.honeycombIconSize(ctx)
            v.showBadge    = AppListPrefs.notifBadge(ctx)
            v.showCount    = AppListPrefs.notifBadgeCount(ctx)
            v.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            v.onAppLongPress  = { item, anchor -> onAppLongPress?.invoke(item, anchor) }
            v.onOrderChanged  = { items -> onOrderChanged?.invoke(items) }
        }
    }

    override fun bindItems(items: List<AppItem>) = honeycombView?.bindItems(items) ?: Unit
    override fun setDragEnabled(enabled: Boolean) { honeycombView?.dragReorderEnabled = enabled }
    override fun refreshBadges(counts: Map<String, Int>) { honeycombView?.refreshBadges(counts) }
}
