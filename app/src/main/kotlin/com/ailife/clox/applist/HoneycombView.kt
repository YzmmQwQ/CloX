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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hex-arranged icon grid with 2D pan (OverScroller inertia).
 * Even rows: 4 icons. Odd rows: 3 icons offset by half icon width.
 * No labels — icons only.
 */
class HoneycombView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onAppClick: ((AppItem) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var enable3d = false
    var iconSizeDp = 0   // 0 = auto (width / 4)

    // FrameLayout handles child layout correctly; children positioned via leftMargin/topMargin
    private val content = FrameLayout(ctx)

    private val scroller = OverScroller(ctx)
    private var velTracker: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var scrollX_ = 0f; private var scrollY_ = 0f
    private var maxScrollX = 0f; private var maxScrollY = 0f

    init { addView(content) }

    // ── Public API ────────────────────────────────────────────────────────────

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
        val iconPx = if (iconSizeDp > 0) dp(iconSizeDp) else width / 4
        if (iconPx <= 0) return

        val rowH = (iconPx * 0.866f).toInt()

        var maxX = 0; var maxY = 0
        for ((idx, item) in items.withIndex()) {
            val (x, y) = hexPosition(idx, iconPx, rowH)
            maxX = maxX.coerceAtLeast(x + iconPx)
            maxY = maxY.coerceAtLeast(y + iconPx)

            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(item.icon.toCircle(iconPx))
            }
            iv.setOnClickListener { onAppClick?.invoke(item) }
            iv.setOnLongClickListener { onAppLongPress?.invoke(item, iv); true }
            // FrameLayout.LayoutParams positions children — layout() is called properly
            content.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx).apply {
                leftMargin = x; topMargin = y
            })
        }

        maxScrollX = (maxX - width).coerceAtLeast(0).toFloat()
        maxScrollY = (maxY - height).coerceAtLeast(0).toFloat()
        applyScroll()
        if (enable3d) apply3d()
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
                val vx = -velTracker!!.xVelocity
                val vy = -velTracker!!.yVelocity
                scroller.fling(
                    scrollX_.toInt(), scrollY_.toInt(),
                    vx.toInt(), vy.toInt(),
                    0, maxScrollX.toInt(),
                    0, maxScrollY.toInt()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Group of 7: items 0-3 on even row (4 cols), items 4-6 on odd row (3 cols, offset)
    private fun hexPosition(idx: Int, iconPx: Int, rowH: Int): Pair<Int, Int> {
        val group = idx / 7; val pos = idx % 7
        val row = group * 2 + if (pos < 4) 0 else 1
        val col = if (pos < 4) pos else pos - 4
        val x = if (row % 2 == 0) col * iconPx else col * iconPx + iconPx / 2
        return Pair(x, row * rowH)
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
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
