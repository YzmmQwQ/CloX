package com.yzmm.clox.overlay.global

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.yzmm.clox.overlay.quicksettings.QuickSettingsOverlay

/**
 * The ONE draw-over-apps window for the global QS. Thin top "strip" when collapsed (status bar /
 * swipe handle); resizes to full-screen when dragged/opened. A single window owns the whole
 * DOWN→MOVE→UP gesture so velocity/fling work and there is no cross-window touch hand-off.
 *
 * The panel height is read LIVE from the QS view each frame (its tile grid lays out deferred, so a
 * one-shot measure is wrong) — this is what makes the panel fully hide on close and the thresholds
 * correct.
 */
class GlobalOverlayView(
    context: Context,
    private val host: Host
) : FrameLayout(context) {

    interface Host {
        fun mode(): String
        fun createQs(): QuickSettingsOverlay
        fun expandWindow()
        fun collapseWindow()
        fun setInteractive(on: Boolean)
        fun onOpened()
        fun onDismissed()
        fun swipeRevealMs(): Long
    }

    val statusBar = GlobalStatusBarView(context)
    private val scrim = View(context).apply {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        alpha = 0f
        isClickable = false
        setOnClickListener { dismiss() }
    }
    private var qs: QuickSettingsOverlay? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var vt: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartRawY = 0f
    private var dragging = false
    private var closeDrag = false
    private var expanded = false
    private var dismissing = false
    private var statusRevealed = false
    private var everFocused = false

    init {
        isFocusableInTouchMode = true
        addView(scrim)
        // Fill the strip so the visible bar grows with the detection-area setting.
        addView(statusBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP))
    }

    fun isExpanded(): Boolean = expanded

    fun refreshCollapsedContent() {
        scrim.isClickable = false
        statusBar.visibility = if (host.mode() == GlobalOverlayPrefs.SB_SHOW) VISIBLE else GONE
        statusRevealed = false
    }

    /** Live panel height (its content lays out deferred; falls back to full screen until then). */
    private fun panelH(): Float {
        val ph = qs?.height ?: 0
        return if (ph > 0) ph.toFloat() else resources.displayMetrics.heightPixels.toFloat()
    }

    private fun attachQs(): QuickSettingsOverlay {
        var panel = qs
        if (panel == null) {
            panel = host.createQs()
            addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
            qs = panel
            panel.onPanelOpened()
        }
        return panel
    }

    private fun detachQs() {
        qs?.let { it.onPanelClosed(); removeView(it) }
        qs = null
    }

    /** Programmatic open (tap / auto-replace): expand then slide the panel down. */
    fun openFull() {
        if (expanded) return
        dismissing = false
        statusBar.visibility = GONE
        host.expandWindow()
        val panel = attachQs()
        scrim.alpha = 0f
        scrim.isClickable = true
        panel.post {
            val ph = panelH()
            panel.translationY = -ph
            panel.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            scrim.animate().alpha(1f).setDuration(200).start()
        }
        host.setInteractive(true)
        requestFocus()
        expanded = true
        host.onOpened()
        Log.d(TAG, "openFull")
    }

    fun dismiss() {
        if (!expanded || dismissing) return
        dismissing = true
        val panel = qs
        scrim.animate().alpha(0f).setDuration(160).start()
        if (panel != null) {
            panel.animate().translationY(-panelH()).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { finishCollapse() }.start()
        } else finishCollapse()
        Log.d(TAG, "dismiss")
    }

    private fun finishCollapse() {
        detachQs()
        scrim.isClickable = false
        expanded = false; dragging = false; closeDrag = false; dismissing = false
        host.collapseWindow()
        refreshCollapsedContent()
        host.onDismissed()
    }

    // When open, the QS content consumes touches; intercept a clear upward swipe so we can
    // drive the close drag from the parent (children get ACTION_CANCEL, we take over).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!expanded) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY; dragging = false; closeDrag = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.rawY - downRawY
                val dx = ev.rawX - downRawX
                if (!dragging && dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true; closeDrag = true; dragStartRawY = ev.rawY
                    Log.d(TAG, "intercept close-swipe")
                    return true
                }
            }
        }
        return false
    }

    // ── touch ──────────────────────────────────────────────────────────────────
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY; dragging = false
                vt = VelocityTracker.obtain(); vt?.addMovement(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.rawY - downRawY
                val dx = ev.rawX - downRawX
                if (!dragging) {
                    if (expanded) {
                        if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                            dragging = true; closeDrag = true; dragStartRawY = ev.rawY
                        }
                    } else if (dy > slop && dy > Math.abs(dx)) {
                        if (host.mode() == GlobalOverlayPrefs.SB_SWIPE && !statusRevealed) {
                            revealStatusTemporarily()
                            downRawY = Float.MAX_VALUE   // swallow the rest of this gesture
                        } else {
                            beginExpandDrag(); dragging = true; closeDrag = false
                        }
                    }
                } else {
                    val ph = panelH()
                    val ty = if (closeDrag) (ev.rawY - dragStartRawY).coerceIn(-ph, 0f)  // from open(0)
                             else (ev.rawY - ph).coerceIn(-ph, 0f)                        // bottom edge follows finger
                    qs?.translationY = ty
                    scrim.alpha = (1f + ty / ph).coerceIn(0f, 1f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                    settle(vt?.yVelocity ?: 0f)
                }
                // A tap on the strip does nothing — only a swipe opens QS.
                vt?.recycle(); vt = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun beginExpandDrag() {
        statusBar.visibility = GONE
        host.expandWindow()
        val panel = attachQs()
        panel.translationY = -panelH()
        scrim.alpha = 0f
        Log.d(TAG, "beginExpandDrag")
    }

    private fun settle(velocityY: Float) {
        val panel = qs
        val ph = panelH()
        val cur = panel?.translationY ?: -ph
        val open = velocityY > FLING || (velocityY > -FLING && cur > -ph / 2f)
        val remaining = if (open) -cur else (ph + cur)
        val dur = (Math.abs(remaining) / Math.abs(velocityY).coerceAtLeast(1f) * 1000f).toLong().coerceIn(120L, 320L)
        Log.d(TAG, "settle v=$velocityY cur=$cur ph=$ph open=$open dur=$dur")
        if (open) {
            panel?.animate()?.translationY(0f)?.setDuration(dur)?.setInterpolator(DecelerateInterpolator())?.start()
            scrim.animate().alpha(1f).setDuration(dur).start()
            scrim.isClickable = true
            host.setInteractive(true)
            requestFocus()
            expanded = true; dragging = false; closeDrag = false
            host.onOpened()
        } else {
            dismissing = true
            panel?.animate()?.translationY(-ph)?.setDuration(dur)?.setInterpolator(DecelerateInterpolator())
                ?.withEndAction { finishCollapse() }?.start() ?: finishCollapse()
            scrim.animate().alpha(0f).setDuration(dur).start()
        }
    }

    private fun revealStatusTemporarily() {
        statusRevealed = true
        statusBar.visibility = VISIBLE
        postDelayed({
            if (!expanded && host.mode() == GlobalOverlayPrefs.SB_SWIPE) {
                statusBar.visibility = GONE; statusRevealed = false
            }
        }, host.swipeRevealMs())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && expanded) {
            dismiss(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) everFocused = true
        else if (expanded && everFocused && !dismissing) dismiss()
    }

    companion object {
        private const val TAG = "GlobalOverlayView"
        private const val FLING = 900f   // px/s
    }
}
