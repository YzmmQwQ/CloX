package com.ailife.clox.applist

import android.animation.ValueAnimator
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
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt



/**
 * Icons arranged in a true hexagonal shape (pointy-top axial coords).
 * Drag reorder: treats the flat hex cell order as a logical 1-D list; shift-based (same as Grid2DView).
 */
class HoneycombView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem, View) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
    /** 3D depth config (curve + min scale). Used when [enable3d] is true. */
    var effect3DConfig = Effect3DConfig()
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
    private var hasBuiltOnce = false   // center only on first build; preserve scroll on rebuilds
    private var maxScrollX = 0f; private var maxScrollY = 0f
    private var isPanning = false
    private var pendingItems: List<AppItem> = emptyList()

    var overscrollStiffness: Int = 5   // 0-10, default 5

    private var overScrollX_ = 0f
    private var overScrollY_ = 0f
    private var overScrollAnim: ValueAnimator? = null
    private val overDamp get() = overscrollStiffness * 0.06f
    private val maxOverScroll get() = dp(overscrollStiffness * 8).toFloat()

    /**
     * When enabled, the settled scroll range extends half a screen past the content on every side,
     * so you can pan a corner icon toward centre and it stays there (springs to that point) instead
     * of snapping straight back to the content edge — handy for reaching corners on a round screen.
     */
    var roundOverscroll = false
    private val restPadX get() = if (roundOverscroll) width / 2f else 0f
    private val restPadY get() = if (roundOverscroll) height / 2f else 0f
    private val minScrollX get() = -restPadX
    private val minScrollY get() = -restPadY
    private val maxScrollXR get() = maxScrollX + restPadX
    private val maxScrollYR get() = maxScrollY + restPadY

    // ── Drag state ────────────────────────────────────────────────────────────
    private val orderedViews = mutableListOf<View>()
    private val badgeViews   = mutableListOf<TextView>()
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
        clipChildren = false; clipToPadding = false; isMotionEventSplittingEnabled = false
    }

    private var needsBuild = false

    fun bindItems(items: List<AppItem>) {
        pendingItems = items
        android.util.Log.d("CloX/HoneycombView", "bindItems n=${items.size} w=$width h=$height iconSizeDp=$iconSizeDp")
        // Build immediately if sized; otherwise flag it and let onLayout build once measured.
        // (The old self-removing OnGlobalLayoutListener leaked after a backend rebuild — the
        // re-fetched ViewTreeObserver was a different instance, so it never detached and re-ran
        // buildGrid on every global layout, saturating the UI thread and making open/close janky
        // until a process restart.)
        if (width > 0 && height > 0) { needsBuild = false; buildGrid(items) }
        // Force a fresh measure/layout pass so onLayout/onSizeChanged is guaranteed to fire and
        // consume the pending build once this view is sized — don't rely on the addView() that
        // just happened having already scheduled one.
        else { needsBuild = true; requestLayout() }
    }

    private var buildScheduled = false

    /** Build the deferred grid once dimensions are known. needsBuild is cleared before building. */
    private fun maybeBuildDeferred() {
        if (needsBuild && width > 0 && height > 0 && pendingItems.isNotEmpty()) {
            needsBuild = false
            buildGrid(pendingItems)
        }
    }

    /**
     * Schedule the deferred build to run AFTER the current layout pass, never inside it. buildGrid
     * adds cells and calls requestLayout(); a requestLayout() issued while a layout traversal is in
     * progress (i.e. from within onLayout/onSizeChanged) is swallowed, so the freshly-added cells
     * never get measured and render at 0x0. post() defers the build to the next message loop, after
     * the traversal completes, so addView()'s requestLayout() schedules a real measure pass.
     */
    private fun scheduleDeferredBuild() {
        if (!needsBuild || buildScheduled) return
        buildScheduled = true
        post { buildScheduled = false; maybeBuildDeferred() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleDeferredBuild()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        scheduleDeferredBuild()
    }

    private fun buildGrid(items: List<AppItem>) {
        removeAllViews()

        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp)
        else (minOf(width, height) / 5.2f).toInt().coerceAtLeast(1)
        android.util.Log.d("CloX/HoneycombView", "buildGrid n=${items.size} iconPx=$iconPx w=$width h=$height")
        if (iconPx <= 0) return
        cellIconPx = iconPx

        // Apple Watch honeycomb: tightly packed rows that alternate between 3 and 4
        // icons, each row centred. The rows overlap slightly, which gives the woven
        // hex look; a full hexagon layout instead leaves uneven gaps at the sides.
        val stepX = iconPx * H_STEP
        val stepY = iconPx * V_STEP
        val raw = ArrayList<Pair<Float, Float>>(items.size)
        var row = 0
        while (raw.size < items.size) {
            val cols = if (row % 2 == 0) 3 else 4
            val left = -(cols - 1) * stepX / 2f
            for (col in 0 until cols) {
                if (raw.size >= items.size) break
                raw.add(left + col * stepX to row * stepY)
            }
            row++
        }
        val minX = raw.minOf { it.first }; val maxX_ = raw.maxOf { it.first }
        val minY = raw.minOf { it.second }; val maxY_ = raw.maxOf { it.second }
        val contentW = maxX_ - minX + iconPx; val contentH = maxY_ - minY + iconPx
        val shiftX = if (contentW >= width) -minX else (width - contentW) / 2f - minX
        val shiftY = if (contentH >= height) -minY else (height - contentH) / 2f - minY

        orderedViews.clear(); workingItems.clear(); gridLeft.clear(); gridTop.clear()
        badgeViews.clear()
        var contentMaxX = 0; var contentMaxY = 0

        for ((idx, item) in items.withIndex()) {
            if (idx >= raw.size) break
            val (rx, ry) = raw[idx]
            val x = (rx + shiftX).toInt(); val y = (ry + shiftY).toInt()
            contentMaxX = contentMaxX.coerceAtLeast(x + iconPx)
            contentMaxY = contentMaxY.coerceAtLeast(y + iconPx)

            val iv = ImageView(context).apply {
                id = com.ailife.clox.R.id.appIcon
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(CircularIconCache.obtain(item.icon, iconPx, "${item.packageName}/${item.activityName}"))
                isLongClickable = false
            }
            val badge = NotificationBadge.makeView(context)
            val cell = FrameLayout(context).apply {
                isLongClickable = false
                // Tag with package so the launcher can locate this app's icon for the return animation.
                setTag(com.ailife.clox.R.id.app_item_package, item.packageName)
            }
            cell.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx))
            cell.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END })
            cell.setOnClickListener {
                if (!AppContextMenu.isShowing) onAppClick?.invoke(item, cell)
            }
            badgeViews.add(badge)

            addView(cell, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                leftMargin = x; topMargin = y
            })
            orderedViews.add(cell)
            gridLeft.add(x.toFloat())
            gridTop.add(y.toFloat())
        }
        workingItems.addAll(items)

        val prevX = scrollX_; val prevY = scrollY_
        maxScrollX = (contentMaxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (contentMaxY - height).toFloat().coerceAtLeast(0f)
        // Center on the first build; on later rebuilds (app installed/updated → list reloaded)
        // keep the user's scroll position instead of jumping back to center.
        if (hasBuiltOnce) {
            scrollX_ = prevX.coerceIn(minScrollX, maxScrollXR); scrollY_ = prevY.coerceIn(minScrollY, maxScrollYR)
        } else {
            scrollX_ = maxScrollX / 2f; scrollY_ = maxScrollY / 2f
            hasBuiltOnce = true
        }
        overScrollX_ = 0f; overScrollY_ = 0f; overScrollAnim?.cancel(); overScrollAnim = null
        applyScroll()
        if (enable3d) post { apply3d() }
        if (lastBadgeCounts.isNotEmpty()) refreshBadges(lastBadgeCounts)
        dragActive = false; draggingPos = -1; draggingViewRef = null; pendingDragIdx = -1
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
                AppContextMenu.dismissCurrent(); pendingDragIdx = -1
                scroller.forceFinished(true)
                overScrollAnim?.cancel(); overScrollAnim = null; overScrollX_ = 0f; overScrollY_ = 0f
                lastX = ev.x; lastY = ev.y
                isPanning = false
                // Right 2/3: block GIL from DOWN so scrolling is always reliable
                if (ev.x >= width / 3f) parent?.requestDisallowInterceptTouchEvent(true)
                initVelocity(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingDragIdx >= 0 && dragReorderEnabled && !enable3d) {
                    parent?.requestDisallowInterceptTouchEvent(true)
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
                        isPanning = true
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
                scroller.forceFinished(true)
                overScrollAnim?.cancel(); overScrollAnim = null; overScrollX_ = 0f; overScrollY_ = 0f
                lastX = ev.x; lastY = ev.y
                initVelocity(ev); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX; val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                val rawX = scrollX_ - dx; val rawY = scrollY_ - dy
                val clampedX = rawX.coerceIn(minScrollX, maxScrollXR); val clampedY = rawY.coerceIn(minScrollY, maxScrollYR)
                scrollX_ = clampedX; scrollY_ = clampedY
                val exX = (rawX - clampedX) * overDamp; val exY = (rawY - clampedY) * overDamp
                overScrollX_ = if (exX != 0f) (overScrollX_ + exX).coerceIn(-maxOverScroll, maxOverScroll) else overScrollX_ * 0.7f
                overScrollY_ = if (exY != 0f) (overScrollY_ + exY).coerceIn(-maxOverScroll, maxOverScroll) else overScrollY_ * 0.7f
                applyScroll(); if (enable3d) apply3d()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velTracker?.computeCurrentVelocity(1000)
                val vx = -(velTracker?.xVelocity ?: 0f); val vy = -(velTracker?.yVelocity ?: 0f)
                recycleVelocity(); isPanning = false
                val overDist = if (overscrollStiffness > 0) dp(overscrollStiffness * 8) else 0
                val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
                if (abs(vx) > minFling || abs(vy) > minFling) {
                    overScrollAnim?.cancel(); overScrollAnim = null
                    val startX = (scrollX_ + overScrollX_).toInt().coerceIn(minScrollX.toInt() - overDist, maxScrollXR.toInt() + overDist)
                    val startY = (scrollY_ + overScrollY_).toInt().coerceIn(minScrollY.toInt() - overDist, maxScrollYR.toInt() + overDist)
                    overScrollX_ = 0f; overScrollY_ = 0f
                    scroller.fling(startX, startY, vx.toInt(), vy.toInt(),
                        minScrollX.toInt(), maxScrollXR.toInt(), minScrollY.toInt(), maxScrollYR.toInt(), overDist, overDist)
                    post(flingRunnable)
                } else {
                    springBackOverscroll()
                }
            }
        }
        return true
    }

    private val flingRunnable: Runnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val rawX = scroller.currX.toFloat(); val rawY = scroller.currY.toFloat()
                scrollX_ = rawX.coerceIn(minScrollX, maxScrollXR); scrollY_ = rawY.coerceIn(minScrollY, maxScrollYR)
                overScrollX_ = rawX - scrollX_; overScrollY_ = rawY - scrollY_
                applyScroll(); if (enable3d) apply3d(); postOnAnimation(this)
            } else {
                overScrollX_ = 0f; overScrollY_ = 0f; applyScroll()
            }
        }
    }

    private fun applyScroll() = scrollTo((scrollX_ + overScrollX_).toInt(), (scrollY_ + overScrollY_).toInt())

    private fun springBackOverscroll() {
        if (overScrollX_ == 0f && overScrollY_ == 0f) return
        val startX = overScrollX_; val startY = overScrollY_
        overScrollAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 350
            interpolator = OvershootInterpolator(2f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                overScrollX_ = startX * t
                overScrollY_ = startY * t
                applyScroll()
                if (enable3d) apply3d()
            }
        }
        overScrollAnim?.start()
    }

    /**
     * Apple Watch-style depth, following the layout used by the watchOS honeycomb:
     * a barely-visible sine push spreads icons at mid radius, scale grows toward the
     * centre and shrinks toward the edges, and every icon fades out over the last few
     * dp at the viewport border. Unlike a radial warp this keeps each row straight,
     * so the bubbles stay on their lattice while scrolling.
     */
    private fun apply3d() {
        if (width == 0 || height == 0) return
        val vw = width.toFloat(); val vh = height.toFloat()
        val cx = vw / 2f; val cy = vh / 2f
        val radius = maxOf(vw, vh) / 2f
        val maxPush = minOf(cx, cy) * 0.9f
        val scrollXNow = scrollX_ + overScrollX_
        val scrollYNow = scrollY_ + overScrollY_
        val density = resources.displayMetrics.density
        val fadeStart = 28f * density
        val fadeEnd = 4f * density
        val fadeRange = (fadeStart - fadeEnd).coerceAtLeast(1f)
        val pi = Math.PI.toFloat()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === draggingViewRef) continue
            val sx = child.left + child.width / 2f - scrollXNow
            val sy = child.top + child.height / 2f - scrollYNow
            val dx = sx - cx
            val dy = sy - cy
            val dist = sqrt(dx * dx + dy * dy)

            // Lens push: strongest at mid radius, zero at the centre and at maxPush.
            val push = if (dist in 0.01f..maxPush) {
                1f + sin(dist / maxPush * pi) * PUSH_STRENGTH
            } else 1f

            val ratio = (dist / radius).coerceIn(0f, 1f)
            val baseScale = effect3DConfig.scaleFor(ratio)
            val centerBoost = 1f + SCALE_CENTER_BOOST * (1f - ratio)

            fun edgeFade(d: Float): Float = when {
                d >= fadeStart -> 1f
                d <= fadeEnd -> 0f
                else -> {
                    val p = (d - fadeEnd) / fadeRange
                    p * p * (3f - 2f * p)
                }
            }
            val fade = minOf(
                edgeFade(sx), edgeFade(vw - sx),
                edgeFade(sy), edgeFade(vh - sy)
            )

            val scale = baseScale * centerBoost * fade
            child.scaleX = scale
            child.scaleY = scale
            child.translationX = cx + dx * push - sx
            child.translationY = cy + dy * push - sy
        }
    }

    // ── Drag helpers ──────────────────────────────────────────────────────────

    private fun startDragAt(idx: Int, contentX: Float, contentY: Float) {
        if (enable3d) { pendingDragIdx = -1; return }
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
        /** Horizontal centre spacing, as a fraction of the icon diameter (visible gap). */
        private const val H_STEP = 1.20f
        /** Vertical row spacing; above 1.0 leaves a small gap between rows. */
        private const val V_STEP = 1.06f
        private const val PUSH_STRENGTH = 0.055f
        private const val SCALE_CENTER_BOOST = 0.14f
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
