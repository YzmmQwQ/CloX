package com.ailife.clox

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Path B — real, system-driven window→icon morph on the swipe-home gesture.
 *
 * Creates a translucent, top-Z [SurfaceView] sized to the app icon's on-screen rect, draws the
 * app icon into it, and hands its [android.view.SurfaceControl] to the system via the
 * [GestureNavContract]. The system then morphs the CLOSING app window directly into this surface,
 * so no in-app follow-on animation is needed. Mirrors AOSP Launcher3's FloatingSurfaceView.
 *
 * The surface self-removes a short time after the handshake so the real app-list icon underneath
 * is revealed once the morph completes.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AppCloseIconSurface private constructor(
    context: Context,
    private val parent: FrameLayout,
    private val icon: Drawable,
    bounds: Rect,
    private val contract: GestureNavContract
) : SurfaceHolder.Callback2 {

    private val surfaceView = SurfaceView(context)
    private var sent = false

    private val removeRunnable = Runnable { remove() }

    init {
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.holder.addCallback(this)

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
        if (sent) return
        sent = true
        val loc = IntArray(2)
        surfaceView.getLocationOnScreen(loc)
        val pos = RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + surfaceView.width).toFloat(), (loc[1] + surfaceView.height).toFloat()
        )
        Log.d(TAG, "handoff icon surface -> system pos=$pos")
        contract.sendEndPosition(pos, surfaceView.surfaceControl)
        // Keep the surface alive through the morph, then drop it to reveal the real icon.
        surfaceView.postDelayed(removeRunnable, REMOVE_DELAY_MS)
    }

    fun remove() {
        surfaceView.removeCallbacks(removeRunnable)
        runCatching { parent.removeView(surfaceView) }
    }

    companion object {
        private const val TAG = "CloX/Handoff"
        private const val REMOVE_DELAY_MS = 900L

        @RequiresApi(Build.VERSION_CODES.R)
        fun show(
            context: Context,
            parent: FrameLayout,
            icon: Drawable,
            bounds: Rect,
            contract: GestureNavContract
        ): AppCloseIconSurface = AppCloseIconSurface(context, parent, icon, bounds, contract)
    }
}
