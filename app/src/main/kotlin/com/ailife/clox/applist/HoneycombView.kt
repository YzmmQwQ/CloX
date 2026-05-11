package com.ailife.clox.applist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
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
 * Radius R is the smallest value where 1+3R(R+1) >= itemCount.
 * Content is centered in the view; if it overflows the view scrolls in 2D.
 */
class HoneycombView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
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
        isMotionEventSplittingEnabled = false
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

        // Smallest R so that the hex shape (1 + 3R(R+1) cells) holds all items
        var R = 0
        while (1 + 3 * R * (R + 1) < items.size) R++

        // Auto: show ~5 icons across the smaller screen dimension
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else (minOf(width, height) / 5).coerceAtLeast(1)
        if (iconPx <= 0) return
        val sqrt3 = 1.7320508f
        // Center-to-center = sqrt(3)*tile; visual gap = sqrt(3)*tile - iconPx = GAP_RATIO*iconPx
        // → tile = (1 + GAP_RATIO) * iconPx / sqrt(3)
        val tile = (1f + GAP_RATIO) * iconPx / sqrt3

        // Axial coords for pointy-top hex of radius R, sorted top→bottom left→right
        val coords = mutableListOf<Pair<Int, Int>>()
        for (r in -R..R) {
            for (q in maxOf(-R, -r - R)..minOf(R, -r + R)) coords.add(q to r)
        }
        coords.sortWith(compareBy({ it.second }, { it.first }))

        // Axial → pixel (pointy-top): x = √3·tile·(q + r/2), y = 1.5·tile·r
        val raw = coords.map { (q, r) -> tile * sqrt3 * (q + r / 2f) to tile * 1.5f * r }

        val minX = raw.minOf { it.first };  val maxX = raw.maxOf { it.first }
        val minY = raw.minOf { it.second }; val maxY = raw.maxOf { it.second }
        val contentW = maxX - minX + iconPx;  val contentH = maxY - minY + iconPx
        // No negative margins: leftmost icon at x≥0. Center if content fits, else left/top-align for scrolling.
        val shiftX = if (contentW >= width)  -minX else (width  - contentW) / 2f - minX
        val shiftY = if (contentH >= height) -minY else (height - contentH) / 2f - minY

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
            }
            iv.setOnClickListener { onAppClick?.invoke(item) }
            iv.setOnLongClickListener { onAppLongPress?.invoke(item, iv); true }
            addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                leftMargin = x; topMargin = y
            })
        }

        maxScrollX = (contentMaxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (contentMaxY - height).toFloat().coerceAtLeast(0f)
        scrollX_ = maxScrollX / 2f
        scrollY_ = maxScrollY / 2f
        applyScroll()
        if (enable3d) post { apply3d() }
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
                        lastX = ev.x; lastY = ev.y  // reset so first onTouchEvent MOVE has no jump
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
        // scrollTo offsets the drawing canvas — no physical move, clips correctly at screen edges
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
            val scale = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.5f
            child.scaleX = scale; child.scaleY = scale
        }
    }

    private fun initVelocity(ev: MotionEvent) {
        velTracker?.recycle()
        velTracker = VelocityTracker.obtain()
        velTracker?.addMovement(ev)
    }

    private fun recycleVelocity() {
        velTracker?.recycle(); velTracker = null
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object { private const val GAP_RATIO = 0.12f }
}

fun Drawable.toCircle(sizePx: Int): Drawable {
    if (sizePx <= 0) return this
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) }
    canvas.clipPath(path)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return BitmapDrawable(null, bmp)
}
