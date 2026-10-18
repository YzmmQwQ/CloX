package com.yzmm.clox.anim

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
import android.util.Log
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
 * Modeled on [com.yzmm.clox.AppCloseIconSurface] (top-Z translucent SurfaceView, main-thread
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
    private val taskId: Int,
    private val speed: Float,
    private val onDone: () -> Unit,
) : SurfaceHolder.Callback2 {

    /** Current expansion (1 = fullscreen, 0 = icon) and whether this morph is ticking — polled by
     *  its OWN blur/settle so each close animation is independent of every other morph. */
    @Volatile var expansion = 1f
        private set
    @Volatile var running = false
        private set

    private val surfaceView = SurfaceView(context)
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val clip = Path()
    private var anim: ValueAnimator? = null
    private var removed = false
    private var started = false

    private val removeRunnable = Runnable { finish() }

    // Flicker diagnostics: a monotonic clock from construction, plus per-stage frame counters, so the
    // logcat timeline shows exactly what was presented (and when) before the smooth morph begins.
    private val t0 = android.os.SystemClock.uptimeMillis()
    private fun dt() = android.os.SystemClock.uptimeMillis() - t0
    private var frameCount = 0
    private var firstDrawAt = -1L
    private var sawSurfaceChanged = false

    init {
        surfaceView.setZOrderOnTop(true)               // above the watchface GL layer and the window
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.holder.addCallback(this)
        parent.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Guaranteed cleanup even if surfaceCreated never fires or the anim is interrupted.
        main.postDelayed(removeRunnable, TIMEOUT_MS)
        Log.d(TAG, "init dt=${dt()} snapshot=${snapshot.width}x${snapshot.height} config=${snapshot.config} " +
            "hwBuffer=${runCatching { snapshot.hardwareBuffer != null }.getOrDefault(false)} " +
            "icon=$icon win=$win taskId=$taskId speed=$speed parentSize=${parent.width}x${parent.height} " +
            "zOrderOnTop=true format=TRANSLUCENT")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val valid = holder.surface?.isValid
        val frame = runCatching { holder.surfaceFrame }.getOrNull()
        // Deliberately do NOT draw or start the animation here. The surface's buffer geometry isn't
        // final until surfaceChanged (which always fires right after). Drawing in surfaceCreated meant
        // frames #1–2 were presented, then surfaceChanged reallocated the buffer and dropped them — a
        // one-frame blank = the "always flickers once" glitch. We now begin only from surfaceChanged.
        Log.d(TAG, "surfaceCreated dt=${dt()} started=$started surfaceValid=$valid frame=$frame " +
            "sawSurfaceChanged=$sawSurfaceChanged (deferring first draw to surfaceChanged)")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        val first = !sawSurfaceChanged
        sawSurfaceChanged = true
        Log.d(TAG, "surfaceChanged dt=${dt()} format=$format size=${w}x$h started=$started " +
            "frameCount=$frameCount first=$first surfaceValid=${holder.surface?.isValid}")
        if (started) return
        started = true
        running = true
        // Surface geometry is now final — safe to draw. Land at fullscreen (e=1) in the same frame we
        // start the animator, so nothing is presented before the buffer is stable (no reconfigure flash).
        drawFrame(holder, 1f, "surfaceChanged-first")
        startAnim(holder)
    }
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceRedrawNeeded dt=${dt()} frameCount=$frameCount")
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed dt=${dt()} frameCount=$frameCount removed=$removed")
    }

    private fun startAnim(holder: SurfaceHolder) {
        val dist = 1f   // full → icon
        val duration = (MORPH_MS * dist / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(80L) * DEBUG_SLOWDOWN
        Log.d(TAG, "startAnim dt=${dt()} duration=$duration speed=$speed slowdown=${DEBUG_SLOWDOWN}x")
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { drawFrame(holder, it.animatedValue as Float, "anim") }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) {
                    Log.d(TAG, "anim onStart dt=${dt()}")
                }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    Log.d(TAG, "anim onEnd dt=${dt()} frameCount=$frameCount")
                    finish()
                }
            })
            start()
        }
    }

    /** Draw the snapshot at expansion [e] (1 = fullscreen, 0 = icon), rounded + fading into the icon. */
    private fun drawFrame(holder: SurfaceHolder, e: Float, tag: String) {
        val hw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val c: Canvas = (if (hw)
            runCatching { holder.lockHardwareCanvas() }.getOrNull() else null)
            ?: runCatching { holder.lockCanvas() }.getOrNull()
            ?: run { Log.w(TAG, "drawFrame[$tag] dt=${dt()} e=$e LOCK FAILED (no canvas)"); return }
        val usedHw = hw && c.isHardwareAccelerated
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
            expansion = e
            frameCount++
            // Log the first few frames in full, then only every ~10th, to catch the start flash without
            // spamming. canvasSize reveals a surface sized differently from win (a reconfigure flash).
            if (frameCount <= 3 || frameCount % 10 == 0) {
                if (firstDrawAt < 0L) firstDrawAt = dt()
                Log.d(TAG, "drawFrame[$tag] dt=${dt()} #$frameCount e=%.3f canvas=%dx%d hw=%b dst=%.0f,%.0f-%.0f,%.0f alpha=%d radius=%.1f"
                    .format(e, c.width, c.height, usedHw, dst.left, dst.top, dst.right, dst.bottom, paint.alpha, radius))
            }
        } finally {
            runCatching { holder.unlockCanvasAndPost(c) }
        }
    }

    private fun finish() {
        if (removed) return
        removed = true
        running = false
        main.removeCallbacks(removeRunnable)
        anim?.cancel(); anim = null
        Log.d(TAG, "finish dt=${dt()} frameCount=$frameCount removingView")
        runCatching { parent.removeView(surfaceView) }
        runCatching { onDone() }
    }

    companion object {
        private const val TAG = "CloX/Flicker"
        private const val MORPH_MS = 260f
        // DEBUG: slow the morph so the opening flicker is visible in a screen recording. Set to 1 for
        // production. TIMEOUT_MS is scaled with it so the safety-remove doesn't cut the slow morph short.
        private const val DEBUG_SLOWDOWN = 1L
        private const val TIMEOUT_MS = 1200L * DEBUG_SLOWDOWN

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
            taskId: Int,
            speed: Float,
            onDone: () -> Unit,
        ): CloseMorphSurface? {
            if (snapshot.width <= 0 || snapshot.height <= 0 || icon.isEmpty || win.isEmpty) return null
            return CloseMorphSurface(context, parent, snapshot, icon, win, taskId, speed, onDone)
        }
    }

    /** Cancel an in-flight morph (e.g. the user reopened the app). */
    fun cancel() = finish()
}
