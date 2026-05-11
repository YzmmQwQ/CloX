package com.ailife.clox.applist.backends

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ailife.clox.R
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.model.AppItem

class PagesBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private val cols = AppListPrefs.pagesCols(ctx)
    private val rows = AppListPrefs.pagesRows(ctx)
    private val pageSize get() = cols * rows

    private var pager: ViewPager2? = null
    private var pagesAdapter: PagesAdapter? = null
    private var currentPage = 0

    /** Exposed for GestureInterceptorLayout: true when swipe-right should page back (not close list). */
    val canScrollRight: () -> Boolean = { currentPage > 0 }

    override fun createContentView(context: Context): View {
        val vp = ViewPager2(context).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        pager = vp

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { currentPage = position }
        })

        pagesAdapter = PagesAdapter(cols, rows).also { vp.adapter = it }

        // Detect swipe-RIGHT past page 0 (dx < 0 = content moves left = user swipes right).
        // When currentPage == 0 and pagesCanScrollRight returns false, the gesture interceptor
        // will already handle the RIGHT swipe to close the app list — this listener is a safety net.
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val innerRv = vp.getChildAt(0) as? RecyclerView
        innerRv?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var startX = 0f
            private var fired = false
            override fun onInterceptTouchEvent(rv: RecyclerView, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = ev.x; fired = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (!fired && currentPage == 0 && ev.x - startX > touchSlop) {
                            fired = true
                            onRequestClose?.invoke()
                        }
                    }
                }
                return false  // never consume
            }
        })

        return vp
    }

    override fun bindItems(items: List<AppItem>) {
        val pages = items.chunked(pageSize)
        pagesAdapter?.submitPages(pages)
    }

    // ── Page adapter ──────────────────────────────────────────────────────────

    inner class PagesAdapter(
        private val cols: Int,
        private val rows: Int
    ) : RecyclerView.Adapter<PagesAdapter.PageVH>() {

        private var pages: List<List<AppItem>> = emptyList()

        fun submitPages(newPages: List<List<AppItem>>) {
            pages = newPages
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            // Wrap grid in a FrameLayout so we can centre it vertically
            val wrapper = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val grid = RecyclerView(parent.context).apply {
                layoutManager = SquarePagesGridLayoutManager(parent.context, cols)
                isNestedScrollingEnabled = false
                // WRAP_CONTENT + CENTER_VERTICAL centres the grid in the page
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
                )
            }
            wrapper.addView(grid)
            return PageVH(wrapper, grid)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(pages[position])
        }

        inner class PageVH(wrapper: FrameLayout, private val grid: RecyclerView) : RecyclerView.ViewHolder(wrapper) {
            private val iconAdapter = IconGridAdapter()
            init { grid.adapter = iconAdapter }
            fun bind(items: List<AppItem>) = iconAdapter.submitList(items)
        }

        inner class IconGridAdapter : androidx.recyclerview.widget.ListAdapter<AppItem,
                IconGridAdapter.IVH>(DIFF) {

            inner class IVH(view: View) : RecyclerView.ViewHolder(view) {
                init {
                    view.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_ID.toInt()) onAppClick?.invoke(currentList[pos])
                    }
                    view.setOnLongClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_ID.toInt()) onAppLongPress?.invoke(currentList[pos], view)
                        true
                    }
                }
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                IVH(buildCell(parent.context))

            override fun onBindViewHolder(holder: IVH, position: Int) {
                val item = getItem(position)
                holder.itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
                holder.itemView.findViewById<TextView>(R.id.appLabel).text = item.label
            }
        }

        // Forces square cells (same technique as GridBackend)
        private inner class SquarePagesGridLayoutManager(context: Context, cols: Int) : GridLayoutManager(context, cols) {
            override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
                super.measureChildWithMargins(child, widthUsed, heightUsed)
                val side = child.measuredWidth
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
                )
            }
        }
    }

    private fun buildCell(context: Context): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).also { it.recycle() }.let {
                context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    .getDrawable(0)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val iv = ImageView(context).apply {
            id = R.id.appIcon
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
            }.let { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        }
        val tv = TextView(context).apply {
            id = R.id.appLabel
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dp(context) }
        }
        cell.addView(iv); cell.addView(tv)
        return cell
    }

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a.label == b.label
        }
    }

    private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
