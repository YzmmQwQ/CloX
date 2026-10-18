package com.yzmm.clox.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.yzmm.clox.cocos.LuaBridge
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
    var gestureLocked: Boolean = false
    var pagesBackendActive: Boolean = false
    /**
     * When true (Grid2D or Honeycomb mode), RIGHT-swipe to close the app list
     * only works from the left 1/3 of the screen. The right 2/3 passes through
     * to the child view so the user can pan/scroll instead.
     */
    var grid2dHoneycombActive: Boolean = false
    /** Returns true when any QS/overlay panel is open — horizontal swipes should pass to VP2 */
    var qsOverlayActive: (() -> Boolean)? = null
    /** Returns true when ViewPager2 can scroll right (page > 0) — passes right swipe to child. */
    var pagesCanScrollRight: (() -> Boolean)? = null
    /** Returns true while a Pages drag is in progress — blocks all swipe gestures. */
    var pagesBackendDragActive: (() -> Boolean)? = null

    /** Returns true when the notification panel is open — vertical + right swipes pass to child. */
    var notificationOverlayActive: (() -> Boolean)? = null

    /** Returns true when the notification panel was opened by a downward swipe from the top. */
    var notificationOpenedFromDown: (() -> Boolean)? = null

    /** Returns true when the notification list can still scroll downward (more content below). */
    var notificationListCanScrollDown: (() -> Boolean)? = null

    /**
     * Returns true when the widget drawer is open. Vertical + horizontal swipes pass to the
     * inner RecyclerView. The only exception: swipe DOWN while the list cannot scroll up
     * (i.e., the list is at the top / in overscroll), which collapses the overlay.
     */
    var widgetOverlayActive: (() -> Boolean)? = null
    /** Returns true when the widget RecyclerView can still scroll upward (not at the top). */
    var widgetListCanScrollUp: (() -> Boolean)? = null

    var onSwipeStart: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeUpdate: ((SwipeDirection, Float, Float) -> Unit)? = null
    var onSwipeEnd: ((SwipeDirection, Float, Float, Float, Float) -> Unit)? = null
    var doubleTapListener: (() -> Unit)? = null
    var longPressListener: (() -> Unit)? = null

    /**
     * Returns true when a double-tap action is actually configured. When armed,
     * two quick nearby taps fire the double-tap gesture (and the second tap is
     * never passed to Lua); when not armed, two quick taps are just two taps.
     * (AOD lock arms double tap implicitly — it is the unlock gesture.)
     */
    var doubleTapArmed: (() -> Boolean)? = null

    /**
     * Returns true when single taps should additionally be HELD for the
     * double-tap window before being passed to Lua, so the FIRST tap of a
     * double tap never reaches the watchface either. Costs ~300ms of tap
     * latency — user-controlled, since it makes tap-heavy faces (stopwatches)
     * sluggish; when off, the first tap of a double tap reaches the face.
     */
    var holdTapForDoubleTap: (() -> Boolean)? = null

    // RTOS mode 1: when true all gestures and touches are blocked (watchface only mode)
    var rtosActive: Boolean = false
    // RTOS mode 2: gestures work but long press must not fire haptic (interferes with password)
    var suppressLongPress: Boolean = false
    // Returns true when the RTOS fake QS overlay is visible — gesture interceptor stands down
    var rtosQsOverlayVisible: (() -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val doubleTapSlop = ViewConfiguration.get(ctx).scaledDoubleTapSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val directionRatio = 1.5f

    private var velocityTracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var dragDirection: SwipeDirection? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingLuaTap: Runnable? = null

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Every window touch passes through here (root interceptor). While the
    // user interacts — and shortly after, so release animations finish — the
    // GL face must render at full rate: gesture feedback, overlay open/close
    // animations and blur backdrop captures all composite against it, and the
    // idle 1 FPS throttle makes them stutter. Cheap enough to call per event.
    // Only needed while dynamic FPS is throttling; without it the face already
    // renders at its configured rate. Suppressed in the AOD dim state so a
    // touch on the (gesture-locked) dim face can't run 3 s of 60 FPS — a
    // touch that legitimately wakes the watch undims first (AodManager.onTouch
    // runs earlier in the activity's dispatch), flipping aodDimmed off before
    // this fires.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (com.yzmm.clox.cocos.RenderPolicy.dynamicFps &&
            !com.yzmm.clox.cocos.RenderPolicy.aodDimmed) {
            dev.axmol.lib.AxmolRenderer.boostFrames(3f)
        }
        return super.dispatchTouchEvent(ev)
    }

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
                    // Stand down when RTOS QS is visible — let the overlay handle swipes
                    if (gestureLocked || rtosActive || rtosQsOverlayVisible?.invoke() == true) return false
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
                if (!isDragging && !gestureLocked && !rtosActive &&
                    rtosQsOverlayVisible?.invoke() != true) {
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
        if (longPressListener == null || rtosActive) return
        // Only arm the watchface long-press when the touch STARTS on the bare watchface. If it
        // starts over the app list or an overlay, don't schedule it — otherwise closing that via
        // the system home gesture (which consumes the move events, so no drag is detected to
        // cancel the timer) lets the pending long-press fire on the now-visible watchface.
        if (appListOpen) return
        if (qsOverlayActive?.invoke() == true || notificationOverlayActive?.invoke() == true ||
            widgetOverlayActive?.invoke() == true) return
        val r = Runnable {
            if (suppressLongPress) return@Runnable   // RTOS Mode 2: no haptic or action
            val overlayOpen = qsOverlayActive?.invoke() == true ||
                              notificationOverlayActive?.invoke() == true ||
                              widgetOverlayActive?.invoke() == true
            val aodLocked = GestureLockManager.isAodLocked
            if (!isDragging && !appListOpen && !overlayOpen && !aodLocked) {
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
        // Notification panel: top-opened panels scroll down and collapse on UP at the bottom;
        // right-opened panels keep their original LEFT-to-close behavior.
        if (notificationOverlayActive?.invoke() == true) {
            if (notificationOpenedFromDown?.invoke() == true) {
                if (dir == SwipeDirection.UP &&
                    notificationListCanScrollDown?.invoke() == true) return true
                if (dir == SwipeDirection.DOWN ||
                    dir == SwipeDirection.LEFT ||
                    dir == SwipeDirection.RIGHT) return true
            } else if (dir == SwipeDirection.UP ||
                dir == SwipeDirection.DOWN ||
                dir == SwipeDirection.RIGHT) return true
        }
        // Widget drawer: pass all horizontal and upward swipes to the inner RecyclerView.
        // Downward swipe at the very top (overscroll) collapses the overlay — don't block it.
        if (widgetOverlayActive?.invoke() == true) {
            if (dir == SwipeDirection.LEFT || dir == SwipeDirection.RIGHT) return true
            if (dir == SwipeDirection.UP) return true
            if (dir == SwipeDirection.DOWN && widgetListCanScrollUp?.invoke() == true) return true
        }
        if (appListOpen) {
            // Up/down always go to child for scrolling
            if (dir == SwipeDirection.UP || dir == SwipeDirection.DOWN) return true
            // Pages: always pass LEFT to ViewPager2 (go to next page)
            if (pagesBackendActive && dir == SwipeDirection.LEFT) return true
            // Pages: pass RIGHT only when there's a page to go back to
            if (pagesBackendActive && dir == SwipeDirection.RIGHT && pagesCanScrollRight?.invoke() == true) return true
            // Grid2D/Honeycomb: RIGHT close only works from left 1/3; right 2/3 passes to child for panning
            if (grid2dHoneycombActive && dir == SwipeDirection.RIGHT && startX >= width / 3f) return true
        }
        return false
    }

    private fun commitDirection(dir: SwipeDirection) {
        cancelLongPressGesture()
        dragDirection = dir
        isDragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** True when the bare watchface is the front surface (no app list / overlay). */
    private fun watchfaceVisible(): Boolean = !appListOpen &&
        qsOverlayActive?.invoke() != true &&
        notificationOverlayActive?.invoke() != true &&
        widgetOverlayActive?.invoke() != true

    private fun handleTap(ev: MotionEvent) {
        if (ev.actionMasked != MotionEvent.ACTION_UP) return
        val now = System.currentTimeMillis()
        // AOD lock always arms double tap — it is the unlock gesture.
        val armed = GestureLockManager.isAodLocked || doubleTapArmed?.invoke() != false
        // A double tap needs proximity, not just timing: two taps on opposite
        // screen edges are two separate taps, no matter how quick.
        val dx = ev.x - lastTapX
        val dy = ev.y - lastTapY
        val isDoubleTap = armed && now - lastTapTime < doubleTapTimeout &&
            dx * dx + dy * dy <= (doubleTapSlop * doubleTapSlop).toFloat()
        if (isDoubleTap) {
            lastTapTime = 0L                     // a third tap starts a fresh sequence
            cancelPendingLuaTap()                // first tap must not reach the watchface
            // Double tap only executes on the bare watchface (or AOD-locked, to unlock).
            if (GestureLockManager.isAodLocked || watchfaceVisible()) {
                doubleTapListener?.invoke()
            }
            return
        }
        lastTapTime = now; lastTapX = ev.x; lastTapY = ev.y
        if (GestureLockManager.isAodLocked || rtosActive) return
        // Taps belong to the watchface only when it is the front surface — a tap
        // on app-list / overlay empty space must never fall through to Lua.
        if (!watchfaceVisible()) return
        if (armed && holdTapForDoubleTap?.invoke() == true) {
            // Hold the tap for the double-tap window; a second tap cancels it so
            // an intercepted double press never also fires the watchface tap.
            val x = ev.x; val y = ev.y; val action = ev.action
            val r = Runnable {
                pendingLuaTap = null
                LuaBridge.passTouch(x, y, action)
            }
            pendingLuaTap = r
            longPressHandler.postDelayed(r, doubleTapTimeout)
        } else {
            LuaBridge.passTouch(ev.x, ev.y, ev.action)
        }
    }

    private fun cancelPendingLuaTap() {
        pendingLuaTap?.let { longPressHandler.removeCallbacks(it) }
        pendingLuaTap = null
    }

    override fun onDetachedFromWindow() {
        cancelPendingLuaTap()
        cancelLongPressGesture()
        super.onDetachedFromWindow()
    }

    private fun reset() {
        cancelLongPressGesture()
        isDragging = false; dragDirection = null
        velocityTracker?.recycle(); velocityTracker = null
    }
}
