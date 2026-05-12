package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListAdapter
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.AppListTouchCallback
import com.ailife.clox.applist.WaterfallLayoutManager
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.applist.toCircle

class WaterfallBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private val enable3d = AppListPrefs.waterfall3d(ctx)
    private var adapter: AppListAdapter? = null
    private var lm: WaterfallLayoutManager? = null
    private var rv3d: RecyclerView? = null
    private var effect3d: (() -> Unit)? = null

    override fun createContentView(context: Context): View {
        val wlm = WaterfallLayoutManager().also { lm = it }
        val rv = RecyclerView(context).also { rv3d = it }.apply {
            layoutManager = wlm
            clipToPadding = false
        }

        adapter = AppListAdapter(R.layout.item_app_icon) { view, item ->
            val iv = view.findViewById<ImageView>(R.id.appIcon)
            if (view.width > 0) {
                iv.setImageDrawable(item.icon.toCircle(view.width))
            } else {
                iv.setImageDrawable(item.icon)
                view.post { iv.setImageDrawable(item.icon.toCircle(view.width.coerceAtLeast(1))) }
            }
        }.also { adp ->
            adp.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            adp.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
            adp.onOrderChanged = { items ->
                AppListPrefs.saveCustomOrder(ctx, items.map { it.packageName })
            }
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

    private fun attach3d(rv: RecyclerView) {
        val effect: () -> Unit = {
            val cx = rv.width / 2f; val cy = rv.height / 2f
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val dx = (child.left + child.right) / 2f - cx
                val dy = (child.top + child.bottom) / 2f - cy
                val maxDist = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val ratio = (dist / maxDist).coerceIn(0f, 1f)
                val scale = 1f - ratio * 0.5f
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
