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

    override var onAppClick: ((AppItem) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchField: EditText
    private var adapter: AppListAdapter? = null
    private var allItems: List<AppItem> = emptyList()
    private val showSearch = AppListPrefs.listSearch(ctx)
    private val enable3d   = AppListPrefs.list3d(ctx)

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
            itemLayoutRes = R.layout.item_app_list,
            hasDragHandle = true,
            dragHandleId  = R.id.dragHandle
        ) { view, item ->
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.appLabel).text = item.label
        }.also { adp ->
            adp.onAppClick     = { onAppClick?.invoke(it) }
            adp.onAppLongPress = { item, v -> onAppLongPress?.invoke(item, v) }
            adp.onOrderChanged = { items ->
                AppListPrefs.saveCustomOrder(ctx, items.map { it.packageName })
            }
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
    }

    override fun setDragEnabled(enabled: Boolean) {
        adapter?.dragEnabled = enabled
    }

    private fun buildSearchField(ctx: Context): EditText {
        val bg = ctx.getDrawable(R.drawable.bg_search_field)
        return EditText(ctx).apply {
            hint = "Search apps…"
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
        val effect: () -> Unit = {
            val cy = rv.height / 2f
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val childCy = (child.top + child.bottom) / 2f
                val ratio = (Math.abs(childCy - cy) / (cy + child.height / 2f)).coerceIn(0f, 1f)
                child.scaleX = 1f - ratio * 0.3f
                child.scaleY = 1f - ratio * 0.3f
            }
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = effect()
        })
        rv.post(effect)
    }

    private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
