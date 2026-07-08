package com.ailife.clox.watchface.loading

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.ailife.clox.R
import com.ailife.clox.watchface.WatchfaceConfigPrefs

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
        // The preview covers only the canvas square; the root keeps the rest of
        // the window opaque over the stale GL frame.
        setBackgroundResource(R.color.clox_bg)
        visibility = GONE
        // Never intercept input — system gestures stay with GestureInterceptorLayout.
        isClickable = false
        isFocusable = false
    }

    // Face path of the pending load — keys the per-face alignment config.
    private var canvasPath: String? = null

    /**
     * The preview must cover exactly the watchface render canvas — the
     * face's aligned square (WatchfaceConfigPrefs.canvasRect), not the full
     * window: full-bleed stretched the square face over the whole portrait
     * screen, so the placeholder didn't match what loads under it.
     */
    private fun applyCanvasBounds() {
        // Size the rect off the WINDOW bounds, not this view's laid-out size or
        // displayMetrics. Lua renders the face against director:getWinSize() —
        // the full GL surface (the whole window). displayMetrics is reduced by
        // the navigation bar (480x458 vs the 480x480 window on this ROM), so
        // using it drew the preview ~one nav-bar smaller and offset. The window
        // bounds equal the GL surface in every case, and are available before
        // this view's first layout pass — which show() runs before on the
        // fresh-engine and selector-handoff paths.
        val (w, h) = windowSize()
        if (w == 0 || h == 0) return
        val r = WatchfaceConfigPrefs.canvasRect(context, canvasPath, w, h)
        val dm = resources.displayMetrics
        android.util.Log.i("WFLoadingBounds",
            "view=${width}x${height} dm=${dm.widthPixels}x${dm.heightPixels} " +
            "win=${w}x${h} rect=$r")
        val lp = previewView.layoutParams as LayoutParams
        if (lp.width != r.width() || lp.height != r.height() ||
            lp.leftMargin != r.left || lp.topMargin != r.top) {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.width = r.width()
            lp.height = r.height()
            lp.leftMargin = r.left
            lp.topMargin = r.top
            previewView.layoutParams = lp
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCanvasBounds()
    }

    /** The window (GL surface) size in pixels — the reference Lua renders against. */
    private fun windowSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
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
    fun show(preview: Bitmap?, activePath: String?) {
        removeCallbacks(safetyHide)
        animate().cancel()
        canvasPath = activePath
        applyCanvasBounds()
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
