package com.ailife.clox.applist.backends

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        Log.d("CloX/Pages", "createContentView cols=$cols rows=$rows pageSize=$pageSize")

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { currentPage = position }
        })

        pagesAdapter = PagesAdapter().also { vp.adapter = it }

        // Swipe right at page 0 → close app list
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
                return false
            }
        })

        return vp
    }

    override fun bindItems(items: List<AppItem>) {
        pagesAdapter?.submitPages(items.chunked(pageSize))
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PageVH>() {

        private var pages: List<List<AppItem>> = emptyList()

        fun submitPages(newPages: List<List<AppItem>>) {
            pages = newPages
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            // Full-screen page wrapper — no RecyclerView inside, just a plain ViewGroup
            val page = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageVH(page)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(pages[position])
        }

        inner class PageVH(private val page: FrameLayout) : RecyclerView.ViewHolder(page) {

            fun bind(items: List<AppItem>) {
                page.removeAllViews()
                val ctx = page.context
                val screenW = page.resources.displayMetrics.widthPixels
                val cellSize = screenW / cols

                // Build rows of LinearLayouts
                val grid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL
                    )
                }

                for (rowIdx in 0 until rows) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            cellSize
                        )
                        gravity = Gravity.CENTER_HORIZONTAL
                    }

                    for (colIdx in 0 until cols) {
                        val pos = rowIdx * cols + colIdx
                        val item = items.getOrNull(pos)
                        row.addView(buildCell(ctx, item, cellSize))
                    }
                    grid.addView(row)
                }

                page.addView(grid)
            }
        }
    }

    // ── Cell builder ──────────────────────────────────────────────────────────

    private fun buildCell(ctx: Context, item: AppItem?, cellSize: Int): View {
        val cell = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            if (item != null) {
                isClickable = true
                isFocusable = true
                background = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                ).getDrawable(0)
            }
        }

        if (item == null) return cell   // empty slot

        val iconPad = (cellSize * 0.1f).toInt()
        val labelH  = (cellSize * 0.22f).toInt()
        val iconSize = cellSize - iconPad * 2 - labelH

        val iv = ImageView(ctx).apply {
            id = R.id.appIcon
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(cellSize - iconPad * 2, iconSize).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = iconPad
            }
            setImageDrawable(item.icon)
        }

        val tv = TextView(ctx).apply {
            id = R.id.appLabel
            text = item.label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            textSize = 8f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, labelH
            ).apply { gravity = Gravity.BOTTOM }
        }

        cell.addView(iv)
        cell.addView(tv)

        cell.setOnClickListener { onAppClick?.invoke(item) }
        cell.setOnLongClickListener { onAppLongPress?.invoke(item, cell); true }

        return cell
    }
}
