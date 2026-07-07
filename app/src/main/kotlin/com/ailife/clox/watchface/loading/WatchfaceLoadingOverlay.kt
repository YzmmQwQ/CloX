package com.ailife.clox.watchface.loading

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.ailife.clox.R

/**
 * Full-screen placeholder shown over the Axmol surface while a watchface
 * loads: the face's preview image stretched to the canvas, a scrim, and the
 * animated app-icon indicator with a "Loading…" label.
 *
 * show() on load start, hide() on the Lua "wf_loaded" event. A safety timeout
 * mirrors the renderer watchdog (2 rounds) so a dead renderer never leaves the
 * placeholder covering the watchdog's recovery dialog context forever.
 */
class WatchfaceLoadingOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val previewView: ImageView
    private val sticksView: ImageView

    private val safetyHide = Runnable { hide() }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_watchface_loading, this, true)
        previewView = findViewById(R.id.wfLoadingPreview)
        sticksView = findViewById(R.id.wfLoadingSticks)
        visibility = GONE
        // Never intercept input — system gestures stay with GestureInterceptorLayout.
        isClickable = false
        isFocusable = false
    }

    private fun sticksAnimatable() = sticksView.drawable as? android.graphics.drawable.Animatable

    /**
     * Show immediately with the preview (or black if none is available yet).
     * At FULL opacity, no fade-in: an entrance fade is driven by the main
     * thread, which stalls for hundreds of ms during the window handoff
     * (GLSurfaceView's surfaceChanged wait) — a half-played fade then sits on
     * screen as a stuck semi-transparent overlay. Only hide() fades; by then
     * the load is finished and the main thread is idle.
     */
    fun show(preview: Bitmap?) {
        removeCallbacks(safetyHide)
        animate().cancel()
        previewView.setImageBitmap(preview)
        alpha = 1f
        visibility = VISIBLE
        sticksAnimatable()?.start()
        postDelayed(safetyHide, SAFETY_TIMEOUT_MS)
    }

    /** Swap in a preview that finished decoding after show() (async zip read). */
    fun setPreview(preview: Bitmap?) {
        if (visibility == VISIBLE && preview != null) previewView.setImageBitmap(preview)
    }

    fun hide() {
        removeCallbacks(safetyHide)
        if (visibility != VISIBLE) return
        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction {
                visibility = GONE
                previewView.setImageBitmap(null)
                sticksAnimatable()?.stop()
            }
            .start()
    }

    companion object {
        private const val FADE_OUT_MS = 300L

        // Matches 2 × WatchfaceBridgeManager.WATCHDOG_MS — when the watchdog
        // declares the renderer dead, the placeholder gets out of the way.
        private const val SAFETY_TIMEOUT_MS = 30_000L

        // Material 3 emphasized easing
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
