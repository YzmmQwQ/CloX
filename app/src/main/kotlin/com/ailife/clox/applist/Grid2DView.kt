package com.ailife.clox.applist

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Square grid of icons with 2D pan.
 *
 * Cell size = iconPx + GAP_DP. Content is slightly wider and taller than the
 * screen, so panning is available in both axes. Default scroll: centred.
 *
 * Pan gesture: onInterceptTouchEvent steals from child views once touchSlop exceeded.
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
    var iconSizeDp = 0

    private val scroller = OverScroller(ctx)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var velTracker: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var scrollX_ = 0f; private var scrollY_ = 0f
    private var maxScrollX = 0f; private var maxScrollY = 0f
    private var isPanning = false
    private var pendingItems: List<AppItem> = emptyList()

    init {
        clipChildren = true
        clipToPadding = true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun bindItems(items: List<AppItem>) {
        pendingItems = items
        if (width > 0 && height > 0) buildGrid(items)
        else viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                buildGrid(pendingItems)
            }
        })
    }

    private fun buildGrid(items: List<AppItem>) {
        removeAllViews()
        // Auto mode: compute columns so grid is as square as possible
        val cols = if (iconSizeDp > 0) columns.coerceAtLeast(2)
                   else kotlin.math.sqrt(items.size.toFloat()).toInt().coerceIn(3, 10)
        val gap = (GAP_DP * resources.displayMetrics.density + 0.5f).toInt()
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else width / cols
        if (iconPx <= 0) return

        val labelH = if (showNames) dp(LABEL_DP) else 0
        val cellW = iconPx + gap
        val cellH = iconPx + labelH + gap

        var maxX = 0; var maxY = 0
        for ((idx, item) in items.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val x = col * cellW
            val y = row * cellH
            maxX = maxX.coerceAtLeast(x + cellW)
            maxY = maxY.coerceAtLeast(y + cellH)

            val cell = buildCell(item, iconPx, labelH, cellW, cellH)
            addView(cell, FrameLayout.LayoutParams(iconPx, iconPx + labelH).apply {
                leftMargin = x + gap / 2
                topMargin  = y + gap / 2
            })
        }

        maxScrollX = (maxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (maxY - height).toFloat().coerceAtLeast(0f)
        // Start centred
        scrollX_ = maxScrollX / 2f
        scrollY_ = maxScrollY / 2f
        applyScroll()
        if (enable3d) post { apply3d() }
    }

    private fun buildCell(item: AppItem, iconPx: Int, labelH: Int, cellW: Int, cellH: Int): View {
        val cell = FrameLayout(context).apply {
            isClickable = true; isFocusable = true
            background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
        }
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(item.icon)
        }
        cell.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
        if (showNames && labelH > 0) {
            val tv = TextView(context).apply {
                text = item.label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                textSize = 9f
            }
            cell.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, labelH
            ).apply { topMargin = iconPx + dp(2) })
        }
        cell.setOnClickListener { onAppClick?.invoke(item) }
        cell.setOnLongClickListener { onAppLongPress?.invoke(item, cell); true }
        return cell
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = ev.x; lastY = ev.y
                isPanning = false
                initVelocity(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velTracker?.addMovement(ev)
                if (!isPanning) {
                    if (abs(ev.x - lastX) > touchSlop || abs(ev.y - lastY) > touchSlop) {
                        isPanning = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isPanning) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                recycleVelocity()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = ev.x; lastY = ev.y
                initVelocity(ev)
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
                velTracker?.computeCurrentVelocity(1000)
                val vx = -(velTracker?.xVelocity ?: 0f)
                val vy = -(velTracker?.yVelocity ?: 0f)
                scroller.fling(
                    scrollX_.toInt(), scrollY_.toInt(),
                    vx.toInt(), vy.toInt(),
                    0, maxScrollX.toInt(), 0, maxScrollY.toInt()
                )
                recycleVelocity()
                isPanning = false
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
        scrollTo(scrollX_.toInt(), scrollY_.toInt())
    }

    private fun apply3d() {
        val cx = scrollX_ + width / 2f
        val cy = scrollY_ + height / 2f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val dx = child.left + child.width / 2f - cx
            val dy = child.top + child.height / 2f - cy
            val dist = sqrt(dx * dx + dy * dy)
            val maxDist = sqrt((width * width + height * height).toFloat()) / 2f
            val scale = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.55f
            child.scaleX = scale; child.scaleY = scale
        }
    }

    private fun initVelocity(ev: MotionEvent) {
        velTracker?.recycle()
        velTracker = VelocityTracker.obtain()
        velTracker?.addMovement(ev)
    }

    private fun recycleVelocity() { velTracker?.recycle(); velTracker = null }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val GAP_DP   = 8
        private const val LABEL_DP = 16
    }
}
