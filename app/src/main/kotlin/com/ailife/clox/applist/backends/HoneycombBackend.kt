package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.HoneycombView
import com.ailife.clox.applist.model.AppItem

class HoneycombBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private var honeycombView: HoneycombView? = null

    override fun createContentView(context: Context): View {
        return HoneycombView(context).also { v ->
            honeycombView = v
            v.enable3d     = AppListPrefs.honeycomb3d(ctx)
            v.iconSizeDp   = AppListPrefs.honeycombIconSize(ctx)
            v.onAppClick      = { onAppClick?.invoke(it) }
            v.onAppLongPress  = { item, anchor -> onAppLongPress?.invoke(item, anchor) }
            v.onOrderChanged  = { items -> AppListPrefs.saveCustomOrder(ctx, items.map { it.packageName }) }
        }
    }

    override fun bindItems(items: List<AppItem>) = honeycombView?.bindItems(items) ?: Unit
    override fun setDragEnabled(enabled: Boolean) { honeycombView?.dragReorderEnabled = enabled }
}
