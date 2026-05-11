package com.ailife.clox.applist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Icons arranged in a true hexagonal shape (pointy-top axial coords).
 * Drag reorder: treats the flat hex cell order as a logical 1-D list; shift-based (same as Grid2DView).
 */
class HoneycombView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
    var iconSizeDp = 0
    var dragReorderEnabled = false
    var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    private val scroller = OverScroller(ctx)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var velTracker: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var scrollX_ = 0f; private var scrollY_ = 0f
    private var maxScrollX = 0f; private var maxScrollY = 0f
    private var isPanning = false
    private var pendingItems: List<AppItem> = emptyList()

    // ── Drag state ────────────────────────────────────────────────────────────
    private val orderedViews = mutableListOf<View>()
    private val workingItems = mutableListOf<AppItem>()
    private val gridLeft = mutableListOf<Float>()   // fixed layout left edge of each cell
    private val gridTop = mutableListOf<Float>()
    private var cellIconPx = 0

    private var dragActive = false
    private var draggingPos = -1
    private var draggingViewRef: View? = null
    private var pendingDragIdx = -1
    private var pendingDownX = 0f; private var pendingDownY = 0f

    private val dragGesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (isPanning || dragActive) return
            val cx = e.x + scrollX_; val cy = e.y + scrollY_
            val idx = closestGridPos(cx, cy)
            if (idx < 0 || idx >= orderedViews.size) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pendingDragIdx = idx
            pendingDownX = e.x; pendingDownY = e.y
            onAppLongPress?.invoke(workingItems[idx], orderedViews[idx])
        }
    })

    init {
        clipChildren = true; clipToPadding = true; isMotionEventSplittingEnabled = false
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
        var R = 0
        while (1 + 3 * R * (R + 1) < items.size) R++

        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else (minOf(width, height) / 5).coerceAtLeast(1)
        if (iconPx <= 0) return
        cellIconPx = iconPx
        val sqrt3 = 1.7320508f
        val tile = (1f + GAP_RATIO) * iconPx / sqrt3

        val coords = mutableListOf<Pair<Int, Int>>()
        for (r in -R..R) {
            for (q in maxOf(-R, -r - R)..minOf(R, -r + R)) coords.add(q to r)
        }
        coords.sortWith(compareBy({ it.second }, { it.first }))

        val raw = coords.map { (q, r) -> tile * sqrt3 * (q + r / 2f) to tile * 1.5f * r }
        val minX = raw.minOf { it.first }; val maxX_ = raw.maxOf { it.first }
        val minY = raw.minOf { it.second }; val maxY_ = raw.maxOf { it.second }
        val contentW = maxX_ - minX + iconPx; val contentH = maxY_ - minY + iconPx
        val shiftX = if (contentW >= width) -minX else (width - contentW) / 2f - minX
        val shiftY = if (contentH >= height) -minY else (height - contentH) / 2f - minY

        orderedViews.clear(); workingItems.clear(); gridLeft.clear(); gridTop.clear()
        var contentMaxX = 0; var contentMaxY = 0

        for ((idx, item) in items.withIndex()) {
            if (idx >= raw.size) break
            val (rx, ry) = raw[idx]
            val x = (rx + shiftX).toInt(); val y = (ry + shiftY).toInt()
            contentMaxX = contentMaxX.coerceAtLeast(x + iconPx)
            contentMaxY = contentMaxY.coerceAtLeast(y + iconPx)

            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(item.icon.toCircle(iconPx))
                isLongClickable = false
            }
            iv.setOnClickListener {
                if (!AppContextMenu.isShowing) onAppClick?.invoke(item)
            }
            addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                leftMargin = x; topMargin = y
            })
            orderedViews.add(iv)
            gridLeft.add(x.toFloat())
            gridTop.add(y.toFloat())
        }
        workingItems.addAll(items)

        maxScrollX = (contentMaxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (contentMaxY - height).toFloat().coerceAtLeast(0f)
        scrollX_ = maxScrollX / 2f; scrollY_ = maxScrollY / 2f
        applyScroll()
        if (enable3d) post { apply3d() }
        dragActive = false; draggingPos = -1; draggingViewRef = null; pendingDragIdx = -1
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        dragGesture.onTouchEvent(ev)
        if (dragActive) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                AppContextMenu.dismissCurrent(); pendingDragIdx = -1
                scroller.forceFinished(true); lastX = ev.x; lastY = ev.y
                isPanning = false; initVelocity(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingDragIdx >= 0 && dragReorderEnabled) {
                    val dx = ev.x - pendingDownX; val dy = ev.y - pendingDownY
                    if (dx * dx + dy * dy > DRAG_THRESHOLD_SQ) {
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
                if (isPanning) { pendingDragIdx = -1; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                scroller.forceFinished(true); lastX = ev.x; lastY = ev.y
                initVelocity(ev); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX; val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                scrollX_ = (scrollX_ - dx).coerceIn(0f, maxScrollX)
                scrollY_ = (scrollY_ - dy).coerceIn(0f, maxScrollY)
                applyScroll(); if (enable3d) apply3d()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velTracker?.computeCurrentVelocity(1000)
                val vx = -(velTracker?.xVelocity ?: 0f); val vy = -(velTracker?.yVelocity ?: 0f)
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
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val dx = child.left + child.width / 2f - cx
            val dy = child.top + child.height / 2f - cy
            val dist = sqrt(dx * dx + dy * dy)
            val maxDist = sqrt((width * width + height * height).toFloat()) / 2f
            val scale = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.5f
            child.scaleX = scale; child.scaleY = scale
        }
    }

    // ── Drag helpers ──────────────────────────────────────────────────────────

    private fun startDragAt(idx: Int, contentX: Float, contentY: Float) {
        dragActive = true; draggingPos = idx; draggingViewRef = orderedViews[idx]
        parent?.requestDisallowInterceptTouchEvent(true)
        draggingViewRef!!.elevation = resources.displayMetrics.density * 8f
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

        val lo = minOf(oldPos, newPos); val hi = maxOf(oldPos, newPos)
        for (j in lo..hi) {
            if (orderedViews[j] === dv) continue
            orderedViews[j].animate()
                .translationX(gridLeft[j] - orderedViews[j].left)
                .translationY(gridTop[j] - orderedViews[j].top)
                .setDuration(100).start()
        }
    }

    private fun endDrag() {
        val dv = draggingViewRef ?: return
        dv.animate()
            .translationX(gridLeft[draggingPos] - dv.left)
            .translationY(gridTop[draggingPos] - dv.top)
            .setDuration(120)
            .withEndAction { dv.elevation = 0f; dv.scaleX = 1f; dv.scaleY = 1f }.start()
        dragActive = false; draggingPos = -1; draggingViewRef = null
        onOrderChanged?.invoke(workingItems.toList())
    }

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
        private const val GAP_RATIO = 0.12f
        private const val DRAG_THRESHOLD_SQ = 400f
    }
}

// ── Drawable utils ────────────────────────────────────────────────────────────

fun Drawable.toCircle(sizePx: Int): Drawable {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) }
    canvas.clipPath(path)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return BitmapDrawable(null, bmp)
}
