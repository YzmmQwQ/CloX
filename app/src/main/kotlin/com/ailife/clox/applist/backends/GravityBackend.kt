package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.GravityView
import com.ailife.clox.applist.model.AppItem

class GravityBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private var gravityView: GravityView? = null

    override fun createContentView(context: Context): View {
        return GravityView(context).also { v ->
            gravityView = v
            v.followAccel    = AppListPrefs.gravityAccel(ctx)
            v.gravityStrength = AppListPrefs.gravityStrength(ctx)
            v.bounce         = AppListPrefs.gravityBounce(ctx)
            v.friction       = AppListPrefs.gravityFriction(ctx)
            v.showBadge      = AppListPrefs.notifBadge(ctx)
            v.showCount      = AppListPrefs.notifBadgeCount(ctx)
            v.onAppClick     = { item, view -> onAppClick?.invoke(item, view) }
            v.onAppLongPress = { item, anchor -> onAppLongPress?.invoke(item, anchor) }
            v.onRequestClose = { onRequestClose?.invoke() }
        }
    }

    override fun bindItems(items: List<AppItem>) = gravityView?.bindItems(items) ?: Unit
    // Reordering is not possible in physics mode; drag flag intentionally ignored.
    override fun setDragEnabled(enabled: Boolean) {}
    override fun setActive(active: Boolean) { gravityView?.setActive(active) }
    override fun refreshBadges(counts: Map<String, Int>) { gravityView?.refreshBadges(counts) }
}
