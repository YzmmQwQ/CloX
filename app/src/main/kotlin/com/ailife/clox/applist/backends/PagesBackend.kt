package com.ailife.clox.applist.backends

import android.content.Context
import android.view.View
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

    override fun createContentView(context: Context): View {
        val vp = ViewPager2(context).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        pager = vp

        pagesAdapter = PagesAdapter(cols).also { vp.adapter = it }

        // Detect swipe-right past page 0 → fire onRequestClose
        val innerRv = vp.getChildAt(0) as? RecyclerView
        innerRv?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var dragging = false
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                dragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dragging && vp.currentItem == 0 && dx > 0) {
                    // scrolling content rightward = user swiped right at page 0
                    onRequestClose?.invoke()
                }
            }
        })

        return vp
    }

    override fun bindItems(items: List<AppItem>) {
        val pages = items.chunked(pageSize)
        pagesAdapter?.submitPages(pages)
    }

    // ── Inner adapter ─────────────────────────────────────────────────────────

    inner class PagesAdapter(private val cols: Int) : RecyclerView.Adapter<PagesAdapter.PageVH>() {

        private var pages: List<List<AppItem>> = emptyList()

        fun submitPages(newPages: List<List<AppItem>>) {
            pages = newPages
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val grid = RecyclerView(parent.context).apply {
                layoutManager = GridLayoutManager(parent.context, cols)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isNestedScrollingEnabled = false
            }
            return PageVH(grid)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(pages[position])
        }

        inner class PageVH(private val grid: RecyclerView) : RecyclerView.ViewHolder(grid) {
            private val iconAdapter = IconGridAdapter()
            init { grid.adapter = iconAdapter }

            fun bind(items: List<AppItem>) = iconAdapter.submitList(items)
        }

        inner class IconGridAdapter : androidx.recyclerview.widget.ListAdapter<AppItem,
                IconGridAdapter.IVH>(AppListBackend_DIFF) {

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

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IVH {
                val cell = buildPageCell(parent.context)
                return IVH(cell)
            }

            override fun onBindViewHolder(holder: IVH, position: Int) {
                val item = getItem(position)
                holder.itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
                holder.itemView.findViewById<TextView>(R.id.appLabel).text = item.label
            }
        }
    }

    private fun buildPageCell(context: Context): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
            setPadding(4.dp(context), 8.dp(context), 4.dp(context), 8.dp(context))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val iv = ImageView(context).apply {
            id = R.id.appIcon
            scaleType = ImageView.ScaleType.FIT_CENTER
            val s = 48.dp(context)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val tv = TextView(context).apply {
            id = R.id.appLabel
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dp(context) }
        }
        cell.addView(iv); cell.addView(tv)
        return cell
    }

    companion object {
        private val AppListBackend_DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a.label == b.label
        }
    }

    private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
