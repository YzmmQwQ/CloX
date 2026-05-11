package com.ailife.clox.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.ailife.clox.cocos.LuaBridge
import kotlin.math.abs

/**
 * Root ViewGroup for all gesture routing.
 *
 * Two event paths — both handled identically:
 *  Path A: a child (RecyclerView) consumed ACTION_DOWN
 *          → onInterceptTouchEvent fires on MOVE, may steal the stream
 *  Path B: no child consumed ACTION_DOWN (Axmol SurfaceView is not clickable)
 *          → onTouchEvent owns the stream from DOWN onwards
 *
 * Direction is locked only when the primary axis is ≥1.5× the secondary,
 * preventing diagonal misfires between overlays and app-list.
 */
class GestureInterceptorLayout @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var appListOpen: Boolean = false
    var pagesBackendActive: Boolean = false

    var onSwipeStart: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeUpdate: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeEnd: ((SwipeDirection, Float, Float, Float, Float) -> Unit)? = null
    var doubleTapListener: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val directionRatio = 1.5f

    private var velocityTracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var dragDirection: SwipeDirection? = null
    private var lastTapTime = 0L

    // ── Path A: child consumed DOWN, we intercept on MOVE ────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDown(ev)
                return false          // never intercept DOWN — children get first look
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!isDragging) {
                    val dir = detectDirection(ev) ?: return false
                    if (shouldBlock(dir)) return false   // let child scroll vertically
                    commitDirection(dir)
                    onSwipeStart?.invoke(dir, startX, startY)
                    // return true: steals the stream from the child (child gets CANCEL)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) handleTap(ev)
                reset()
            }
        }
        return isDragging
    }

    // ── Path B: no child consumed DOWN, we own the stream from DOWN ──────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDown(ev)
                return true           // must return true to keep receiving MOVE/UP
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dir = detectDirection(ev)
                    if (dir != null && !shouldBlock(dir)) {
                        commitDirection(dir)
                        onSwipeStart?.invoke(dir, startX, startY)
                    }
                }
                val dir = dragDirection ?: return true
                onSwipeUpdate?.invoke(dir, ev.x - startX, ev.y - startY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    handleTap(ev)
                } else {
                    val dir = dragDirection
                    if (dir != null) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        onSwipeEnd?.invoke(
                            dir,
                            ev.x - startX, ev.y - startY,
                            velocityTracker?.xVelocity ?: 0f,
                            velocityTracker?.yVelocity ?: 0f
                        )
                    }
                }
                reset()
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun recordDown(ev: MotionEvent) {
        startX = ev.x; startY = ev.y
        isDragging = false; dragDirection = null
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
    }

    /** Returns null while gesture is still ambiguous. */
    private fun detectDirection(ev: MotionEvent): SwipeDirection? {
        val dx = ev.x - startX
        val dy = ev.y - startY
        val ax = abs(dx); val ay = abs(dy)
        if (ax < touchSlop && ay < touchSlop) return null
        if (ax < ay * directionRatio && ay < ax * directionRatio) return null  // diagonal
        return when {
            ax >= ay -> if (dx < 0) SwipeDirection.LEFT  else SwipeDirection.RIGHT
            else     -> if (dy < 0) SwipeDirection.UP    else SwipeDirection.DOWN
        }
    }

    /** True when the direction should be passed through to a child (e.g. vertical list scroll). */
    private fun shouldBlock(dir: SwipeDirection) =
        appListOpen && (
            dir == SwipeDirection.UP || dir == SwipeDirection.DOWN ||
            // Pages mode: let horizontal swipes reach the ViewPager2
            (pagesBackendActive && (dir == SwipeDirection.LEFT || dir == SwipeDirection.RIGHT))
        )

    private fun commitDirection(dir: SwipeDirection) {
        dragDirection = dir
        isDragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun handleTap(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300L) doubleTapListener?.invoke()
            lastTapTime = now
            LuaBridge.passTouch(ev.x, ev.y, ev.action)
        }
    }

    private fun reset() {
        isDragging = false; dragDirection = null
        velocityTracker?.recycle(); velocityTracker = null
    }
}
