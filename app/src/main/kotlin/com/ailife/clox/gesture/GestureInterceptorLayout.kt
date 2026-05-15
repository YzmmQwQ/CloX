package com.ailife.clox.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
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
    /** Returns true when any QS/overlay panel is open — horizontal swipes should pass to VP2 */
    var qsOverlayActive: (() -> Boolean)? = null
    /** Returns true when ViewPager2 can scroll right (page > 0) — passes right swipe to child. */
    var pagesCanScrollRight: (() -> Boolean)? = null
    /** Returns true while a Pages drag is in progress — blocks all swipe gestures. */
    var pagesBackendDragActive: (() -> Boolean)? = null

    var onSwipeStart: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeUpdate: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeEnd: ((SwipeDirection, Float, Float, Float, Float) -> Unit)? = null
    var doubleTapListener: (() -> Unit)? = null
    var longPressListener: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val directionRatio = 1.5f

    private var velocityTracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var dragDirection: SwipeDirection? = null
    private var lastTapTime = 0L

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

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
        scheduleLongPressGesture()
    }

    private fun scheduleLongPressGesture() {
        cancelLongPressGesture()
        if (longPressListener == null) return
        val r = Runnable {
            if (!isDragging) {
                performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                longPressListener?.invoke()
            }
        }
        longPressRunnable = r
        longPressHandler.postDelayed(r, longPressTimeout)
    }

    private fun cancelLongPressGesture() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
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

    /** True when the direction should be passed through to a child. */
    private fun shouldBlock(dir: SwipeDirection): Boolean {
        // While dragging an app icon, block all swipes so the drag owns the touch stream
        if (pagesBackendActive && pagesBackendDragActive?.invoke() == true) return true
        // When QS (or any overlay) is open, let VP2 handle horizontal page swipes
        if (qsOverlayActive?.invoke() == true &&
            (dir == SwipeDirection.LEFT || dir == SwipeDirection.RIGHT)) return true
        return appListOpen && (
            dir == SwipeDirection.UP || dir == SwipeDirection.DOWN ||
            // Pages: always pass LEFT to ViewPager2 (go to next page)
            (pagesBackendActive && dir == SwipeDirection.LEFT) ||
            // Pages: pass RIGHT only when there's a page to go back to; at page 0 let gesture system close the list
            (pagesBackendActive && dir == SwipeDirection.RIGHT && pagesCanScrollRight?.invoke() == true)
        )
    }

    private fun commitDirection(dir: SwipeDirection) {
        cancelLongPressGesture()
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
        cancelLongPressGesture()
        isDragging = false; dragDirection = null
        velocityTracker?.recycle(); velocityTracker = null
    }
}
