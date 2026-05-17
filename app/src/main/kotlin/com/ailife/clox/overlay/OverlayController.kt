package com.ailife.clox.overlay

import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.preference.PreferenceManager
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import com.ailife.clox.overlay.widgets.WidgetsOverlay
import com.ailife.clox.qs.QsPrefs

class OverlayController(
    private val window: Window,
    private val quickSettings: QuickSettingsOverlay,
    private val widgets: WidgetsOverlay,
    private val notifications: NotificationOverlay,
    private val scrimView: View,
    private val blurLayer: ImageView   // static layer in activity_main, never animates
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

        // Capture watchface into static layer; updateDrag drives progressive blur radius.
        // onDone fires after PixelCopy completes — if a fast swipe already committed the
        // drag before the bitmap was ready, apply full blur now.
        BlurEngine.applyBlur(overlay.context, window, overlay, blurLayer) {
            if (activeOverlay != null && !isDragging && !isCollapsing) {
                BlurEngine.updateProgress(blurLayer.context, blurLayer, 1f)
            }
        }

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
        scrimView.alpha = progress * maxScrimAlpha()
        BlurEngine.updateProgress(scrimView.context, blurLayer, progress)
    }

    fun commitDrag() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection
        isDragging = false
        // Start animations immediately — no heavy work before this
        overlay.animate().translationX(0f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                when (direction) {
                    SwipeDirection.DOWN  -> (quickSettings as? QuickSettingsOverlay)?.onPanelOpened()
                    SwipeDirection.RIGHT -> (notifications as? com.ailife.clox.overlay.notifications.NotificationOverlay)?.onPanelOpened()
                    else -> Unit
                }
            }.start()
        BlurEngine.updateProgress(scrimView.context, blurLayer, 1f)
        scrimView.animate().alpha(maxScrimAlpha()).setDuration(200).start()
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
        BlurEngine.clearBlur(window, blurLayer)
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
        scrimView.alpha = progress * maxScrimAlpha()
        BlurEngine.updateProgress(scrimView.context, blurLayer, progress)
    }

    fun commitCollapse() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection ?: return
        isCollapsing = false
        if (direction == SwipeDirection.DOWN) {
            (quickSettings as? QuickSettingsOverlay)?.onPanelClosed()
        }
        if (overlay === widgets) widgets.onPanelClosed()
        activeOverlay = null
        activeDirection = null

        val (endX, endY) = exitTranslation(direction)
        overlay.animate().translationX(endX).translationY(endY).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { overlay.visibility = View.GONE }.start()
        BlurEngine.clearBlur(window, blurLayer)
        scrimView.animate().alpha(0f).setDuration(180)
            .withEndAction { scrimView.visibility = View.GONE }.start()
    }

    fun cancelCollapse() {
        isCollapsing = false
        val overlay = activeOverlay ?: return
        overlay.animate().translationX(0f).translationY(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
        BlurEngine.updateProgress(scrimView.context, blurLayer, 1f)
        scrimView.animate().alpha(maxScrimAlpha()).setDuration(180).start()
    }

    // ── Instant dismiss (back button, double-tap) ────────────────────────────

    fun dismissActive() {
        val overlay = activeOverlay ?: return
        val direction = activeDirection
        overlay.animate().cancel()
        isDragging = false
        isCollapsing = false
        (quickSettings as? QuickSettingsOverlay)?.onPanelClosed()
        if (overlay === widgets) widgets.onPanelClosed()
        activeOverlay = null
        activeDirection = null

        if (direction != null) {
            val (endX, endY) = exitTranslation(direction)
            overlay.animate().translationX(endX).translationY(endY).setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { overlay.visibility = View.GONE }.start()
        } else {
            overlay.animate().alpha(0f).setDuration(150)
                .withEndAction { overlay.visibility = View.GONE; overlay.alpha = 1f }.start()
        }

        BlurEngine.clearBlur(window, blurLayer)
        scrimView.animate().cancel()
        scrimView.animate().alpha(0f).setDuration(200)
            .withEndAction { scrimView.visibility = View.GONE }.start()
    }

    fun hasActiveOverlay(): Boolean = activeOverlay != null

    /** True only when the QS panel (with VP2) is the current active overlay. */
    fun isQsActive(): Boolean = activeOverlay === quickSettings

    /** True only when the notification panel is the current active overlay. */
    fun isNotificationActive(): Boolean = activeOverlay === notifications

    /** True only when the widget drawer is the current active overlay. */
    fun isWidgetActive(): Boolean = activeOverlay === widgets

    /** True if `direction` is the correct swipe to dismiss the current overlay. */
    fun isDismissDirection(direction: SwipeDirection): Boolean = when (activeDirection) {
        SwipeDirection.DOWN  -> direction == SwipeDirection.UP
        SwipeDirection.UP    -> direction == SwipeDirection.DOWN
        SwipeDirection.RIGHT -> direction == SwipeDirection.LEFT
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun overlayFor(direction: SwipeDirection): View? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(quickSettings.context)
        if (direction == SwipeDirection.DOWN && !prefs.getBoolean(QsPrefs.QS_ENABLED, true)) return null
        return when (direction) {
            SwipeDirection.DOWN  -> quickSettings
            SwipeDirection.UP    -> widgets
            SwipeDirection.RIGHT -> notifications
            else -> null
        }
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
        BlurEngine.clearBlur(window, blurLayer)
        scrimView.alpha = 0f
        scrimView.visibility = View.GONE
    }

    // When blur is off, scrim compensates with stronger darkening
    private fun maxScrimAlpha(): Float {
        val blurOff = BlurEngine.getMode(scrimView.context) == BlurEngine.Mode.DISABLED
        return if (blurOff) 0.55f else 0.35f
    }
}
