package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.Grid2DView
import com.ailife.clox.applist.model.AppItem

class Grid2DBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private var grid2dView: Grid2DView? = null

    override fun createContentView(context: Context): View {
        return Grid2DView(context).also { v ->
            grid2dView = v
            v.columns      = AppListPrefs.grid2dCols(ctx)
            v.showNames    = AppListPrefs.grid2dNames(ctx)
            v.enable3d     = AppListPrefs.grid2d3d(ctx)
            v.iconSizeDp   = AppListPrefs.grid2dIconSize(ctx)
            v.onAppClick      = { onAppClick?.invoke(it) }
            v.onAppLongPress  = { item, anchor -> onAppLongPress?.invoke(item, anchor) }
        }
    }

    override fun bindItems(items: List<AppItem>) = grid2dView?.bindItems(items) ?: Unit
}
