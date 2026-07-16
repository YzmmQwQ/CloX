package com.ailife.clox.anim

import android.app.ActivityOptions
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.animation.DecelerateInterpolator
import androidx.annotation.RequiresApi

/**
 * Path A — real app-window OPEN/CLOSE animations, driven through the privileged remote-animation
 * framework APIs (held because CloX ships as a /system/priv-app; hidden-API access enabled by the
 * module). Mirrors Launcher3 QuickstepAppTransitionManagerImpl.
 *
 * The per-frame leash transforms run on a dedicated background thread (see [LeashAnimator]):
 * SurfaceControl.Transaction commits straight to SurfaceFlinger, so the morph stays smooth even
 * when the launcher's main thread is jammed resuming its GL/Axmol surface (which is exactly what
 * stalled the close animation when it ran on the main-thread ValueAnimator).
 */
// System-side remote-animation timeout. Must be >= the slowest actual morph (MORPH_DURATION_MS at
// 0.5x speed = 600ms) or the system force-finishes before our animation completes. Our runner calls
// onAnimationFinished as soon as the real (speed-scaled) morph ends, so a generous value is free.
private const val MORPH_ADAPTER_TIMEOUT_MS = 800L

// Close transition types (API 30). We register for all of them because which one the OEM
// WindowManager picks for "app → launcher" depends on whether the home window shows wallpaper.
// "app ↔ launcher" transitions we register our runner for. TASK_TO_FRONT (10) is included for the
// hardware HOME key, but it is DIRECTION-AMBIGUOUS: it also fires when tapping an ALREADY-RUNNING app
// (home closes, app opens). The RemoteAnimationDefinition can't tell these apart (both transitions
// involve {HOME, STANDARD} activity types), so the runner inspects the targets at animation time and
// shrinks the app (app→launcher) or expands it (launcher→app) — see AppCloseAnimationRunner.
private val CLOSE_TRANSITS = intArrayOf(
    13, // TRANSIT_WALLPAPER_OPEN
    9,  // TRANSIT_TASK_CLOSE
    7,  // TRANSIT_ACTIVITY_CLOSE
    11, // TRANSIT_TASK_TO_BACK
    10  // TRANSIT_TASK_TO_FRONT (HOME key; ambiguous — runner decides direction)
)

@RequiresApi(Build.VERSION_CODES.R)
object RemoteAppAnimator {

    private const val TAG = "CloX/PathA"

    /** Current morph progress (0 = icon, 1 = fullscreen) so the launcher can drive a matching blur. */
    val morphExpansion: Float get() = MorphState.expansion
    /** True only while a real-window morph animator is actively ticking. */
    val morphInFlight: Boolean get() = MorphState.animating

    /**
     * @return a bundle to pass to startActivity so the system runs our open animation, or null if
     * the privileged plumbing is unavailable (then the caller should fall back to a normal launch).
     */
    fun openAnimationBundle(iconBounds: Rect, speed: () -> Float): Bundle? = try {
        val runner = AppOpenAnimationRunner(Rect(iconBounds), speed)
        val adapterClass = Class.forName("android.view.RemoteAnimationAdapter")
        val runnerClass = Class.forName("android.view.IRemoteAnimationRunner")
        val adapter = adapterClass
            .getConstructor(runnerClass, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .newInstance(runner, MORPH_ADAPTER_TIMEOUT_MS, 0L)
        val make = ActivityOptions::class.java.getMethod("makeRemoteAnimation", adapterClass)
        val options = make.invoke(null, adapter) as ActivityOptions
        options.toBundle()
    } catch (t: Throwable) {
        Log.e(TAG, "openAnimationBundle failed — falling back to normal launch", t)
        null
    }

    /**
     * Register the CLOSE animation on the launcher [activity]. When an app closes back to the
     * launcher the system hands our runner the real closing-window leash, which we shrink into the
     * icon rect returned by [iconProvider] (screen coords; null → screen centre). Call
     * [unregisterClose] in onDestroy.
     */
    fun registerClose(
        activity: android.app.Activity,
        speed: (opening: Boolean) -> Float,
        iconProvider: () -> Rect?
    ) {
        try {
            val defClass = Class.forName("android.view.RemoteAnimationDefinition")
            val adapterClass = Class.forName("android.view.RemoteAnimationAdapter")
            val runnerClass = Class.forName("android.view.IRemoteAnimationRunner")
            val def = defClass.getConstructor().newInstance()
            val addRemote = defClass.getMethod(
                "addRemoteAnimation", Int::class.javaPrimitiveType, adapterClass)
            val adapterCtor = adapterClass.getConstructor(
                runnerClass, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val launcherTaskId = activity.taskId
            for (transit in CLOSE_TRANSITS) {
                val runner = AppCloseAnimationRunner(iconProvider, launcherTaskId, speed)
                val adapter = adapterCtor.newInstance(runner, MORPH_ADAPTER_TIMEOUT_MS, 0L)
                addRemote.invoke(def, transit, adapter)
            }
            android.app.Activity::class.java
                .getMethod("registerRemoteAnimations", defClass)
                .invoke(activity, def)
            Log.d(TAG, "registerClose: registered for transits ${CLOSE_TRANSITS.toList()} launcherTaskId=$launcherTaskId")
        } catch (t: Throwable) {
            Log.e(TAG, "registerClose failed", t)
        }
    }

    fun unregisterClose(activity: android.app.Activity) {
        try {
            android.app.Activity::class.java
                .getMethod("unregisterRemoteAnimations").invoke(activity)
            Log.d(TAG, "unregisterClose: done")
        } catch (t: Throwable) {
            Log.e(TAG, "unregisterClose failed", t)
        }
    }
}

/**
 * Drives a leash morph on a dedicated background thread with wall-clock timing, so main-thread
 * jank never stalls it. [onFrame] applies a SurfaceControl.Transaction for progress 0..1 (already
 * eased); [onFinish] is invoked once, after the final frame, to signal the system. [cancel] stops
 * the loop without finishing (used when the system cancels — the leashes are then invalid).
 */
@RequiresApi(Build.VERSION_CODES.R)
private class LeashAnimator(
    private val durationMs: Long,
    private val onFrame: (Float) -> Unit,
    private val onFinish: () -> Unit,
    private val interp: android.view.animation.Interpolator = DecelerateInterpolator(1.5f)
) {
    @Volatile private var cancelled = false
    @Volatile private var finished = false
    private var startTime = 0L

    fun start() {
        startTime = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun cancel() { cancelled = true }

    private val tick = object : Runnable {
        override fun run() {
            if (cancelled || finished) return
            val raw = ((SystemClock.uptimeMillis() - startTime).toFloat() / durationMs)
                .coerceIn(0f, 1f)
            onFrame(interp.getInterpolation(raw))
            if (raw >= 1f) {
                finished = true
                if (!cancelled) onFinish()
            } else {
                handler.postDelayed(this, 8L)
            }
        }
    }

    companion object {
        // One shared background thread for all leash morphs.
        private val handler: Handler by lazy {
            Handler(HandlerThread("CloX-leash-anim").apply { start() }.looper)
        }
    }
}

/**
 * Shared reflection over the public-but-@hide SurfaceControl.Transaction geometry setters. Every
 * method handle is resolved defensively (null if the platform lacks that exact signature) so class
 * init never throws, and every invoke is wrapped so a hidden-API failure degrades gracefully.
 */
private object SurfaceTx {
    private val txClass = SurfaceControl.Transaction::class.java
    private val scClass = SurfaceControl::class.java
    private fun m(name: String, vararg params: Class<*>?) =
        runCatching { txClass.getMethod(name, *params) }.getOrNull()

    private val mSetMatrix = m("setMatrix", scClass, Matrix::class.java, FloatArray::class.java)
    private val mSetCorner = m("setCornerRadius", scClass, Float::class.javaPrimitiveType)
    private val mSetAlpha = m("setAlpha", scClass, Float::class.javaPrimitiveType)
    private val mShow = m("show", scClass)
    private val mSetLayer = m("setLayer", scClass, Int::class.javaPrimitiveType)

    /** Build + apply one frame's transform on the given leash. Safe on any thread. */
    fun frame(leash: SurfaceControl, matrix: Matrix, tmp: FloatArray, cornerR: Float, alpha: Float) {
        runCatching {
            val t = SurfaceControl.Transaction()
            runCatching { mSetMatrix?.invoke(t, leash, matrix, tmp) }
            runCatching { mSetCorner?.invoke(t, leash, cornerR) }
            runCatching { mSetAlpha?.invoke(t, leash, alpha) }
            t.apply()
        }
    }

    /**
     * First frame: show + layer the leash AND set its transform in ONE transaction, so the leash is
     * never presented at its reset (identity = fullscreen) position for a frame before we position it.
     * That intermediate frame was the "snap to fullscreen then reverse" flash on interruption.
     */
    fun frameShown(
        leash: SurfaceControl, layer: Int, matrix: Matrix, tmp: FloatArray, cornerR: Float, alpha: Float
    ) {
        runCatching {
            val t = SurfaceControl.Transaction()
            runCatching { mShow?.invoke(t, leash) }
            runCatching { mSetLayer?.invoke(t, leash, layer) }
            runCatching { mSetMatrix?.invoke(t, leash, matrix, tmp) }
            runCatching { mSetCorner?.invoke(t, leash, cornerR) }
            runCatching { mSetAlpha?.invoke(t, leash, alpha) }
            t.apply()
        }
    }

    /** Reveal [leash] fully opaque at the given z-order. Any thread. */
    fun reveal(leash: SurfaceControl, layer: Int) {
        runCatching {
            val t = SurfaceControl.Transaction()
            runCatching { mShow?.invoke(t, leash) }
            runCatching { mSetAlpha?.invoke(t, leash, 1f) }
            runCatching { mSetLayer?.invoke(t, leash, layer) }
            t.apply()
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Full-distance morph duration (icon↔fullscreen); a partial reversal is scaled down from this. */
private const val MORPH_DURATION_MS = 300L

/**
 * Shared morph progress so open/close are REVERSIBLE and interruptible. [expansion] is 0 (icon-sized)
 * … 1 (fullscreen). [inFlight] is true while a morph animator is running — a HOME/BACK/tap that starts
 * a new transition mid-animation continues from the current [expansion] instead of snapping, so the
 * window reverses in place; pressing again reverses it back. Written from the leash-anim thread and
 * the binder thread; volatile is enough (single float, last-writer-wins is acceptable here).
 */
private object MorphState {
    @Volatile var expansion = 0f
    @Volatile var animating = false     // a morph LeashAnimator is currently ticking
    @Volatile var lastFrameAt = 0L      // uptimeMillis of the last morph frame (for reversal detection)
}

/**
 * Apply one morph frame at [e] (0 = icon rect, 1 = fullscreen [win]) to [leash]. When [showLayer] is
 * non-null the transaction also shows + layers the leash atomically (used for the first frame so a
 * reversal never flashes the leash at its reset fullscreen position).
 */
@RequiresApi(Build.VERSION_CODES.R)
private fun applyMorphFrame(
    leash: SurfaceControl, win: Rect, icon: Rect, matrix: Matrix, tmp: FloatArray, e: Float,
    showLayer: Int? = null
) {
    val winW = win.width().coerceAtLeast(1).toFloat()
    val winH = win.height().coerceAtLeast(1).toFloat()
    val iconScale = (icon.width().toFloat() / winW).coerceIn(0.05f, 1f)
    val s = lerp(iconScale, 1f, e)
    val cx = lerp(icon.exactCenterX(), win.exactCenterX(), e)
    val cy = lerp(icon.exactCenterY(), win.exactCenterY(), e)
    matrix.setScale(s, s)
    matrix.postTranslate(cx - winW * s / 2f, cy - winH * s / 2f)
    // Rounded corners in SCREEN space. setCornerRadius is in the surface's local (pre-matrix) space,
    // so the on-screen radius is cornerR*s — divide the desired screen radius by s to control it. The
    // radius stays ~icon-round for most of the expansion and decreases toward the end (1 - e²), so the
    // window flattens to square only as it reaches fullscreen (and rounds up as it collapses to icon).
    val screenRadius = (1f - e * e) * (icon.width() / 2f)
    val cornerR = screenRadius / s
    // Fade the window out as it collapses into the last quarter toward the icon (and in on expand).
    val alpha = if (e < 0.25f) (e / 0.25f).coerceIn(0f, 1f) else 1f
    if (showLayer != null) SurfaceTx.frameShown(leash, showLayer, matrix, tmp, cornerR, alpha)
    else SurfaceTx.frame(leash, matrix, tmp, cornerR, alpha)
}

/**
 * Build a reversible morph animator for [leash] toward [opening] (true = expand to fullscreen, false
 * = collapse to [icon]). Starts from the CURRENT shared [MorphState.expansion] when a morph is already
 * in flight (a reversal), else from the natural end (0 for open, 1 for close). The first frame is
 * applied synchronously so a reversal picks up the current visual with no jump; duration is scaled by
 * the remaining distance so the speed stays constant. Linear so reversals don't jerk at the seam.
 */
@RequiresApi(Build.VERSION_CODES.R)
private fun buildMorphAnim(
    leash: SurfaceControl, win: Rect, icon: Rect, opening: Boolean, speed: Float, onFinish: () -> Unit
): LeashAnimator {
    val target = if (opening) 1f else 0f
    // Reversal only if a morph animated VERY recently (a preempting transition just cancelled the
    // previous one) — then continue from the current expansion. A stale expansion from an old,
    // finished/cancelled morph must NOT count, or a later fresh open/close would start at a wrong
    // mid-value and a spurious blur could latch onto the stale progress.
    val recentlyActive = MorphState.animating ||
        (android.os.SystemClock.uptimeMillis() - MorphState.lastFrameAt) < 120L
    val start = if (recentlyActive) MorphState.expansion.coerceIn(0f, 1f)
                else if (opening) 0f else 1f
    MorphState.animating = true
    MorphState.lastFrameAt = android.os.SystemClock.uptimeMillis()
    val matrix = Matrix()
    val tmp = FloatArray(9)
    // Land the leash on the starting expansion immediately — showing + layering + positioning it in
    // ONE transaction (showLayer=1) — before the anim thread's first tick, so a reversal never flashes
    // the leash at its reset (identity/fullscreen) state for a frame.
    applyMorphFrame(leash, win, icon, matrix, tmp, start, showLayer = 1)
    // Duration scales with remaining distance AND the user's speed setting (multiplier, higher =
    // faster = shorter). Clamped so a bad pref can't produce a 0ms or runaway animation.
    val dist = kotlin.math.abs(target - start).coerceAtLeast(0.02f)
    val duration = (MORPH_DURATION_MS * dist / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(60L)
    Log.d("CloX/PathA", "morph ${if (opening) "OPEN" else "CLOSE"} start=$start target=$target dur=$duration")
    return LeashAnimator(duration, onFrame = { p ->
        val e = start + (target - start) * p
        MorphState.expansion = e
        MorphState.lastFrameAt = android.os.SystemClock.uptimeMillis()
        applyMorphFrame(leash, win, icon, matrix, tmp, e)
    }, onFinish = {
        MorphState.expansion = target
        MorphState.animating = false
        onFinish()
    }, interp = android.view.animation.LinearInterpolator())
}

/**
 * OPEN runner — grows the real opening app-window leash from the tapped icon rect to fullscreen.
 * At runtime this extends the *framework* IRemoteAnimationRunner.Stub (parent-first classloading
 * shadows the vendored AIDL copy), so the override binds to the real binder method.
 */
@RequiresApi(Build.VERSION_CODES.R)
private class AppOpenAnimationRunner(
    private val iconBounds: Rect,
    private val speed: () -> Float
) : IRemoteAnimationRunner.Stub() {

    @Volatile private var done = false
    private var anim: LeashAnimator? = null

    override fun onAnimationStart(
        apps: Array<out RemoteAnimationTarget>?,
        wallpapers: Array<out RemoteAnimationTarget>?,
        finishedCallback: IRemoteAnimationFinishedCallback?
    ) {
        try {
            anim?.cancel()
            done = false
            val opening = apps?.firstOrNull { it.mode == RemoteAnimationTarget.MODE_OPENING }
            val win = opening?.screenSpaceBounds ?: opening?.sourceContainerBounds
            if (opening?.leash == null || win == null) {
                Log.w("CloX/PathA", "open: no opening leash, finishing")
                finish(finishedCallback)
                return
            }
            val leash = opening.leash
            Log.d("CloX/PathA", "open onAnimationStart icon=$iconBounds win=$win apps=${apps.size}")
            // The app leash is shown + layered (above the launcher) atomically with its first-frame
            // transform inside buildMorphAnim, so the area outside it isn't black and there's no flash.
            anim = buildMorphAnim(leash, win, iconBounds, opening = true, speed = speed()) {
                finish(finishedCallback)
            }.also { it.start() }
        } catch (t: Throwable) {
            // Any failure must still release the transition, or the system hangs ~seconds.
            Log.e("CloX/PathA", "open onAnimationStart failed — finishing", t)
            finish(finishedCallback)
        }
    }

    override fun onAnimationCancelled() {
        Log.d("CloX/PathA", "open onAnimationCancelled")
        done = true
        anim?.cancel()
        // Mark no morph active so the blur can't latch onto stale progress. lastFrameAt is left as-is
        // so a genuine reversal (new transition within ~120ms) is still detected and continues.
        MorphState.animating = false
    }

    private fun finish(cb: IRemoteAnimationFinishedCallback?) {
        if (done) return
        done = true
        try { cb?.onAnimationFinished() } catch (t: Throwable) {
            Log.e("CloX/PathA", "open onAnimationFinished failed", t)
        }
    }
}

/**
 * CLOSE runner — shrinks the real closing app-window leash back into the icon rect. Registered on
 * the launcher via RemoteAnimationDefinition; the system invokes it for the app→launcher transition.
 */
@RequiresApi(Build.VERSION_CODES.R)
private class AppCloseAnimationRunner(
    private val iconProvider: () -> Rect?,
    private val launcherTaskId: Int,
    private val speed: (opening: Boolean) -> Float
) : IRemoteAnimationRunner.Stub() {

    @Volatile private var done = false
    private var anim: LeashAnimator? = null

    override fun onAnimationStart(
        apps: Array<out RemoteAnimationTarget>?,
        wallpapers: Array<out RemoteAnimationTarget>?,
        finishedCallback: IRemoteAnimationFinishedCallback?
    ) {
        try {
            // This runner instance is registered once and REUSED for every close — reset per-run
            // state so a latched `done` from the previous close doesn't freeze subsequent ones.
            anim?.cancel()
            done = false
            // TASK_TO_FRONT (10) is direction-ambiguous: the HOME key (app→launcher) and tapping an
            // already-running app (launcher→app) both land here. Pick the "app" target as the one that
            // is NOT our launcher/home task, then branch on its mode: OPENING = expand it from the icon
            // (launcher→app), CLOSING = shrink it into the icon (app→launcher, i.e. HOME/BACK).
            val appTarget = apps?.firstOrNull { it.taskId != launcherTaskId && it.leash != null }
                ?: apps?.firstOrNull { it.mode == RemoteAnimationTarget.MODE_CLOSING }
            val win = appTarget?.screenSpaceBounds ?: appTarget?.sourceContainerBounds
            if (appTarget?.leash == null || win == null) {
                Log.w("CloX/PathA", "close: no app leash, finishing")
                finish(finishedCallback)
                return
            }
            val leash = appTarget.leash
            val icon = iconProvider() ?: Rect(
                win.centerX() - 1, win.centerY() - 1, win.centerX() + 1, win.centerY() + 1)
            val opening = appTarget.mode == RemoteAnimationTarget.MODE_OPENING
            Log.d("CloX/PathA", "onAnimationStart dir=${if (opening) "OPEN" else "CLOSE"} appTaskId=${appTarget.taskId} launcherTaskId=$launcherTaskId win=$win icon=$icon apps=${apps?.size}")

            // Reveal the launcher (home) fully opaque BELOW the app so the area outside the morphing
            // window isn't a black transition layer — the home target is our launcher task, in either
            // direction. The app leash itself is shown + layered above atomically with its first frame
            // inside buildMorphAnim (no separate reveal → no fullscreen-flash on reversal).
            apps?.firstOrNull { it.taskId == launcherTaskId }?.leash?.let { SurfaceTx.reveal(it, 0) }
            wallpapers?.forEach { it.leash?.let { w -> SurfaceTx.reveal(w, -1) } }

            // Reversible morph: OPENING expands the app from the icon, CLOSING collapses it into the
            // icon — both share MorphState, so a HOME/BACK/tap mid-animation reverses in place.
            anim = buildMorphAnim(leash, win, icon, opening, speed = speed(opening)) {
                finish(finishedCallback)
            }.also { it.start() }
        } catch (t: Throwable) {
            Log.e("CloX/PathA", "close onAnimationStart failed — finishing", t)
            finish(finishedCallback)
        }
    }

    override fun onAnimationCancelled() {
        Log.d("CloX/PathA", "close onAnimationCancelled")
        done = true
        anim?.cancel()
        // Mark no morph active so the blur can't latch onto stale progress. lastFrameAt is left as-is
        // so a genuine reversal (new transition within ~120ms) is still detected and continues.
        MorphState.animating = false
    }

    private fun finish(cb: IRemoteAnimationFinishedCallback?) {
        if (done) return
        done = true
        try { cb?.onAnimationFinished() } catch (t: Throwable) {
            Log.e("CloX/PathA", "close onAnimationFinished failed", t)
        }
    }
}
