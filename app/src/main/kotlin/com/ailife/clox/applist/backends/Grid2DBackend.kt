package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.Grid2DView
import com.ailife.clox.applist.model.AppItem

class Grid2DBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private var grid2dView: Grid2DView? = null

    override fun createContentView(context: Context): View {
        return Grid2DView(context).also { v ->
            grid2dView = v
            v.overscrollStiffness = AppListPrefs.overscroll(ctx)
            v.columns      = AppListPrefs.grid2dCols(ctx)
            v.showNames    = AppListPrefs.grid2dNames(ctx)
            v.enable3d      = AppListPrefs.grid2d3d(ctx)
            v.effect3DConfig = AppListPrefs.grid2d3dConfig(ctx)
            v.iconSizeDp    = AppListPrefs.grid2dIconSize(ctx)
            v.showBadge    = AppListPrefs.notifBadge(ctx)
            v.showCount    = AppListPrefs.notifBadgeCount(ctx)
            v.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            v.onAppLongPress  = { item, anchor -> onAppLongPress?.invoke(item, anchor) }
            v.onOrderChanged  = { items -> onOrderChanged?.invoke(items) }
        }
    }

    override fun bindItems(items: List<AppItem>) = grid2dView?.bindItems(items) ?: Unit
    override fun setDragEnabled(enabled: Boolean) { grid2dView?.dragReorderEnabled = enabled }
    override fun refreshBadges(counts: Map<String, Int>) { grid2dView?.refreshBadges(counts) }
}
