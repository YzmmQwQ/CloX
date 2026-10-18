package com.yzmm.clox.watchface.selector

import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class FastPagerSnapHelper : PagerSnapHelper() {

    override fun findTargetSnapPosition(
        layoutManager: RecyclerView.LayoutManager,
        velocityX: Int,
        velocityY: Int
    ): Int {
        val itemCount = layoutManager.itemCount
        if (itemCount <= 1) return RecyclerView.NO_POSITION

        val currentView = findSnapView(layoutManager) ?: return RecyclerView.NO_POSITION
        val currentPos = layoutManager.getPosition(currentView)
        if (currentPos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION

        val speed = abs(velocityX)
        val pages = when {
            speed > 20000 -> 3
            speed > 12000 -> 2
            else          -> 1
        }

        val delta = if (velocityX > 0) pages else -pages
        return (currentPos + delta).coerceIn(0, itemCount - 1)
    }
}
