package com.ailife.clox.applist

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

class Grid2DView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem, View) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
    /** 3D depth config (curve + min scale). Used when [enable3d] is true. */
    var effect3DConfig = Effect3DConfig()
    var showNames = false
    var columns = 4
    var iconSizeDp = 0
    var dragReorderEnabled = false
    var onOrderChanged: ((List<AppItem>) -> Unit)? = null
    var showBadge = false
    var showCount = true
    private var lastBadgeCounts: Map<String, Int> = emptyMap()

    private val scroller = OverScroller(ctx)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var velTracker: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var scrollX_ = 0f; private var scrollY_ = 0f
    private var maxScrollX = 0f; private var maxScrollY = 0f
    private var isPanning = false
    private var pendingItems: List<AppItem> = emptyList()

    // ── Drag reorder ──────────────────────────────────────────────────────────
    // orderedViews[i] = the view currently at visual position i
    // gridLeft/Top[i] = fixed layout left/top of cell i (never changes)
    private val orderedViews = mutableListOf<View>()
    private val workingItems = mutableListOf<AppItem>()
    private val badgeViews   = mutableListOf<TextView>()
    private val gridLeft = mutableListOf<Float>()
    private val gridTop = mutableListOf<Float>()
    private var cellIconPx = 0    // icon size, used to find closest cell center

    private var dragActive = false
    private var draggingPos = -1
    private var draggingViewRef: View? = null
    private var pendingDragIdx = -1
    private var pendingDownX = 0f; private var pendingDownY = 0f

    private val dragGesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            Log.d(TAG, "onLongPress: isPanning=$isPanning dragActive=$dragActive dragReorderEnabled=$dragReorderEnabled items=${orderedViews.size}")
            if (isPanning || dragActive) return
            val cx = e.x + scrollX_; val cy = e.y + scrollY_
            val idx = closestGridPos(cx, cy)
            Log.d(TAG, "onLongPress: cx=$cx cy=$cy idx=$idx")
            if (idx < 0 || idx >= orderedViews.size) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pendingDragIdx = idx
            pendingDownX = e.x; pendingDownY = e.y
            Log.d(TAG, "onLongPress: pendingDragIdx=$pendingDragIdx set, invoking onAppLongPress")
            onAppLongPress?.invoke(workingItems[idx], orderedViews[idx])
        }
    })

    init {
        clipChildren = false; clipToPadding = false
        isMotionEventSplittingEnabled = false
    }

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
        val cols = ceil(sqrt(items.size.toFloat())).toInt().coerceAtLeast(1)
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else (minOf(width, height) / 5).coerceAtLeast(1)
        val gap = (iconPx * GAP_RATIO + 0.5f).toInt()
        if (iconPx <= 0) return
        cellIconPx = iconPx

        val labelH = if (showNames) dp(LABEL_DP) else 0
        val cellW = iconPx + gap
        val cellH = cellW

        orderedViews.clear(); workingItems.clear(); gridLeft.clear(); gridTop.clear()
        badgeViews.clear()

        var maxX = 0; var maxY = 0
        for ((idx, item) in items.withIndex()) {
            val col = idx % cols; val row = idx / cols
            val x = col * cellW + gap / 2; val y = row * cellH + gap / 2
            maxX = maxX.coerceAtLeast(x + cellW); maxY = maxY.coerceAtLeast(y + cellH)

            val cell = buildCell(item, iconPx, labelH) as FrameLayout
            val badge = NotificationBadge.makeView(context)
            cell.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END })
            badgeViews.add(badge)

            addView(cell, FrameLayout.LayoutParams(iconPx, iconPx + labelH).apply {
                leftMargin = x; topMargin = y
            })
            orderedViews.add(cell)
            gridLeft.add(x.toFloat())
            gridTop.add(y.toFloat())
        }
        workingItems.addAll(items)

        maxScrollX = (maxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (maxY - height).toFloat().coerceAtLeast(0f)
        scrollX_ = maxScrollX / 2f; scrollY_ = maxScrollY / 2f
        applyScroll()
        if (enable3d) post { apply3d() }
        if (lastBadgeCounts.isNotEmpty()) refreshBadges(lastBadgeCounts)
        dragActive = false; draggingPos = -1; draggingViewRef = null; pendingDragIdx = -1
    }

    private fun buildCell(item: AppItem, iconPx: Int, labelH: Int): View {
        val cell = FrameLayout(context).apply {
            isClickable = true; isFocusable = true; isLongClickable = false
            background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
        }
        cell.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(item.icon)
        }, FrameLayout.LayoutParams(iconPx, iconPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
        if (showNames && labelH > 0) {
            cell.addView(TextView(context).apply {
                text = item.label; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; textSize = 9f
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, labelH
            ).apply { topMargin = iconPx + dp(2) })
        }
        cell.setOnClickListener {
            if (!AppContextMenu.isShowing) onAppClick?.invoke(item, cell)
        }
        return cell
    }

    fun refreshBadges(counts: Map<String, Int>) {
        lastBadgeCounts = counts
        for (i in badgeViews.indices) {
            NotificationBadge.bind(badgeViews[i], workingItems.getOrNull(i)?.packageName ?: "", counts, showBadge, showCount)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        dragGesture.onTouchEvent(ev)
        if (dragActive) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.d(TAG, "intercept DOWN: pendingDragIdx=$pendingDragIdx dragReorderEnabled=$dragReorderEnabled")
                AppContextMenu.dismissCurrent(); pendingDragIdx = -1
                scroller.forceFinished(true)
                lastX = ev.x; lastY = ev.y; isPanning = false
                initVelocity(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingDragIdx >= 0 && dragReorderEnabled) {
                    // Prevent ancestors (GestureInterceptorLayout) from stealing the stream
                    // while we wait for the drag threshold to be crossed
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = ev.x - pendingDownX; val dy = ev.y - pendingDownY
                    val dist2 = dx * dx + dy * dy
                    Log.d(TAG, "intercept MOVE: pendingDragIdx=$pendingDragIdx dist2=$dist2 threshold=$DRAG_THRESHOLD_SQ")
                    if (dist2 > DRAG_THRESHOLD_SQ) {
                        Log.d(TAG, "intercept MOVE: threshold reached, starting drag")
                        AppContextMenu.dismissCurrent()
                        startDragAt(pendingDragIdx, ev.x + scrollX_, ev.y + scrollY_)
                        pendingDragIdx = -1
                        return true
                    }
                }
                velTracker?.addMovement(ev)
                if (!isPanning) {
                    if (abs(ev.x - lastX) > touchSlop || abs(ev.y - lastY) > touchSlop) {
                        isPanning = true; lastX = ev.x; lastY = ev.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isPanning) { Log.d(TAG, "intercept MOVE: panning, clearing pendingDragIdx"); pendingDragIdx = -1; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.d(TAG, "intercept UP/CANCEL: pendingDragIdx=$pendingDragIdx")
                isPanning = false; recycleVelocity(); pendingDragIdx = -1
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        dragGesture.onTouchEvent(ev)

        if (dragActive) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveDrag(ev.x + scrollX_, ev.y + scrollY_)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
            }
            return true
        }

        velTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = ev.x; lastY = ev.y
                initVelocity(ev); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragActive) {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    scrollX_ = (scrollX_ - dx).coerceIn(0f, maxScrollX)
                    scrollY_ = (scrollY_ - dy).coerceIn(0f, maxScrollY)
                    applyScroll()
                    if (enable3d) apply3d()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velTracker?.computeCurrentVelocity(1000)
                val vx = -(velTracker?.xVelocity ?: 0f)
                val vy = -(velTracker?.yVelocity ?: 0f)
                scroller.fling(scrollX_.toInt(), scrollY_.toInt(),
                    vx.toInt(), vy.toInt(), 0, maxScrollX.toInt(), 0, maxScrollY.toInt())
                recycleVelocity(); isPanning = false; post(flingRunnable)
            }
        }
        return true
    }

    private val flingRunnable: Runnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollX_ = scroller.currX.toFloat(); scrollY_ = scroller.currY.toFloat()
                applyScroll(); if (enable3d) apply3d(); postOnAnimation(this)
            }
        }
    }

    private fun applyScroll() = scrollTo(scrollX_.toInt(), scrollY_.toInt())

    private fun apply3d() {
        val cx = scrollX_ + width / 2f; val cy = scrollY_ + height / 2f
        val maxDist = sqrt((width * width + height * height).toFloat()) / 2f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === draggingViewRef) continue
            val dx = child.left + child.translationX + child.width / 2f - cx
            val dy = child.top + child.translationY + child.height / 2f - cy
            val ratio = (sqrt(dx * dx + dy * dy) / maxDist)
            child.scaleX = effect3DConfig.scaleFor(ratio)
            child.scaleY = effect3DConfig.scaleFor(ratio)
        }
    }

    // ── Drag reorder ──────────────────────────────────────────────────────────

    private fun startDragAt(idx: Int, contentX: Float, contentY: Float) {
        Log.d(TAG, "startDragAt: idx=$idx contentX=$contentX contentY=$contentY")
        dragActive = true; draggingPos = idx; draggingViewRef = orderedViews[idx]
        parent?.requestDisallowInterceptTouchEvent(true)
        draggingViewRef!!.elevation = dp(8).toFloat()
        draggingViewRef!!.scaleX = 1.15f; draggingViewRef!!.scaleY = 1.15f
        moveDrag(contentX, contentY)
    }

    private fun moveDrag(contentX: Float, contentY: Float) {
        val dv = draggingViewRef ?: return
        dv.translationX = contentX - dv.left - dv.width / 2f
        dv.translationY = contentY - dv.top - dv.height / 2f

        val newPos = closestGridPos(contentX, contentY).coerceIn(0, orderedViews.size - 1)
        if (newPos == draggingPos) return

        val oldPos = draggingPos
        val v = orderedViews.removeAt(oldPos)
        val item = workingItems.removeAt(oldPos)
        orderedViews.add(newPos, v); workingItems.add(newPos, item)
        draggingPos = newPos

        // Only animate views in the shifted range — the rest are already at their grid positions
        val lo = minOf(oldPos, newPos); val hi = maxOf(oldPos, newPos)
        for (j in lo..hi) {
            if (orderedViews[j] === dv) continue
            orderedViews[j].animate()
                .translationX(gridLeft[j] - orderedViews[j].left)
                .translationY(gridTop[j] - orderedViews[j].top)
                .setDuration(100)
                .withEndAction { if (enable3d) apply3d() }
                .start()
        }
        if (enable3d) apply3d()
    }

    private fun endDrag() {
        val dv = draggingViewRef ?: return
        val targetTx = gridLeft[draggingPos] - dv.left
        val targetTy = gridTop[draggingPos] - dv.top
        dragActive = false; draggingPos = -1; draggingViewRef = null
        dv.animate()
            .translationX(targetTx)
            .translationY(targetTy)
            .setDuration(120)
            .withEndAction {
                dv.elevation = 0f
                if (enable3d) apply3d() else { dv.scaleX = 1f; dv.scaleY = 1f }
            }.start()
        onOrderChanged?.invoke(workingItems.toList())
    }

    /** Returns the index of the grid cell whose center is closest to (contentX, contentY). */
    private fun closestGridPos(contentX: Float, contentY: Float): Int {
        var best = 0; var bestDist = Float.MAX_VALUE
        val half = cellIconPx / 2f
        for (j in gridLeft.indices) {
            val cx = gridLeft[j] + half; val cy = gridTop[j] + half
            val d = (contentX - cx) * (contentX - cx) + (contentY - cy) * (contentY - cy)
            if (d < bestDist) { bestDist = d; best = j }
        }
        return best
    }

    private fun initVelocity(ev: MotionEvent) {
        velTracker?.recycle(); velTracker = VelocityTracker.obtain(); velTracker?.addMovement(ev)
    }
    private fun recycleVelocity() { velTracker?.recycle(); velTracker = null }
    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "CloX/Grid2DView"
        private const val GAP_RATIO = 0.12f
        private const val LABEL_DP = 16
        private const val DRAG_THRESHOLD_SQ = 400f
    }
}
