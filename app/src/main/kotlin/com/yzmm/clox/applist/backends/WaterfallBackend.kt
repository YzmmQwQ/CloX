package com.yzmm.clox.applist.backends

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.yzmm.clox.applist.NotificationBadge
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.applist.AppListAdapter
import com.yzmm.clox.applist.AppListBackend
import com.yzmm.clox.applist.AppListPrefs
import com.yzmm.clox.applist.AppListTouchCallback
import com.yzmm.clox.applist.CircularIconCache
import com.yzmm.clox.applist.SpringEdgeEffectFactory
import com.yzmm.clox.applist.WaterfallLayoutManager
import com.yzmm.clox.applist.model.AppItem

class WaterfallBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private val enable3d  = AppListPrefs.waterfall3d(ctx)
    private val effect3DConfig = AppListPrefs.waterfall3dConfig(ctx)
    private val showBadge = AppListPrefs.notifBadge(ctx)
    private val showCount = AppListPrefs.notifBadgeCount(ctx)
    private var notifCounts: Map<String, Int> = emptyMap()
    private var adapter: AppListAdapter? = null
    private var lm: WaterfallLayoutManager? = null
    private var rv3d: RecyclerView? = null
    private var effect3d: (() -> Unit)? = null

    override fun createContentView(context: Context): View {
        val wlm = WaterfallLayoutManager().also { lm = it }
        val iconSizeDp = AppListPrefs.waterfallIconSize(ctx)
        if (iconSizeDp > 0) {
            wlm.cellSizePx = (iconSizeDp * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }
        wlm.roundOverscroll = AppListPrefs.waterfallRoundOverscroll(ctx)
        val rv = RecyclerView(context).also { rv3d = it }.apply {
            layoutManager = wlm
            clipToPadding = false
            edgeEffectFactory = SpringEdgeEffectFactory(AppListPrefs.overscroll(context))
        }

        adapter = AppListAdapter(R.layout.item_app_icon) { view, item ->
            val iv = view.findViewById<ImageView>(R.id.appIcon)
            val key = "${item.packageName}/${item.activityName}"
            if (view.width > 0) {
                iv.setImageDrawable(CircularIconCache.obtain(item.icon, view.width, key))
            } else {
                iv.setImageDrawable(CircularIconCache.obtain(item.icon, 1, key))
                view.post { iv.setImageDrawable(CircularIconCache.obtain(item.icon, view.width.coerceAtLeast(1), key)) }
            }
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

        if (enable3d) attach3d(rv)
        return rv
    }

    override fun bindItems(items: List<AppItem>) {
        lm?.reset()
        adapter?.submitList(items)
        if (enable3d) rv3d?.post { effect3d?.invoke() }
    }

    override fun setDragEnabled(enabled: Boolean) { adapter?.dragEnabled = enabled }
    override fun refreshBadges(counts: Map<String, Int>) {
        notifCounts = counts
        val rv = rv3d ?: return
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

    private fun attach3d(rv: RecyclerView) {
        val config = effect3DConfig
        val effect: () -> Unit = {
            val cx = rv.width / 2f; val cy = rv.height / 2f
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val dx = (child.left + child.right) / 2f - cx
                val dy = (child.top + child.bottom) / 2f - cy
                val maxDist = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val scale = config.scaleFor(dist / maxDist)
                child.scaleX = scale; child.scaleY = scale
            }
        }
        effect3d = effect
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = effect()
        })
        rv.post(effect)
    }
}
