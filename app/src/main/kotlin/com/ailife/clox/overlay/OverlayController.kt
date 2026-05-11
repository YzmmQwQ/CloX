package com.ailife.clox.overlay

import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import com.ailife.clox.overlay.widgets.WidgetsOverlay

class OverlayController(
    private val window: Window,
    private val quickSettings: QuickSettingsOverlay,
    private val widgets: WidgetsOverlay,
    private val notifications: NotificationOverlay,
    private val scrimView: View
) {

    private var activeOverlay: View? = null
    private var activeDirection: SwipeDirection? = null

    var isDragging = false      // opening drag in progress
        private set
    var isCollapsing = false    // closing drag in progress
        private set

    private var screenW = 0f
    private var screenH = 0f

    // ── Open drag (finger pulls panel in) ───────────────────────────────────

    fun startDrag(direction: SwipeDirection, sw: Float, sh: Float) {
        if (activeOverlay != null) return
        val overlay = overlayFor(direction) ?: return

        screenW = sw; screenH = sh

        val bgImage = overlay.findViewWithTag<ImageView>("blur_bg")
        if (bgImage != null) BlurEngine.applyBlur(overlay.context, window, overlay, bgImage) {}

        overlay.translationX = 0f
        overlay.translationY = 0f
        when (direction) {
            SwipeDirection.DOWN  -> overlay.translationY = -sh
            SwipeDirection.UP    -> overlay.translationY = sh
            SwipeDirection.RIGHT -> overlay.translationX = -sw
            else -> return
        }
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        scrimView.alpha = 0f
        scrimView.visibility = View.VISIBLE

        activeOverlay = overlay
        activeDirection = direction
        isDragging = true
    }

    fun updateDrag(direction: SwipeDirection, dx: Float, dy: Float) {
        val overlay = activeOverlay ?: return
        if (direction != activeDirection || !isDragging) return

        val progress: Float = when (direction) {
            SwipeDirection.DOWN  -> {
                val t = (-screenH + dy).coerceIn(-screenH, 0f)
                overlay.translationY = t
                (screenH + t) / screenH
            }
            SwipeDirection.UP    -> {
                val t = (screenH + dy).coerceIn(0f, screenH)
                overlay.translationY = t
                (screenH - t) / screenH
            }
            SwipeDirection.RIGHT -> {
                val t = (-screenW + dx).coerceIn(-screenW, 0f)
                overlay.translationX = t
                (screenW + t) / screenW
            }
            else -> return
        }
        scrimView.alpha = progress * MAX_SCRIM_ALPHA
    }

    fun commitDrag() {
        val overlay = activeOverlay ?: return
        isDragging = false
        overlay.animate().translationX(0f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
        scrimView.animate().alpha(MAX_SCRIM_ALPHA).setDuration(200).start()
    }

    fun cancelDrag() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection ?: return
        isDragging = false
        activeOverlay = null
        activeDirection = null

        val (endX, endY) = exitTranslation(direction)
        overlay.animate().translationX(endX).translationY(endY).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { hideNow(overlay) }.start()
        scrimView.animate().alpha(0f).setDuration(180)
            .withEndAction { scrimView.visibility = View.GONE }.start()
    }

    // ── Close drag (finger pushes panel back out) ────────────────────────────

    fun startCollapse(direction: SwipeDirection): Boolean {
        val overlay = activeOverlay ?: return false
        if (isDragging || isCollapsing) return false
        if (!isDismissDirection(direction)) return false
        isCollapsing = true
        return true
    }

    fun updateCollapse(dx: Float, dy: Float) {
        val overlay = activeOverlay ?: return
        if (!isCollapsing) return

        val progress: Float = when (activeDirection) {
            SwipeDirection.DOWN  -> {
                val t = dy.coerceIn(-screenH, 0f)   // dy < 0 as finger moves up
                overlay.translationY = t
                (screenH + t) / screenH
            }
            SwipeDirection.UP    -> {
                val t = dy.coerceIn(0f, screenH)     // dy > 0 as finger moves down
                overlay.translationY = t
                (screenH - t) / screenH
            }
            SwipeDirection.RIGHT -> {
                val t = dx.coerceIn(-screenW, 0f)    // dx < 0 as finger moves left
                overlay.translationX = t
                (screenW + t) / screenW
            }
            else -> return
        }
        scrimView.alpha = progress * MAX_SCRIM_ALPHA
    }

    fun commitCollapse() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection ?: return
        isCollapsing = false
        activeOverlay = null
        activeDirection = null

        val bgImage = overlay.findViewWithTag<ImageView>("blur_bg")
        bgImage?.let { BlurEngine.clearBlur(it) }

        val (endX, endY) = exitTranslation(direction)
        overlay.animate().translationX(endX).translationY(endY).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { overlay.visibility = View.GONE }.start()
        scrimView.animate().alpha(0f).setDuration(180)
            .withEndAction { scrimView.visibility = View.GONE }.start()
    }

    fun cancelCollapse() {
        isCollapsing = false
        val overlay = activeOverlay ?: return
        overlay.animate().translationX(0f).translationY(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
        scrimView.animate().alpha(MAX_SCRIM_ALPHA).setDuration(180).start()
    }

    // ── Instant dismiss (back button, double-tap) ────────────────────────────

    fun dismissActive() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection
        overlay.animate().cancel()
        isDragging = false
        isCollapsing = false
        activeOverlay = null
        activeDirection = null

        val bgImage = overlay.findViewWithTag<ImageView>("blur_bg")
        bgImage?.let { BlurEngine.clearBlur(it) }

        if (direction != null) {
            val (endX, endY) = exitTranslation(direction)
            overlay.animate().translationX(endX).translationY(endY).setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { overlay.visibility = View.GONE }.start()
        } else {
            overlay.animate().alpha(0f).setDuration(150)
                .withEndAction { overlay.visibility = View.GONE; overlay.alpha = 1f }.start()
        }

        scrimView.animate().cancel()
        scrimView.animate().alpha(0f).setDuration(200)
            .withEndAction { scrimView.visibility = View.GONE }.start()
    }

    fun hasActiveOverlay(): Boolean = activeOverlay != null

    /** True if `direction` is the correct swipe to dismiss the current overlay. */
    fun isDismissDirection(direction: SwipeDirection): Boolean = when (activeDirection) {
        SwipeDirection.DOWN  -> direction == SwipeDirection.UP
        SwipeDirection.UP    -> direction == SwipeDirection.DOWN
        SwipeDirection.RIGHT -> direction == SwipeDirection.LEFT
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun overlayFor(direction: SwipeDirection): View? = when (direction) {
        SwipeDirection.DOWN  -> quickSettings
        SwipeDirection.UP    -> widgets
        SwipeDirection.RIGHT -> notifications
        else -> null
    }

    private fun exitTranslation(direction: SwipeDirection) = when (direction) {
        SwipeDirection.DOWN  -> 0f to -screenH
        SwipeDirection.UP    -> 0f to screenH
        SwipeDirection.RIGHT -> -screenW to 0f
        else -> 0f to 0f
    }

    private fun hideNow(overlay: View) {
        overlay.visibility = View.GONE
        overlay.translationX = 0f
        overlay.translationY = 0f
        scrimView.alpha = 0f
        scrimView.visibility = View.GONE
    }

    companion object {
        private const val MAX_SCRIM_ALPHA = 0.6f
    }
}
