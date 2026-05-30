package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.ailife.clox.applist.NotificationBadge
import com.ailife.clox.applist.ScaledIconCache
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListAdapter
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.AppListTouchCallback
import com.ailife.clox.applist.SpringEdgeEffectFactory
import com.ailife.clox.applist.model.AppItem

class GridBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private val cols      = AppListPrefs.gridCols(ctx)
    private val showNames = AppListPrefs.gridNames(ctx)
    private val showBadge = AppListPrefs.notifBadge(ctx)
    private val showCount = AppListPrefs.notifBadgeCount(ctx)
    private var notifCounts: Map<String, Int> = emptyMap()
    private var adapter: AppListAdapter? = null
    private var recyclerView: RecyclerView? = null

    override fun createContentView(context: Context): View {
        val rv = RecyclerView(context).also { recyclerView = it }.apply {
            layoutManager = GridLayoutManager(context, cols)
            clipToPadding = false
            edgeEffectFactory = SpringEdgeEffectFactory(AppListPrefs.overscroll(context))
        }

        // Always use item_app_grid.xml; icon size is identical with or without label
        adapter = AppListAdapter(R.layout.item_app_grid) { view, item ->
            val iv = view.findViewById<ImageView>(R.id.appIcon)
            val key = "${item.packageName}/${item.activityName}"
            if (iv.measuredWidth > 0) {
                val size = (iv.measuredWidth - iv.paddingLeft - iv.paddingRight).coerceAtLeast(1)
                iv.setImageDrawable(ScaledIconCache.obtain(item.icon, size, key))
            } else {
                iv.setImageDrawable(item.icon)
                iv.post {
                    val size = (iv.measuredWidth - iv.paddingLeft - iv.paddingRight).coerceAtLeast(1)
                    iv.setImageDrawable(ScaledIconCache.obtain(item.icon, size, key))
                }
            }
            val label = view.findViewById<TextView>(R.id.appLabel)
            if (showNames) { label.text = item.label; label.visibility = View.VISIBLE }
            else label.visibility = View.GONE
            NotificationBadge.bind(view.findViewById(R.id.notifBadge), item.packageName, notifCounts, showBadge, showCount)
        }.also { adp ->
            adp.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            adp.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
            adp.onOrderChanged = { items -> onOrderChanged?.invoke(items) }
            val touchHelper = ItemTouchHelper(AppListTouchCallback(adp))
            adp.touchHelper = touchHelper
            touchHelper.attachToRecyclerView(rv)
        }
        rv.adapter = adapter
        return rv
    }

    override fun bindItems(items: List<AppItem>) = adapter?.submitList(items) ?: Unit
    override fun setDragEnabled(enabled: Boolean) { adapter?.dragEnabled = enabled }
    override fun refreshBadges(counts: Map<String, Int>) {
        notifCounts = counts
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0) continue
            val pkg = adapter?.getDragItem(pos)?.packageName ?: continue
            child.findViewById<TextView>(R.id.notifBadge)?.let {
                NotificationBadge.bind(it, pkg, counts, showBadge, showCount)
            }
        }
    }
}
