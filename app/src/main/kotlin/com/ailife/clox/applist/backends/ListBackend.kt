package com.ailife.clox.applist.backends

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ailife.clox.applist.NotificationBadge
import com.ailife.clox.applist.ScaledIconCache
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListAdapter
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.AppListTouchCallback
import com.ailife.clox.applist.model.AppItem

class ListBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchField: EditText
    private var adapter: AppListAdapter? = null
    private var allItems: List<AppItem> = emptyList()
    private val showSearch  = AppListPrefs.listSearch(ctx)
    private val enable3d    = AppListPrefs.list3d(ctx)
    private val showBadge   = AppListPrefs.notifBadge(ctx)
    private val showCount   = AppListPrefs.notifBadgeCount(ctx)
    private var notifCounts: Map<String, Int> = emptyMap()
    private var effect3d: (() -> Unit)? = null

    override fun createContentView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        searchField = buildSearchField(context)
        searchField.visibility = if (showSearch) View.VISIBLE else View.GONE
        root.addView(searchField)

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            clipToPadding = false
            setPadding(0, 0, 0, 8.dp(context))
        }

        adapter = AppListAdapter(
            itemLayoutRes = R.layout.item_app_list
        ) { view, item ->
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
            view.findViewById<TextView>(R.id.appLabel).text = item.label
            NotificationBadge.bind(view.findViewById(R.id.notifBadge), item.packageName, notifCounts, showBadge, showCount)
        }.also { adp ->
            adp.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            adp.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
            adp.onOrderChanged = { items -> onOrderChanged?.invoke(items) }
            val touchCb = AppListTouchCallback(adp)
            val touchHelper = ItemTouchHelper(touchCb)
            adp.touchHelper = touchHelper
            touchHelper.attachToRecyclerView(recyclerView)
        }
        recyclerView.adapter = adapter

        if (enable3d) attach3d(recyclerView)

        root.addView(recyclerView)
        return root
    }

    override fun bindItems(items: List<AppItem>) {
        allItems = items
        val query = if (::searchField.isInitialized) searchField.text?.toString()?.trim()?.lowercase() ?: "" else ""
        adapter?.submitList(if (query.isEmpty()) items else items.filter { it.label.lowercase().contains(query) })
        // Re-apply 3D scales after adapter dispatches — post so layout is settled
        if (enable3d) recyclerView.post { effect3d?.invoke() }
    }

    override fun setDragEnabled(enabled: Boolean) { adapter?.dragEnabled = enabled }

    override fun refreshBadges(counts: Map<String, Int>) {
        notifCounts = counts
        updateVisibleBadges(recyclerView, counts)
    }

    private fun buildSearchField(ctx: Context): EditText {
        val bg = ctx.getDrawable(R.drawable.bg_search_field)
        return EditText(ctx).apply {
            hint = context.getString(R.string.app_list_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = bg
            val p = 12.dp(ctx); val pv = 8.dp(ctx)
            setPadding(p, pv, p, pv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12.dp(ctx), 12.dp(ctx), 12.dp(ctx), 0) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim()?.lowercase() ?: ""
                    adapter?.submitList(
                        if (q.isEmpty()) allItems
                        else allItems.filter { it.label.lowercase().contains(q) }
                    )
                }
            })
        }
    }

    private fun attach3d(rv: RecyclerView) {
        val config = AppListPrefs.list3dConfig(ctx)
        val effect: () -> Unit = {
            val cy = rv.height / 2f
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val childCy = (child.top + child.bottom) / 2f
                val ratio = (Math.abs(childCy - cy) / (cy + child.height / 2f)).coerceIn(0f, 1f)
                val scale = config.scaleFor(ratio)
                child.scaleX = scale; child.scaleY = scale
            }
        }
        effect3d = effect
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = effect()
        })
        rv.post(effect)
    }

    private fun updateVisibleBadges(rv: RecyclerView, counts: Map<String, Int>) {
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

    private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
