package com.ailife.clox.applist

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Apple Watch-style app grid: circular icons in a hex-spiral cluster, pannable
 * with one finger and pinch-zoomable. Labels appear once zoomed in. Long-press
 * opens the same context menu as the other backends.
 */
class AppleGridView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem, View) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var iconSizeDp = 0
    var showLabels = true
    var showBadge = false
    var showCount = true

    private val world = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val scroller = OverScroller(ctx)
    private var velTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var worldW = 0
    private var worldH = 0
    private var iconPx = 0
    private var built = false

    private val cells = mutableListOf<FrameLayout>()
    private val labels = mutableListOf<TextView>()
    private val badgeViews = mutableListOf<TextView>()
    private val workingItems = mutableListOf<AppItem>()
    private val centersX = mutableListOf<Float>()
    private val centersY = mutableListOf<Float>()
    private var lastBadgeCounts: Map<String, Int> = emptyMap()

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isPanning = false

    private val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isPanning) return false
            val idx = indexAt(e.x, e.y) ?: return false
            onAppClick?.invoke(workingItems[idx], cells[idx])
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isPanning) return
            val idx = indexAt(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onAppLongPress?.invoke(workingItems[idx], cells[idx])
        }
    })

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            val next = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (next == old) return false
            // Keep the world point under the fingers fixed while zooming.
            val centerX = width / 2f + panX
            val centerY = height / 2f + panY
            panX += (detector.focusX - centerX) * (1f - next / old)
            panY += (detector.focusY - centerY) * (1f - next / old)
            scale = next
            clampPan()
            applyTransform()
            return true
        }
    })

    init {
        clipChildren = false
        clipToPadding = false
        isMotionEventSplittingEnabled = false
        addView(world, LayoutParams(0, 0))
        isClickable = true
    }

    fun bindItems(items: List<AppItem>) {
        workingItems.clear()
        workingItems.addAll(items)
        if (width > 0 && height > 0) build() else post { if (width > 0) build() }
    }

    private fun build() {
        world.removeAllViews()
        cells.clear(); labels.clear(); badgeViews.clear()
        centersX.clear(); centersY.clear()

        val n = workingItems.size
        if (n == 0 || width == 0 || height == 0) return
        val icon = if (iconSizeDp > 0) dp(iconSizeDp)
        else (minOf(width, height) * ICON_SCREEN_FRACTION).roundToInt()
        iconPx = icon.coerceAtLeast(1)
        val gap = iconPx * GAP_RATIO
        val s = (iconPx + gap) / sqrt(3f)   // axial size: neighbour distance = sqrt(3)*s
        val cellW = (iconPx * 1.9f).roundToInt()
        val cellH = (iconPx * 2.2f).roundToInt()

        // Hex spiral: centre first, then rings outward.
        val pos = ArrayList<Pair<Float, Float>>(n)
        pos.add(0f to 0f)
        var ring = 1
        while (pos.size < n) {
            val dirs = arrayOf(
                intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1),
                intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1),
            )
            var q = -ring
            var r = ring
            for (d in dirs) {
                repeat(ring) {
                    if (pos.size < n) {
                        // flat-top axial -> pixel
                        pos.add(1.5f * s * q to sqrt(3f) * s * (r + q / 2f))
                    }
                    q += d[0]; r += d[1]
                }
            }
            ring++
        }

        val pad = iconPx * 1.1f
        val minX = pos.minOf { it.first } - iconPx / 2f
        val maxX = pos.maxOf { it.first } + iconPx / 2f
        val minY = pos.minOf { it.second } - iconPx / 2f
        val maxY = pos.maxOf { it.second } + iconPx / 2f
        worldW = (maxX - minX + pad * 2).roundToInt()
        worldH = (maxY - minY + pad * 2).roundToInt()
        world.layoutParams = LayoutParams(worldW, worldH)
        world.requestLayout()
        requestLayout()
        world.pivotX = worldW / 2f
        world.pivotY = worldH / 2f

        val shiftX = worldW / 2f
        val shiftY = worldH / 2f

        for ((idx, item) in workingItems.withIndex()) {
            val (px, py) = pos[idx]
            val cx = shiftX + px
            val cy = shiftY + py
            centersX.add(cx); centersY.add(cy)

            val cell = FrameLayout(context).apply { isClickable = false }
            val iv = ImageView(context).apply {
                id = com.ailife.clox.R.id.appIcon
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    CircularIconCache.obtain(
                        item.icon, iconPx, "${item.packageName}/${item.activityName}"))
            }
            val label = TextView(context).apply {
                text = item.label
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }
            val badge = NotificationBadge.makeView(context)

            cell.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            })
            cell.addView(label, FrameLayout.LayoutParams(cellW, dp(16)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                topMargin = iconPx
            })
            cell.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.END })

            world.addView(cell, LayoutParams(cellW, cellH).apply {
                leftMargin = (cx - cellW / 2f).roundToInt()
                topMargin = (cy - iconPx / 2f).roundToInt()
            })
            cells.add(cell)
            labels.add(label)
            badgeViews.add(badge)
        }

        if (!built) {
            built = true
            scale = 1f
            panX = 0f
            panY = 0f
        }
        applyTransform()
        updateLabelVisibility()
        if (lastBadgeCounts.isNotEmpty()) refreshBadges(lastBadgeCounts)
    }

    fun refreshBadges(counts: Map<String, Int>) {
        lastBadgeCounts = counts
        for (i in badgeViews.indices) {
            NotificationBadge.bind(
                badgeViews[i], workingItems.getOrNull(i)?.packageName ?: "",
                counts, showBadge, showCount)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (workingItems.isNotEmpty()) build()
    }

    private fun applyTransform() {
        world.translationX = (width - worldW) / 2f + panX
        world.translationY = (height - worldH) / 2f + panY
        world.scaleX = scale
        world.scaleY = scale
        updateLabelVisibility()
    }

    private fun updateLabelVisibility() {
        val visible = showLabels && scale >= LABEL_SCALE
        for (l in labels) l.visibility = if (visible) VISIBLE else GONE
    }

    private fun maxPanX(): Float =
        ((worldW * scale - width) / 2f).coerceAtLeast(0f)

    private fun maxPanY(): Float =
        ((worldH * scale - height) / 2f).coerceAtLeast(0f)

    private fun clampPan() {
        val mx = maxPanX(); val my = maxPanY()
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    /** Screen point -> item index (icon circle hit test in world coords). */
    private fun indexAt(sx: Float, sy: Float): Int? {
        if (cells.isEmpty()) return null
        val centerScreenX = width / 2f + panX
        val centerScreenY = height / 2f + panY
        val wx = (sx - centerScreenX) / scale + worldW / 2f
        val wy = (sy - centerScreenY) / scale + worldH / 2f
        val hit = (iconPx / 2f + iconPx * 0.18f)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in centersX.indices) {
            val dx = wx - centersX[i]
            val dy = wy - centersY[i]
            val d = dx * dx + dy * dy
            if (d <= hit * hit && d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0) best else null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // We own the whole gesture surface (tap/pan/pinch/long-press).
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gesture.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                isPanning = false
                velTracker?.recycle()
                velTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                velTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velTracker?.addMovement(ev)
                if (scaleDetector.isInProgress) {
                    isPanning = true
                } else {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    if (!isPanning &&
                        (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                        isPanning = true
                    }
                    if (isPanning) {
                        panX += dx; panY += dy
                        clampPan()
                        applyTransform()
                    }
                }
                lastX = ev.x; lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPanning) {
                    velTracker?.computeCurrentVelocity(1000)
                    val vx = velTracker?.xVelocity ?: 0f
                    val vy = velTracker?.yVelocity ?: 0f
                    val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
                    if (abs(vx) > minFling || abs(vy) > minFling) {
                        scroller.fling(
                            panX.roundToInt(), panY.roundToInt(),
                            vx.roundToInt(), vy.roundToInt(),
                            -maxPanX().roundToInt(), maxPanX().roundToInt(),
                            -maxPanY().roundToInt(), maxPanY().roundToInt(),
                        )
                        post(flingRunnable)
                    }
                }
                velTracker?.recycle(); velTracker = null
                isPanning = false
            }
        }
        return true
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                panX = scroller.currX.toFloat()
                panY = scroller.currY.toFloat()
                applyTransform()
                postOnAnimation(this)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val ICON_SCREEN_FRACTION = 0.24f
        private const val GAP_RATIO = 0.14f
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 2.5f
        private const val LABEL_SCALE = 1.15f
    }
}
