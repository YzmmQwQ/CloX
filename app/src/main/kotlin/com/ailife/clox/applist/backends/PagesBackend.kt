package com.ailife.clox.applist.backends

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ailife.clox.R
import com.ailife.clox.applist.AppContextMenu
import com.ailife.clox.applist.AppListAdapter
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.NotificationBadge
import com.ailife.clox.applist.model.AppItem
import kotlin.math.min

class PagesBackend(private val ctx: Context) : AppListBackend {

    override var onAppClick: ((AppItem, View) -> Unit)? = null
    override var onAppLongPress: ((AppItem, View) -> Unit)? = null
    override var onRequestClose: (() -> Unit)? = null

    private val cols      = AppListPrefs.pagesCols(ctx)
    private val rows      = AppListPrefs.pagesRows(ctx)
    private val showBadge = AppListPrefs.notifBadge(ctx)
    private val showCount = AppListPrefs.notifBadgeCount(ctx)
    private var notifCounts: Map<String, Int> = emptyMap()
    private val pageSize get() = cols * rows

    private var container: FrameLayout? = null
    private var pager: ViewPager2? = null
    private var pagesAdapter: PagesAdapter? = null
    var currentPage = 0; private set
    private var dragEnabled = false

    val workingItems = mutableListOf<AppItem>()

    // Drag state
    private var dragView: View? = null
    private var dragItem: AppItem? = null
    private var dragIdx = -1                       // current flat index of dragged item in workingItems
    private var dragPending = false
    private var pendingDragItem: AppItem? = null
    private var pendingDragView: View? = null
    private var downX = 0f
    private var downY = 0f
    private var lastEdgeTrigger = 0L

    val isDragActive: Boolean get() = dragPending || dragView != null

    val canScrollRight: () -> Boolean = { currentPage > 0 }

    override fun createContentView(context: Context): View {
        container = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (dragView != null) return true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; downY = ev.y; dragPending = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragPending) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            val dx = ev.x - downX; val dy = ev.y - downY
                            if (dx * dx + dy * dy > DRAG_THRESHOLD_SQ) {
                                dragPending = false
                                AppContextMenu.dismissCurrent()
                                val item = pendingDragItem ?: return false
                                val view = pendingDragView ?: return false
                                startDrag(item, view)
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragPending = false; pendingDragItem = null; pendingDragView = null
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (dragView != null) {
                    handleDragTouch(ev)
                    return true
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val ct = container!!

        val vp = ViewPager2(context).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        pager = vp
        ct.addView(vp)

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(p: Int) {
                currentPage = p
            }
        })

        pagesAdapter = PagesAdapter().also { vp.adapter = it }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val innerRv = vp.getChildAt(0) as? RecyclerView
        innerRv?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var startX = 0f; private var fired = false
            override fun onInterceptTouchEvent(rv: RecyclerView, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = ev.x; fired = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (!fired && !dragEnabled && currentPage == 0 && ev.x - startX > touchSlop) {
                            fired = true; onRequestClose?.invoke()
                        }
                    }
                }
                return false
            }
        })
        return ct
    }

    override fun bindItems(items: List<AppItem>) {
        workingItems.clear(); workingItems.addAll(items)
        pagesAdapter?.notifyDataSetChanged()
    }

    override fun setDragEnabled(enabled: Boolean) { dragEnabled = enabled }

    override fun refreshBadges(counts: Map<String, Int>) {
        notifCounts = counts
        pager?.post { pagesAdapter?.notifyDataSetChanged() }
    }

    private fun pageSlice(pageIdx: Int): List<AppItem> {
        val start = pageIdx * pageSize
        return workingItems.subList(start, min(start + pageSize, workingItems.size)).toList()
    }

    private fun totalPages() = if (workingItems.isEmpty()) 0
        else (workingItems.size + pageSize - 1) / pageSize

    // ── Drag lifecycle ──────────────────────────────────────────────────

    private fun startDrag(item: AppItem, holderView: View) {
        val pv = pager ?: return
        val rv = pv.getChildAt(0) as? RecyclerView ?: return
        val pageVH = rv.findViewHolderForAdapterPosition(currentPage) as? PagesAdapter.PageVH ?: return

        // Use workingItems (not currentList which may lag behind setItemsDirectly calls)
        val flatIdx = workingItems.indexOf(item)
        if (flatIdx < 0) return
        val pos = flatIdx - currentPage * pageSize
        if (pos < 0 || pos >= pageSize) return

        dragIdx = flatIdx
        dragItem = item

        // Mark the slot invisible via the adapter (persists across redraws)
        getPageAdapter(currentPage)?.dragHiddenPosition = pos
        holderView.visibility = View.INVISIBLE  // immediate feedback before next bind

        val cellW = pageVH.rv.width / cols
        val cellH = pageVH.rv.height / rows
        val dragSize = minOf(cellW, cellH)

        val dragV = View.inflate(ctx, R.layout.item_app_grid, null).apply {
            findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            findViewById<TextView>(R.id.appLabel).apply {
                text = item.label; visibility = View.VISIBLE
            }
            layoutParams = ViewGroup.LayoutParams(dragSize, dragSize)
            measure(
                View.MeasureSpec.makeMeasureSpec(dragSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dragSize, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, dragSize, dragSize)
            scaleX = 1.15f; scaleY = 1.15f
            alpha = 0.9f
            elevation = 20f

            val hlLoc = IntArray(2)
            holderView.getLocationOnScreen(hlLoc)
            val cLoc = IntArray(2)
            container?.getLocationOnScreen(cLoc)
            x = hlLoc[0] - cLoc[0] + (holderView.width - dragSize) / 2f
            y = hlLoc[1] - cLoc[1] + (holderView.height - dragSize) / 2f
        }
        dragView = dragV
        container?.addView(dragV)
        holderView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun handleDragTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragView?.let { v ->
                    v.x = (event.rawX - containerX() - v.width / 2f)
                        .coerceIn(0f, (container?.width ?: 1) - v.width.toFloat())
                    v.y = (event.rawY - containerY() - v.height / 2f)
                        .coerceIn(0f, (container?.height ?: 1) - v.height.toFloat())
                }
                updateHoverPosition(event.rawX, event.rawY)
                checkEdgeDrag(event.rawX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
    }

    /** Live-shift items in workingItems with slide animation for same-page moves. */
    private fun updateHoverPosition(rawX: Float, rawY: Float) {
        val target = calculateTargetFlat(rawX, rawY)
        if (target < 0 || target == dragIdx) return
        val item = dragItem ?: return

        val oldPage = dragIdx / pageSize
        val newPage = target / pageSize
        val oldPosInPage = dragIdx % pageSize
        val newPosInPage = target % pageSize

        getPageAdapter(oldPage)?.dragHiddenPosition = -1

        workingItems.removeAt(dragIdx)
        workingItems.add(target, item)
        dragIdx = target

        getPageAdapter(newPage)?.dragHiddenPosition = newPosInPage

        if (oldPage == newPage) {
            // Update data first (no notify), then notifyItemMoved → RecyclerView slides items
            getPageAdapter(oldPage)?.updateItemsDirect(pageSlice(oldPage))
            getPageAdapter(oldPage)?.notifyItemMoved(oldPosInPage, newPosInPage)
        } else {
            // Cross-page: full rebind on both pages
            getPageAdapter(oldPage)?.setItemsDirectly(pageSlice(oldPage))
            getPageAdapter(newPage)?.setItemsDirectly(pageSlice(newPage))
        }
    }

    private fun getPageAdapter(page: Int): AppListAdapter? {
        val pv = pager?.getChildAt(0) as? RecyclerView ?: return null
        return (pv.findViewHolderForAdapterPosition(page) as? PagesAdapter.PageVH)?.adapter
    }

    private fun containerX(): Float {
        val arr = IntArray(2); container?.getLocationOnScreen(arr); return arr[0].toFloat()
    }
    private fun containerY(): Float {
        val arr = IntArray(2); container?.getLocationOnScreen(arr); return arr[1].toFloat()
    }

    private fun checkEdgeDrag(rawX: Float) {
        val screenW = ctx.resources.displayMetrics.widthPixels.toFloat()
        val edgeW = screenW * 0.10f
        val now = System.currentTimeMillis()
        if (now - lastEdgeTrigger < 1000) return

        val dir = when {
            rawX < edgeW && currentPage > 0 -> -1
            rawX > screenW - edgeW && currentPage < totalPages() - 1 -> 1
            else -> 0
        }
        if (dir == 0) return

        lastEdgeTrigger = now
        showAllItems()   // make all slots visible before the page turn
        pager?.setCurrentItem(currentPage + dir, true)
        // ghost slot will be re-established by updateHoverPosition on next MOVE
    }

    private fun endDrag() {
        AppListPrefs.saveCustomOrder(ctx, workingItems.map { "${it.packageName}/${it.activityName}" })
        cleanupDrag()  // clears dragHiddenPosition + makes all slots visible
        // Update visible inner adapters directly — do NOT call pagesAdapter.notifyDataSetChanged()
        // because that goes through onBindViewHolder → setItemsDirectly which would fire
        // notifyDataSetChanged on inner adapters causing a flash, and is redundant since
        // showAllItems() already corrected visibility in cleanupDrag.
        val pv = pager?.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until totalPages()) {
            val pageVH = pv.findViewHolderForAdapterPosition(i) as? PagesAdapter.PageVH ?: continue
            pageVH.adapter.setItemsDirectly(pageSlice(i))
        }
    }

    private fun calculateTargetFlat(rawX: Float, rawY: Float): Int {
        val pv = pager ?: return -1
        val rv = pv.getChildAt(0) as? RecyclerView ?: return -1
        val pageVH = rv.findViewHolderForAdapterPosition(currentPage) as? PagesAdapter.PageVH ?: return -1
        val pageRv = pageVH.rv

        val rvLoc = IntArray(2)
        pageRv.getLocationOnScreen(rvLoc)

        val relX = rawX - rvLoc[0]
        val relY = rawY - rvLoc[1]

        val cellW = pageRv.width / cols  // cells are square
        val col = (relX / cellW).toInt().coerceIn(0, cols - 1)
        val row = (relY / cellW).toInt().coerceIn(0, rows - 1)
        // workingItems has the dragged item temporarily removed, so size is N-1.
        // Valid insert targets are 0..N-1 = 0..workingItems.size (not workingItems.size-1).
        return (currentPage * pageSize + row * cols + col).coerceAtMost(workingItems.size)
    }

    private fun showAllItems() {
        for (i in 0 until totalPages()) {
            val pv = pager?.getChildAt(0) as? RecyclerView
            val pageVH = pv?.findViewHolderForAdapterPosition(i) as? PagesAdapter.PageVH ?: continue
            pageVH.adapter.dragHiddenPosition = -1  // adapter won't hide anything on next bind
            for (j in 0 until pageVH.adapter.itemCount) {
                pageVH.rv.findViewHolderForAdapterPosition(j)?.itemView?.visibility = View.VISIBLE
            }
        }
    }

    private fun cleanupDrag() {
        container?.removeView(dragView)
        dragView = null
        dragItem = null
        dragIdx = -1
        dragPending = false
        pendingDragItem = null
        pendingDragView = null
        showAllItems()
    }

    // ── Adapter ─────────────────────────────────────────────────────────

    inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PageVH>() {

        override fun getItemCount() = totalPages()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layoutManager = GridLayoutManager(parent.context, cols)
                clipToPadding = false
                itemAnimator = DefaultItemAnimator().apply { moveDuration = 100 }
            }
            val adp = AppListAdapter(R.layout.item_app_grid) { view, item ->
                view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
                view.findViewById<TextView>(R.id.appLabel).apply {
                    text = item.label; visibility = View.VISIBLE
                }
                NotificationBadge.bind(view.findViewById(R.id.notifBadge), item.packageName, notifCounts, showBadge, showCount)
            }
            adp.touchHelper = null
            rv.adapter = adp
            return PageVH(rv, adp)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.adapter.dragEnabled = dragEnabled
            holder.adapter.onAppClick = { item, view -> onAppClick?.invoke(item, view) }
            holder.adapter.onAppLongPress = { item, view ->
                onAppLongPress?.invoke(item, view)
                if (dragEnabled) {
                    dragPending = true
                    pendingDragItem = item
                    pendingDragView = view
                }
            }
            // Use setItemsDirectly to bypass AsyncListDiffer — keeps adapter state always in sync
            // with workingItems without stale-differ animation artifacts.
            holder.adapter.setItemsDirectly(pageSlice(position))
        }

        inner class PageVH(
            val rv: RecyclerView,
            val adapter: AppListAdapter
        ) : RecyclerView.ViewHolder(rv)
    }

    companion object {
        private const val DRAG_THRESHOLD_SQ = 400f
    }
}
