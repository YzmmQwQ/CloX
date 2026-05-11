package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListAdapter
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.AppListTouchCallback
import com.ailife.clox.applist.model.AppItem

class GridBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private val cols      = AppListPrefs.gridCols(ctx)
    private val showNames = AppListPrefs.gridNames(ctx)
    private var adapter: AppListAdapter? = null

    override fun createContentView(context: Context): View {
        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, cols)
            clipToPadding = false
        }

        val itemRes = if (showNames) R.layout.item_app_grid else R.layout.item_app_icon
        adapter = AppListAdapter(itemRes) { view, item ->
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            if (showNames) view.findViewById<TextView>(R.id.appLabel).text = item.label
        }.also { adp ->
            adp.onAppClick     = { onAppClick?.invoke(it) }
            adp.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
            adp.onOrderChanged = { items ->
                AppListPrefs.saveCustomOrder(ctx, items.map { it.packageName })
            }
            val touchHelper = ItemTouchHelper(AppListTouchCallback(adp))
            adp.touchHelper = touchHelper
            touchHelper.attachToRecyclerView(rv)
        }
        rv.adapter = adapter
        return rv
    }

    override fun bindItems(items: List<AppItem>) = adapter?.submitList(items) ?: Unit
    override fun setDragEnabled(enabled: Boolean) { adapter?.dragEnabled = enabled }
}
