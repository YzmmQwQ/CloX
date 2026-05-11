package com.ailife.clox.applist

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import kotlin.math.sqrt

/**
 * Regular grid of icons with 2D pan (OverScroller inertia).
 * Configurable columns, optional app name below icon, optional 3D depth effect.
 */
class Grid2DView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
    var showNames = false
    var columns = 4
    var iconSizeDp = 0   // 0 = auto

    private val content = FrameLayout(ctx)

    private val scroller = OverScroller(ctx)
    private var velTracker: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var scrollX_ = 0f; private var scrollY_ = 0f
    private var maxScrollX = 0f; private var maxScrollY = 0f

    init { addView(content) }

    private var pendingItems: List<AppItem> = emptyList()

    fun bindItems(items: List<AppItem>) {
        pendingItems = items
        if (width > 0 && height > 0) {
            buildGrid(items)
        } else {
            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    buildGrid(pendingItems)
                }
            })
        }
    }

    private fun buildGrid(items: List<AppItem>) {
        content.removeAllViews()
        val cols = columns.coerceAtLeast(2)
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else width / cols
        if (iconPx <= 0) return

        val labelH = if (showNames) dp(18) else 0
        val cellH = iconPx + labelH + dp(if (showNames) 2 else 0)

        for ((idx, item) in items.withIndex()) {
            val row = idx / cols
            val col = idx % cols
            val x = (col * (width / cols)).toFloat()
            val y = (row * cellH).toFloat()

            val cell = buildCell(item, iconPx, labelH)
            content.addView(cell, FrameLayout.LayoutParams(width / cols, cellH).apply {
                leftMargin = x.toInt(); topMargin = y.toInt()
            })
        }

        val rows = (items.size + cols - 1) / cols
        val contentH = rows * cellH
        maxScrollX = 0f
        maxScrollY = (contentH - height).coerceAtLeast(0).toFloat()
        applyScroll()
        if (enable3d) apply3d()
    }

    private fun buildCell(item: AppItem, iconPx: Int, labelH: Int): ViewGroup {
        val cell = FrameLayout(context)
        cell.isClickable = true
        cell.isFocusable = true
        cell.background = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        ).getDrawable(0)

        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(item.icon)
        }
        val ivParams = FrameLayout.LayoutParams(iconPx, iconPx).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        }
        cell.addView(iv, ivParams)

        if (showNames && labelH > 0) {
            val tv = TextView(context).apply {
                text = item.label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                textSize = 10f
            }
            val tvParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, labelH
            ).apply { topMargin = iconPx + dp(2) }
            cell.addView(tv, tvParams)
        }

        cell.setOnClickListener { onAppClick?.invoke(item) }
        cell.setOnLongClickListener { onAppLongPress?.invoke(item, cell); true }
        return cell
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velTracker = velTracker ?: VelocityTracker.obtain()
        velTracker!!.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = ev.x; lastY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX; val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                scrollX_ = (scrollX_ - dx).coerceIn(0f, maxScrollX)
                scrollY_ = (scrollY_ - dy).coerceIn(0f, maxScrollY)
                applyScroll()
                if (enable3d) apply3d()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velTracker!!.computeCurrentVelocity(1000)
                scroller.fling(
                    scrollX_.toInt(), scrollY_.toInt(),
                    (-velTracker!!.xVelocity).toInt(), (-velTracker!!.yVelocity).toInt(),
                    0, maxScrollX.toInt(), 0, maxScrollY.toInt()
                )
                velTracker!!.recycle(); velTracker = null
                post(flingRunnable)
            }
        }
        return true
    }

    private val flingRunnable: Runnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollX_ = scroller.currX.toFloat()
                scrollY_ = scroller.currY.toFloat()
                applyScroll()
                if (enable3d) apply3d()
                postOnAnimation(this)
            }
        }
    }

    private fun applyScroll() {
        content.translationX = -scrollX_
        content.translationY = -scrollY_
    }

    private fun apply3d() {
        val cx = scrollX_ + width / 2f
        val cy = scrollY_ + height / 2f
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            val dx = child.left + child.width / 2f - cx
            val dy = child.top + child.height / 2f - cy
            val dist = sqrt(dx * dx + dy * dy)
            val maxDist = sqrt((width * width + height * height).toFloat()) / 2f
            val ratio = (dist / maxDist).coerceIn(0f, 1f)
            val scale = 1f - ratio * 0.35f
            child.scaleX = scale; child.scaleY = scale
        }
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
