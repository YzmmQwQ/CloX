package com.ailife.clox.anim

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * CloX-owned home-return close morph. The real system app→home transition is an instant cut (the
 * default slide is neutralized by the CloXTransitions RRO), and CloX cannot own that transition —
 * so it fakes the close entirely in its own window: a top-Z [SurfaceView] draws the outgoing app's
 * task **snapshot** (real pixels, via [TaskSnapshotHelper] + the LSPosed perm hook) shrinking from
 * fullscreen into the launched icon rect, over the watchface, then removes itself.
 *
 * Modeled on [com.ailife.clox.AppCloseIconSurface] (top-Z translucent SurfaceView, main-thread
 * lifecycle + safety removal), but self-driven (we animate the shrink) rather than handing the
 * surface to the system.
 */
@RequiresApi(Build.VERSION_CODES.S)
class CloseMorphSurface private constructor(
    context: Context,
    private val parent: FrameLayout,
    private val snapshot: Bitmap,
    private val icon: Rect,
    private val win: Rect,
    private val speed: Float,
    private val onDone: () -> Unit,
) : SurfaceHolder.Callback2 {

    private val surfaceView = SurfaceView(context)
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val clip = Path()
    private var anim: ValueAnimator? = null
    private var removed = false
    private var started = false

    private val removeRunnable = Runnable { finish() }

    init {
        surfaceView.setZOrderOnTop(true)               // above the watchface GL layer and the window
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.holder.addCallback(this)
        parent.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Guaranteed cleanup even if surfaceCreated never fires or the anim is interrupted.
        main.postDelayed(removeRunnable, TIMEOUT_MS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        started = true
        drawFrame(holder, 1f)   // land at fullscreen before the first tick (no flash)
        startAnim(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private fun startAnim(holder: SurfaceHolder) {
        val dist = 1f   // full → icon
        val duration = (MORPH_MS * dist / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(80L)
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { drawFrame(holder, it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = finish()
            })
            start()
        }
    }

    /** Draw the snapshot at expansion [e] (1 = fullscreen, 0 = icon), rounded + fading into the icon. */
    private fun drawFrame(holder: SurfaceHolder, e: Float) {
        val c: Canvas = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { holder.lockHardwareCanvas() }.getOrNull() else null)
            ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val iconScale = (icon.width().toFloat() / win.width().coerceAtLeast(1)).coerceIn(0.02f, 1f)
            val s = lerp(iconScale, 1f, e)
            val cx = lerp(icon.exactCenterX(), win.exactCenterX(), e)
            val cy = lerp(icon.exactCenterY(), win.exactCenterY(), e)
            val w = win.width() * s
            val h = win.height() * s
            dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            val radius = (1f - e * e) * (icon.width() / 2f)          // icon-round → square as it grows
            paint.alpha = (if (e < 0.25f) (e / 0.25f).coerceIn(0f, 1f) else 1f).let { (it * 255).toInt() }
            clip.reset()
            clip.addRoundRect(dst, radius, radius, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            c.drawBitmap(snapshot, null, dst, paint)
            c.restore()
        } finally {
            runCatching { holder.unlockCanvasAndPost(c) }
        }
    }

    private fun finish() {
        if (removed) return
        removed = true
        main.removeCallbacks(removeRunnable)
        anim?.cancel(); anim = null
        runCatching { parent.removeView(surfaceView) }
        runCatching { onDone() }
    }

    companion object {
        private const val MORPH_MS = 260f
        private const val TIMEOUT_MS = 1200L

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        /**
         * Play a snapshot close morph shrinking [snapshot] from [win] (fullscreen) into [icon].
         * Returns the controller (for cancellation) or null if inputs are unusable.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        fun show(
            context: Context,
            parent: FrameLayout,
            snapshot: Bitmap,
            icon: Rect,
            win: Rect,
            speed: Float,
            onDone: () -> Unit,
        ): CloseMorphSurface? {
            if (snapshot.width <= 0 || snapshot.height <= 0 || icon.isEmpty || win.isEmpty) return null
            return CloseMorphSurface(context, parent, snapshot, icon, win, speed, onDone)
        }
    }

    /** Cancel an in-flight morph (e.g. the user reopened the app). */
    fun cancel() = finish()
}
