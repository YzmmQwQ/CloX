package com.ailife.clox.applist

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Alternating rows of 3 and 4 icons (7 items per group).
 *
 * All icons are the same size: width = height = totalWidth / 4.
 * 3-col rows: centred — startX = cellSize/2 so equal gap on both sides.
 * 4-col rows: icons flush left, fill width exactly.
 *
 * Performance: only items in the visible Y range are laid out.
 * scrollVerticallyBy offsets existing children then fills edges.
 */
class WaterfallLayoutManager : RecyclerView.LayoutManager() {

    /** Fixed cell size in px; 0 = auto (width / 4). Rows re-centre for any size. */
    var cellSizePx = 0

    /**
     * Extends the settled scroll range half a screen past the content on both ends,
     * so top/bottom rows can rest at the centre of a round screen — same semantics
     * as Grid2DView/HoneycombView roundOverscroll.
     */
    var roundOverscroll = false

    private var scrollOffset = 0

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun canScrollVertically() = true

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        fill(recycler, state)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val restPad = if (roundOverscroll) height / 2 else 0
        val maxScroll = (totalHeight() - height).coerceAtLeast(0) + restPad
        val clamped = when {
            dy < 0 -> dy.coerceAtLeast(-(scrollOffset + restPad))
            dy > 0 -> dy.coerceAtMost(maxScroll - scrollOffset)
            else   -> 0
        }
        if (clamped == 0) return 0
        scrollOffset += clamped
        offsetChildrenVertical(-clamped)
        // Fill any newly exposed gap at top or bottom, recycle what's gone off-screen
        fillEdges(recycler, state)
        recycleOutOfBounds(recycler)
        return clamped
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    /** Cell size = fixed px when set, else width/4; same for all icons regardless of row type. */
    private fun cellSize() = when {
        cellSizePx > 0 -> cellSizePx
        width > 0      -> width / 4
        else           -> 80
    }

    /** Returns (row, col, rowType: 3|4) for adapter position. */
    private fun rowInfo(pos: Int): Triple<Int, Int, Int> {
        val group = pos / 7
        val rem   = pos % 7
        return if (rem < 3) Triple(group * 2, rem, 3)
        else                 Triple(group * 2 + 1, rem - 3, 4)
    }

    /** Pixel Y of the top of a row (before scroll offset). */
    private fun rowTopAbs(row: Int) = row * cellSize()

    /** Left pixel for col inside a row. Rows are centred for any cell size
     *  (at the default cs = width/4 this gives the original 0 / cs/2 offsets). */
    private fun leftX(col: Int, rowType: Int, cs: Int): Int {
        val startOffset = (width - rowType * cs) / 2
        return startOffset + col * cs
    }

    // Full initial layout — only attaches items in the visible window
    private fun fill(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val cs = cellSize()
        if (cs <= 0 || state.itemCount == 0) return
        for (pos in 0 until state.itemCount) {
            val top = rowTopAbs(rowInfo(pos).first) - scrollOffset
            if (top >= height) break          // all subsequent items are below screen
            if (top + cs < 0) continue        // above screen — skip but keep iterating

            val (_, col, rowType) = rowInfo(pos)
            val left = leftX(col, rowType, cs)
            val view = recycler.getViewForPosition(pos)
            addView(view)
            measureExact(view, cs, cs)
            layoutDecorated(view, left, top, left + cs, top + cs)
        }
    }

    // After an offset: only add views at the newly-exposed edge
    private fun fillEdges(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val cs = cellSize()
        if (cs <= 0 || state.itemCount == 0) return

        val firstAttached = getChildAt(0)?.let { getPosition(it) } ?: 0
        val lastAttached  = getChildAt(childCount - 1)?.let { getPosition(it) } ?: -1

        // Fill below
        for (pos in (lastAttached + 1) until state.itemCount) {
            val top = rowTopAbs(rowInfo(pos).first) - scrollOffset
            if (top >= height) break
            val (_, col, rowType) = rowInfo(pos)
            val left = leftX(col, rowType, cs)
            val view = recycler.getViewForPosition(pos)
            addView(view)
            measureExact(view, cs, cs)
            layoutDecorated(view, left, top, left + cs, top + cs)
        }

        // Fill above (scrolled down, need items at top)
        for (pos in (firstAttached - 1) downTo 0) {
            val top = rowTopAbs(rowInfo(pos).first) - scrollOffset
            if (top + cs < 0) break
            val (_, col, rowType) = rowInfo(pos)
            val left = leftX(col, rowType, cs)
            val view = recycler.getViewForPosition(pos)
            addView(view, 0)   // add at front
            measureExact(view, cs, cs)
            layoutDecorated(view, left, top, left + cs, top + cs)
        }
    }

    private fun recycleOutOfBounds(recycler: RecyclerView.Recycler) {
        val toRecycle = mutableListOf<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child.bottom < 0 || child.top > height) toRecycle.add(child)
        }
        toRecycle.forEach { removeAndRecycleView(it, recycler) }
    }

    private fun totalHeight(): Int {
        if (itemCount == 0) return 0
        val (lastRow, _, _) = rowInfo(itemCount - 1)
        return (lastRow + 1) * cellSize()
    }

    private fun measureExact(view: View, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
    }

    fun reset() { scrollOffset = 0 }
}
