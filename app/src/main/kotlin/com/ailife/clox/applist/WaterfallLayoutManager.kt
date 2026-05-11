package com.ailife.clox.applist

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Lays items in alternating rows of 3 and 4 (7 items per group).
 * Items are square; row height is based on the 4-column cell width.
 */
class WaterfallLayoutManager : RecyclerView.LayoutManager() {

    private var scrollOffset = 0

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun canScrollVertically() = true

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (state.itemCount == 0) return
        fill(recycler, state)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val maxScroll = (totalHeight() - height).coerceAtLeast(0)
        val clamped = when {
            dy < 0 -> dy.coerceAtLeast(-scrollOffset)
            dy > 0 -> dy.coerceAtMost(maxScroll - scrollOffset)
            else   -> 0
        }
        if (clamped == 0) return 0
        scrollOffset += clamped
        detachAndScrapAttachedViews(recycler)
        fill(recycler, state)
        return clamped
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    // Group of 7 items: first 3 in a 3-col row, next 4 in a 4-col row.
    // Returns (rowIndex, colIndex, colsInRow) for adapter position.
    private fun rowInfo(pos: Int): Triple<Int, Int, Int> {
        val group = pos / 7
        val rem   = pos % 7
        return if (rem < 3) Triple(group * 2,     rem,       3)
        else                 Triple(group * 2 + 1, rem - 3,   4)
    }

    private fun cellSize() = if (width > 0) width / 4 else 80   // square based on 4-col

    private fun rowTop(row: Int) = row * cellSize() - scrollOffset

    private fun fill(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val cs = cellSize()
        for (pos in 0 until state.itemCount) {
            val (row, col, cols) = rowInfo(pos)
            val itemW = width / cols
            val top   = rowTop(row)
            val bot   = top + cs

            if (bot < 0 || top > height) {
                recycler.recycleView(recycler.getViewForPosition(pos))
                continue
            }

            val view = recycler.getViewForPosition(pos)
            addView(view)
            measureExact(view, itemW, cs)
            val left = col * itemW
            layoutDecorated(view, left, top, left + itemW, bot)
        }
    }

    private fun totalHeight(): Int {
        if (itemCount == 0) return 0
        val (lastRow, _, _) = rowInfo(itemCount - 1)
        return (lastRow + 1) * cellSize()
    }

    private fun measureExact(view: View, w: Int, h: Int) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
    }

    // Called by RecyclerView after items change — reset scroll
    fun reset() { scrollOffset = 0 }
}
