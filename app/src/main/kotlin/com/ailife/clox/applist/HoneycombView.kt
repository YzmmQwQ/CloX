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
 * Hex-arranged icon grid with 2D pan.
 *
 * Layout: 6 icons on even rows, 5 on odd rows (offset by tileSize/2).
 * Icon size: width/6 by default (smaller icons → grid wider than screen → pan in X+Y).
 * Gap between icons: GAP_DP. Default scroll: centred on the content.
 *
 * Pan gesture: onInterceptTouchEvent steals from child ImageViews once touchSlop exceeded.
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
        val gap = (GAP_DP * resources.displayMetrics.density + 0.5f).toInt()
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else width / 8
        if (iconPx <= 0) return

        val tileSize = iconPx + gap
        val rowH = (tileSize * 0.866f).toInt()

        var maxX = 0; var maxY = 0
        for ((idx, item) in items.withIndex()) {
            val (tx, ty) = hexTile(idx, tileSize, rowH)
            maxX = maxX.coerceAtLeast(tx + tileSize)
            maxY = maxY.coerceAtLeast(ty + tileSize)

            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(item.icon.toCircle(iconPx))
            }
            iv.setOnClickListener { onAppClick?.invoke(item) }
            iv.setOnLongClickListener { onAppLongPress?.invoke(item, iv); true }
            addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                leftMargin = tx + gap / 2
                topMargin  = ty + gap / 2
            })
        }

        maxScrollX = (maxX - width).toFloat().coerceAtLeast(0f)
        maxScrollY = (maxY - height).toFloat().coerceAtLeast(0f)
        scrollX_ = maxScrollX / 2f
        scrollY_ = maxScrollY / 2f
        applyScroll()
        // Defer 3D until after layout pass so child.left/top are set
        if (enable3d) post { apply3d() }
    }

    // ── Hex position ──────────────────────────────────────────────────────────

    // Group of 13: positions 0-6 → even row (7 icons), 7-12 → odd row (6 icons, offset)
    // 7 per row keeps the overall grid roughly square; auto icon = width/8 gives slight overflow
    private fun hexTile(idx: Int, tileSize: Int, rowH: Int): Pair<Int, Int> {
        val group = idx / 13; val pos = idx % 13
        val row = group * 2 + if (pos < 7) 0 else 1
        val col = if (pos < 7) pos else pos - 7
        val x   = if (row % 2 == 0) col * tileSize else col * tileSize + tileSize / 2
        return x to row * rowH
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
            val scale = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.55f
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

    companion object { private const val GAP_DP = 8 }
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
