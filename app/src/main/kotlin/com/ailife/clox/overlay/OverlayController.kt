package com.ailife.clox.overlay

import android.view.View
import android.view.Window
import android.widget.ImageView
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import com.ailife.clox.overlay.widgets.WidgetsOverlay

class OverlayController(
    private val window: Window,
    private val quickSettings: QuickSettingsOverlay,
    private val widgets: WidgetsOverlay,
    private val notifications: NotificationOverlay
) {

    private var activeOverlay: View? = null

    fun onSwipe(direction: SwipeDirection) {
        val target: View = when (direction) {
            SwipeDirection.DOWN -> quickSettings
            SwipeDirection.UP -> widgets
            SwipeDirection.RIGHT -> notifications
            SwipeDirection.LEFT -> return  // handled by MainActivity for AppListFragment
        }
        openOverlay(target)
    }

    fun openOverlay(overlay: View) {
        if (activeOverlay == overlay) return
        activeOverlay?.let { dismiss(it, animate = false) }
        overlay.visibility = View.VISIBLE
        blurAndReveal(overlay)
        activeOverlay = overlay
    }

    fun dismissActive() {
        activeOverlay?.let { dismiss(it) }
    }

    fun hasActiveOverlay(): Boolean = activeOverlay != null

    private fun blurAndReveal(overlay: View) {
        val bgImage = overlay.findViewWithTag<ImageView>("blur_bg")
        if (bgImage != null) {
            BlurEngine.applyBlur(overlay.context, window, overlay, bgImage) {
                animateIn(overlay)
            }
        } else {
            animateIn(overlay)
        }
        // CocosManager.onPause() — enable when Axmol submodule is added
    }

    private fun dismiss(overlay: View, animate: Boolean = true) {
        val bgImage = overlay.findViewWithTag<ImageView>("blur_bg")
        bgImage?.let { BlurEngine.clearBlur(it) }
        if (animate) {
            animateOut(overlay) {
                overlay.visibility = View.GONE
                if (activeOverlay == overlay) activeOverlay = null
                // CocosManager.onResume() — enable when Axmol submodule is added
            }
        } else {
            overlay.visibility = View.GONE
            if (activeOverlay == overlay) activeOverlay = null
            // CocosManager.onResume() — enable when Axmol submodule is added
        }
    }

    private fun animateIn(overlay: View) {
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
    }

    private fun animateOut(overlay: View, onEnd: () -> Unit) {
        overlay.animate().alpha(0f).setDuration(150).withEndAction(onEnd).start()
    }

}
