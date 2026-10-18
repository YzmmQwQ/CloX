package com.yzmm.clox

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Path B — real, system-driven window→icon morph on the swipe-home gesture.
 *
 * Creates a translucent, top-Z [SurfaceView] sized to the app icon's on-screen rect, draws the
 * app icon into it, and hands its [android.view.SurfaceControl] to the system via the
 * [GestureNavContract]. The system then morphs the CLOSING app window directly into this surface.
 * Mirrors AOSP Launcher3's FloatingSurfaceView.
 *
 * Clean handoff (avoids the double-icon + lingering-icon bugs):
 *  - the REAL app-list icon [realIcon] is hidden while our surface morphs (so only one icon shows);
 *  - after the launcher animation settles we restore the real icon AND remove our surface, so the
 *    handed surface never lingers over the real icon. Removal is scheduled on a main-thread [Handler]
 *    (not the SurfaceView's own handler, whose callbacks get dropped when the view detaches — the
 *    cause of the old ~2s linger).
 */
@RequiresApi(Build.VERSION_CODES.R)
class AppCloseIconSurface private constructor(
    context: Context,
    private val parent: FrameLayout,
    private val icon: Drawable,
    bounds: Rect,
    private val contract: GestureNavContract,
    private val realIcon: View?
) : SurfaceHolder.Callback2 {

    private val surfaceView = SurfaceView(context)
    private var sent = false
    private var removed = false

    private val main = Handler(Looper.getMainLooper())
    private val removeRunnable = Runnable { remove() }

    init {
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.holder.addCallback(this)

        // Hide the real icon so it doesn't show through / double up under the morphing surface.
        realIcon?.visibility = View.INVISIBLE

        // Position the surface at the icon rect in parent-local coordinates.
        val loc = IntArray(2)
        parent.getLocationOnScreen(loc)
        val lp = FrameLayout.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        ).apply {
            leftMargin = bounds.left - loc[0]
            topMargin = bounds.top - loc[1]
        }
        parent.addView(surfaceView, lp)

        // Schedule the cleanup HERE, at morph start — GUARANTEED to run regardless of whether
        // surfaceCreated() ever fires. If we scheduled it in send() (surfaceCreated) instead, quick
        // successive returns where the surface is never created would leave the surface up and the
        // real icon INVISIBLE forever (then scrolling recycles that hidden view onto another app).
        main.postDelayed(removeRunnable, REMOVE_DELAY_MS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawIcon(holder)
        send()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawIcon(holder)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) = drawIcon(holder)

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private fun drawIcon(holder: SurfaceHolder) {
        val c = holder.lockHardwareCanvas() ?: return
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            icon.setBounds(0, 0, surfaceView.width, surfaceView.height)
            icon.draw(c)
        } finally {
            runCatching { holder.unlockCanvasAndPost(c) }
        }
    }

    private fun send() {
        if (sent || removed) return
        sent = true
        val loc = IntArray(2)
        surfaceView.getLocationOnScreen(loc)
        val pos = RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + surfaceView.width).toFloat(), (loc[1] + surfaceView.height).toFloat()
        )
        Log.d(TAG, "handoff icon surface -> system pos=$pos")
        contract.sendEndPosition(pos, surfaceView.surfaceControl)
    }

    fun remove() {
        if (removed) return
        removed = true
        main.removeCallbacks(removeRunnable)
        realIcon?.visibility = View.VISIBLE
        runCatching { parent.removeView(surfaceView) }
    }

    companion object {
        private const val TAG = "CloX/Handoff"
        private const val REMOVE_DELAY_MS = 500L

        @RequiresApi(Build.VERSION_CODES.R)
        fun show(
            context: Context,
            parent: FrameLayout,
            icon: Drawable,
            bounds: Rect,
            contract: GestureNavContract,
            realIcon: View?
        ): AppCloseIconSurface = AppCloseIconSurface(context, parent, icon, bounds, contract, realIcon)
    }
}
