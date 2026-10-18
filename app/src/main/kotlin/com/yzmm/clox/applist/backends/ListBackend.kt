package com.yzmm.clox.applist.backends

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yzmm.clox.applist.NotificationBadge
import com.yzmm.clox.applist.ScaledIconCache
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.applist.AppListAdapter
import com.yzmm.clox.applist.AppListBackend
import com.yzmm.clox.applist.AppListPrefs
import com.yzmm.clox.applist.AppListTouchCallback
import com.yzmm.clox.applist.SpringEdgeEffectFactory
import com.yzmm.clox.applist.model.AppItem

class ListBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null
    override var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchField: EditText
    private lateinit var searchContainer: View
    private var adapter: AppListAdapter? = null
    private var allItems: List<AppItem> = emptyList()
    private val showSearch  = AppListPrefs.listSearch(ctx)
    private val enable3d    = AppListPrefs.list3d(ctx)
    private val iconSizePx  = AppListPrefs.listIconSize(ctx).let { if (it > 0) it.dp(ctx) else 0 }
    private val roundOverscroll = AppListPrefs.listRoundOverscroll(ctx)
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

        val (container, editText) = buildSearchBar(context)
        searchContainer = container
        searchField = editText
        searchContainer.visibility = if (showSearch) View.VISIBLE else View.GONE
        root.addView(searchContainer)

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            clipToPadding = false
            setPadding(0, 0, 0, 8.dp(context))
            edgeEffectFactory = SpringEdgeEffectFactory(AppListPrefs.overscroll(context))
        }
        if (roundOverscroll) attachRoundOverscroll(recyclerView)

        adapter = AppListAdapter(
            itemLayoutRes = R.layout.item_app_list
        ) { view, item ->
            val iv = view.findViewById<ImageView>(R.id.appIcon)
            val key = "${item.packageName}/${item.activityName}"
            if (iconSizePx > 0) {
                // Fixed size: resize the icon slot and grow the row to fit
                val slot = iv.parent as View
                slot.layoutParams = slot.layoutParams.apply { width = iconSizePx; height = iconSizePx }
                view.layoutParams = view.layoutParams.apply {
                    height = maxOf(iconSizePx + 16.dp(ctx), 56.dp(ctx))
                }
                iv.setImageDrawable(ScaledIconCache.obtain(item.icon, iconSizePx, key))
            } else if (iv.measuredWidth > 0) {
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

    override fun setActive(active: Boolean) {
        // Leaving the list page (page change / screen-off / drawer close) drops focus;
        // the focus-change listener then hides the keyboard.
        if (!active && ::searchField.isInitialized) searchField.clearFocus()
    }

    private fun hideKeyboard(view: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun refreshBadges(counts: Map<String, Int>) {
        notifCounts = counts
        updateVisibleBadges(recyclerView, counts)
    }

    private fun buildSearchBar(ctx: Context): Pair<View, EditText> {
        val margin = 12.dp(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ctx.getDrawable(R.drawable.bg_search_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, margin, margin, 0) }
        }

        var clearButton: ImageButton? = null

        val editText = EditText(ctx).apply {
            hint = ctx.getString(R.string.app_list_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = null
            val pv = 8.dp(ctx); val ph = margin
            setPadding(ph, pv, 4.dp(ctx), pv)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) hideKeyboard(v)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim()?.lowercase() ?: ""
                    adapter?.submitList(
                        if (q.isEmpty()) allItems
                        else allItems.filter { it.label.lowercase().contains(q) }
                    )
                    clearButton?.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                }
            })
        }

        clearButton = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            background = null
            visibility = View.GONE
            val pad = 8.dp(ctx)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                editText.text.clear()
                editText.clearFocus()
            }
        }

        container.addView(editText)
        container.addView(clearButton)
        return Pair(container, editText)
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

    companion object {
        /**
         * Round overscroll for RecyclerView styles: half-a-screen top/bottom padding
         * (clipToPadding=false) extends the settled scroll range past the content so
         * edge rows can rest at the centre of a round screen — the RecyclerView
         * equivalent of Grid2DView/HoneycombView's roundOverscroll rest padding.
         */
        fun attachRoundOverscroll(rv: RecyclerView) {
            rv.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val half = (bottom - top) / 2
                if (half > 0 && v.paddingTop != half) {
                    v.setPadding(v.paddingLeft, half, v.paddingRight, half)
                }
            }
        }
    }
}
