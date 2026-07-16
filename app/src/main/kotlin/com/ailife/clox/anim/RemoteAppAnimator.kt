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

    /** Latest morph progress (0 = icon, 1 = fullscreen) so the launcher can drive a matching blur. */
    val morphExpansion: Float get() = MorphState.blurExpansion
    /** True while ANY real-window morph animator is actively ticking. */
    val morphInFlight: Boolean get() = MorphState.anyAnimating()

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
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: ALSO supply a Shell-transitions RemoteTransition. The platform uses the
            // RemoteTransition when shell transitions are enabled (default on 12+, forced on 15) and
            // the legacy adapter otherwise. On API 33+ the legacy remote-animation path is dormant, so
            // this is the ONLY open animation that actually fires there. Falls back to the plain
            // 1-arg legacy path if the modern classes/overload aren't available.
            makeRemoteAnimationWithTransition(adapter, adapterClass, Rect(iconBounds), speed)
                ?: (ActivityOptions::class.java.getMethod("makeRemoteAnimation", adapterClass)
                    .invoke(null, adapter) as ActivityOptions)
        } else {
            ActivityOptions::class.java.getMethod("makeRemoteAnimation", adapterClass)
                .invoke(null, adapter) as ActivityOptions
        }
        options.toBundle()
    } catch (t: Throwable) {
        Log.e(TAG, "openAnimationBundle failed — falling back to normal launch", t)
        null
    }

    /**
     * Build ActivityOptions via the 2-arg makeRemoteAnimation(RemoteAnimationAdapter, RemoteTransition)
     * so the OPEN morph fires under Shell Transitions (API 33+). Returns null (caller falls back) if
     * any of the @hide RemoteTransition/IRemoteTransition classes or the overload are unavailable.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun makeRemoteAnimationWithTransition(
        adapter: Any, adapterClass: Class<*>, iconBounds: Rect, speed: () -> Float
    ): ActivityOptions? = runCatching {
        val iRemoteTransition = Class.forName("android.window.IRemoteTransition")
        val remoteTransitionClass = Class.forName("android.window.RemoteTransition")
        val transitionRunner = AppOpenTransitionRunner(iconBounds, speed)   // IRemoteTransition.Stub
        val remoteTransition = remoteTransitionClass
            .getConstructor(iRemoteTransition, String::class.java)
            .newInstance(transitionRunner, "CloXLaunch")
        ActivityOptions::class.java
            .getMethod("makeRemoteAnimation", adapterClass, remoteTransitionClass)
            .invoke(null, adapter, remoteTransition) as ActivityOptions
    }.getOrElse {
        Log.d(TAG, "makeRemoteAnimation(adapter, RemoteTransition) unavailable: $it")
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
    private val interp: android.view.animation.Interpolator = DecelerateInterpolator(1.5f),
    // Which thread drives the frames. Legacy remote-animation (API 30) uses the shared BG thread so
    // main-thread jank can't stall it. Shell Transitions (API 31+) IGNORE surface transactions applied
    // from a background thread mid-transition, so that path must pass the MAIN handler.
    private val handler: Handler = sharedHandler
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
        // One shared background thread for all legacy leash morphs.
        val sharedHandler: Handler by lazy {
            Handler(HandlerThread("CloX-leash-anim").apply { start() }.looper)
        }
        val mainHandler: Handler by lazy { Handler(android.os.Looper.getMainLooper()) }

        /** Post to the shared leash-anim thread (used to defer the orphan-finish past a possible reversal). */
        fun postDelayed(delayMs: Long, r: Runnable) { sharedHandler.postDelayed(r, delayMs) }
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
    private val mSetCrop = m("setCrop", scClass, Rect::class.java)
        ?: m("setWindowCrop", scClass, Rect::class.java)
    private val mReparent = m("reparent", scClass, scClass)

    /** Diagnostic: which reflected setters resolved. */
    val diag: String get() = "matrix=${mSetMatrix != null} crop=${mSetCrop != null} " +
        "show=${mShow != null} alpha=${mSetAlpha != null} reparent=${mReparent != null}"

    /**
     * Set up a Shell-transitions leash on the shell's START transaction [t] so our per-frame matrix
     * morph actually renders: SHOW it and CROP it to its content bounds ([w]x[h]). A transition leash
     * with no crop renders nothing — on API 35 that showed the launcher blur but no window. Applied by
     * the caller (do not apply here). Safe if [t] is null (no-op).
     */
    fun setupLeash(t: SurfaceControl.Transaction?, leash: SurfaceControl, w: Int, h: Int) {
        t ?: return
        runCatching {
            runCatching { mShow?.invoke(t, leash) }
            runCatching { mSetCrop?.invoke(t, leash, Rect(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))) }
        }
    }

    // Direct per-transaction setters (operate on the passed transaction; caller applies).
    fun show(t: SurfaceControl.Transaction, leash: SurfaceControl) { runCatching { mShow?.invoke(t, leash) } }
    fun hide(t: SurfaceControl.Transaction, leash: SurfaceControl) {
        runCatching { txClass.getMethod("hide", scClass).invoke(t, leash) }
    }
    fun alpha(t: SurfaceControl.Transaction, leash: SurfaceControl, a: Float) { runCatching { mSetAlpha?.invoke(t, leash, a) } }
    fun layer(t: SurfaceControl.Transaction, leash: SurfaceControl, l: Int) { runCatching { mSetLayer?.invoke(t, leash, l) } }
    fun crop(t: SurfaceControl.Transaction, leash: SurfaceControl, w: Int, h: Int) {
        runCatching { mSetCrop?.invoke(t, leash, Rect(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))) }
    }
    fun reparent(t: SurfaceControl.Transaction, leash: SurfaceControl, newParent: SurfaceControl?) {
        runCatching { mReparent?.invoke(t, leash, newParent) }
    }

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
    // Progress is tracked PER TASK so two windows morphing at once (one shrinking while another
    // expands) don't share/clobber each other's expansion — while a reversal of the SAME task still
    // continues from where it was. A stale global value was making a new open start part-open and a
    // shrinking window's progress bleed into the opening one.
    class TaskState {
        @Volatile var expansion = 0f
        @Volatile var animating = false     // a morph LeashAnimator is currently ticking for this task
        @Volatile var lastFrameAt = 0L      // uptimeMillis of its last morph frame (reversal detection)
        @Volatile var icon: Rect? = null    // icon rect this task expanded from (so its close can aim back)
    }
    private val tasks = java.util.concurrent.ConcurrentHashMap<Int, TaskState>()
    fun of(taskId: Int): TaskState = tasks.getOrPut(taskId) { TaskState() }

    // Global view for the launcher blur (which only needs "is anything morphing, and how far"). When
    // two windows morph at once the NEWEST owns the blur: each morph claims a token, and only the
    // current owner's frames update blurExpansion, so a shrinking window can't fight the opening one.
    @Volatile var blurExpansion = 0f
    private val blurSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var blurOwner = 0L
    fun claimBlur(): Long = blurSeq.incrementAndGet().also { blurOwner = it }
    fun anyAnimating(): Boolean = tasks.values.any { it.animating }
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
    leash: SurfaceControl, win: Rect, icon: Rect, opening: Boolean, taskId: Int, speed: Float,
    handler: Handler = LeashAnimator.sharedHandler,
    topLayer: Int = 1,
    onFinish: () -> Unit
): LeashAnimator {
    val st = MorphState.of(taskId)
    val target = if (opening) 1f else 0f
    // Reversal only if THIS task's morph animated VERY recently (a preempting transition just
    // cancelled the previous one) — then continue from its current expansion. A stale expansion from
    // an old, finished/cancelled morph must NOT count, or a later fresh open/close would start at a
    // wrong mid-value. Per-task so a different window morphing at the same time can't affect this one.
    val recentlyActive = st.animating ||
        (android.os.SystemClock.uptimeMillis() - st.lastFrameAt) < 120L
    val start = if (recentlyActive) st.expansion.coerceIn(0f, 1f)
                else if (opening) 0f else 1f
    st.animating = true
    st.lastFrameAt = android.os.SystemClock.uptimeMillis()
    if (opening) st.icon = Rect(icon)   // remember where this task expanded from, for its future close
    val blurToken = MorphState.claimBlur()   // this (newest) morph now owns the launcher blur
    val matrix = Matrix()
    val tmp = FloatArray(9)
    // Land the leash on the starting expansion immediately — showing + layering + positioning it in
    // ONE transaction (showLayer=1) — before the anim thread's first tick, so a reversal never flashes
    // the leash at its reset (identity/fullscreen) state for a frame.
    applyMorphFrame(leash, win, icon, matrix, tmp, start, showLayer = topLayer)
    // Duration scales with remaining distance AND the user's speed setting (multiplier, higher =
    // faster = shorter). Clamped so a bad pref can't produce a 0ms or runaway animation.
    val dist = kotlin.math.abs(target - start).coerceAtLeast(0.02f)
    val duration = (MORPH_DURATION_MS * dist / speed.coerceIn(0.25f, 4f)).toLong().coerceAtLeast(60L)
    Log.d("CloX/PathA", "morph ${if (opening) "OPEN" else "CLOSE"} task=$taskId start=$start target=$target dur=$duration")
    // For Shell Transitions (topLayer high) re-assert show+layer EVERY frame — the shell can re-order
    // leashes mid-transition, which would drop the app back behind the home (blur-only). Cheap; the
    // legacy path (topLayer=1) also re-asserts harmlessly.
    return LeashAnimator(duration, onFrame = { p ->
        val e = start + (target - start) * p
        st.expansion = e
        st.lastFrameAt = android.os.SystemClock.uptimeMillis()
        if (MorphState.blurOwner == blurToken) MorphState.blurExpansion = e  // only the newest morph
        applyMorphFrame(leash, win, icon, matrix, tmp, e, showLayer = topLayer)
    }, onFinish = {
        st.expansion = target
        st.animating = false
        onFinish()
    }, interp = android.view.animation.LinearInterpolator(), handler = handler)
}

/**
 * Finish an ORPHANED morph on [leash] to [target] over a short time — used when the system cancels a
 * shrinking window's transition to open another app (the leash is left frozen mid-shrink). Runs only
 * if [taskId] hasn't been re-animated by a new transition (i.e. it's not a reversal). Best-effort: if
 * the system already reclaimed the leash the transactions are simply no-ops.
 */
@RequiresApi(Build.VERSION_CODES.R)
private fun finishOrphanMorph(leash: SurfaceControl, win: Rect, icon: Rect, taskId: Int, target: Float) {
    val st = MorphState.of(taskId)
    if (st.animating) return    // a new transition already took this task over — not orphaned
    val start = st.expansion.coerceIn(0f, 1f)
    st.animating = true
    val matrix = Matrix()
    val tmp = FloatArray(9)
    val dist = kotlin.math.abs(target - start).coerceAtLeast(0.02f)
    val duration = (MORPH_DURATION_MS * dist).toLong().coerceAtLeast(60L)
    Log.d("CloX/PathA", "orphan finish task=$taskId start=$start target=$target dur=$duration")
    LeashAnimator(duration, onFrame = { p ->
        val e = start + (target - start) * p
        st.expansion = e
        applyMorphFrame(leash, win, icon, matrix, tmp, e)
    }, onFinish = {
        st.expansion = target
        st.animating = false
    }, interp = android.view.animation.LinearInterpolator()).start()
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
    private var lastTaskId = -1

    override fun onAnimationStart(
        apps: Array<out RemoteAnimationTarget>?,
        wallpapers: Array<out RemoteAnimationTarget>?,
        finishedCallback: IRemoteAnimationFinishedCallback?
    ) {
        try {
            anim?.cancel()
            // Reusing this instance for a new transition cancels the previous animator, which never
            // fires onFinish — so clear the previous task's active flag here or anyAnimating() (the
            // blur's "is anything morphing") would stay true forever and the blur would stick.
            if (lastTaskId != -1) MorphState.of(lastTaskId).animating = false
            done = false
            val opening = apps?.firstOrNull { it.mode == RemoteAnimationTarget.MODE_OPENING }
            val win = opening?.screenSpaceBounds ?: opening?.sourceContainerBounds
            if (opening?.leash == null || win == null) {
                Log.w("CloX/PathA", "open: no opening leash, finishing")
                finish(finishedCallback)
                return
            }
            lastTaskId = opening.taskId
            val leash = opening.leash
            Log.d("CloX/PathA", "open onAnimationStart icon=$iconBounds win=$win apps=${apps.size}")
            // The app leash is shown + layered (above the launcher) atomically with its first-frame
            // transform inside buildMorphAnim, so the area outside it isn't black and there's no flash.
            anim = buildMorphAnim(leash, win, iconBounds, opening = true, taskId = opening.taskId,
                speed = speed()) {
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
        // Reset this task's active flag (a genuine reversal is still detected via lastFrameAt recency).
        if (lastTaskId != -1) MorphState.of(lastTaskId).animating = false
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
    // Remembered for the orphan-finish when the system cancels this close to open another app.
    private var lastLeash: SurfaceControl? = null
    private var lastWin: Rect? = null
    private var lastIcon: Rect? = null
    private var lastTaskId = -1
    private var lastTarget = 0f

    override fun onAnimationStart(
        apps: Array<out RemoteAnimationTarget>?,
        wallpapers: Array<out RemoteAnimationTarget>?,
        finishedCallback: IRemoteAnimationFinishedCallback?
    ) {
        try {
            // This runner instance is registered once and REUSED for every close — reset per-run
            // state so a latched `done` from the previous close doesn't freeze subsequent ones.
            anim?.cancel()
            // Cancelling the previous animator never fires its onFinish, so clear the previous task's
            // active flag here — else anyAnimating() (the blur's signal) stays true and the blur sticks.
            if (lastTaskId != -1) MorphState.of(lastTaskId).animating = false
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
            val opening = appTarget.mode == RemoteAnimationTarget.MODE_OPENING
            // Opening: expand from the just-tapped icon (iconProvider). Closing: shrink into THIS app's
            // own icon, recorded when it expanded (so it aims at the right slot even if another app was
            // launched since). Fall back to iconProvider, then screen centre.
            val icon = (if (opening) iconProvider() else MorphState.of(appTarget.taskId).icon)
                ?: iconProvider()
                ?: Rect(win.centerX() - 1, win.centerY() - 1, win.centerX() + 1, win.centerY() + 1)
            Log.d("CloX/PathA", "onAnimationStart dir=${if (opening) "OPEN" else "CLOSE"} appTaskId=${appTarget.taskId} launcherTaskId=$launcherTaskId win=$win icon=$icon apps=${apps?.size}")

            // Reveal the launcher (home) fully opaque BELOW the app so the area outside the morphing
            // window isn't a black transition layer — the home target is our launcher task, in either
            // direction. The app leash itself is shown + layered above atomically with its first frame
            // inside buildMorphAnim (no separate reveal → no fullscreen-flash on reversal).
            apps?.firstOrNull { it.taskId == launcherTaskId }?.leash?.let { SurfaceTx.reveal(it, 0) }
            wallpapers?.forEach { it.leash?.let { w -> SurfaceTx.reveal(w, -1) } }

            lastLeash = leash; lastWin = Rect(win); lastIcon = Rect(icon)
            lastTaskId = appTarget.taskId; lastTarget = if (opening) 1f else 0f

            // Reversible per-task morph: OPENING expands the app from the icon, CLOSING collapses it
            // into the icon — a HOME/BACK/tap mid-animation reverses this task in place.
            anim = buildMorphAnim(leash, win, icon, opening, taskId = appTarget.taskId,
                speed = speed(opening)) {
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
        val tid = lastTaskId
        if (tid != -1) {
            // Reset this task's active flag (a genuine reversal is still detected via lastFrameAt
            // recency). Then, deferred so a reversal (new transition ~1 frame later) can claim the
            // task first, finish an ORPHANED shrink — the system cancels the close to open another app
            // and leaves this leash frozen mid-shrink; drive it the rest of the way so it doesn't stick.
            MorphState.of(tid).animating = false
            val l = lastLeash; val w = lastWin; val ic = lastIcon; val tgt = lastTarget
            if (l != null && w != null && ic != null) {
                LeashAnimator.postDelayed(48L) { finishOrphanMorph(l, w, ic, tid, tgt) }
            }
        }
    }

    private fun finish(cb: IRemoteAnimationFinishedCallback?) {
        if (done) return
        done = true
        try { cb?.onAnimationFinished() } catch (t: Throwable) {
            Log.e("CloX/PathA", "close onAnimationFinished failed", t)
        }
    }
}

/**
 * OPEN runner for Shell Transitions (API 31+). On Android 12+ (and forced on 15) the legacy
 * remote-animation path is dormant; the system runs THIS instead. It hands us a TransitionInfo of
 * leashes and we grow the opening app's leash from the tapped icon rect to its end bounds, reusing
 * the exact same morph math as the legacy runner. Subclasses the vendored
 * android.window.IRemoteTransition.Stub; the real framework interface shadows it at runtime.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class AppOpenTransitionRunner(
    private val iconBounds: Rect,
    private val speed: () -> Float
) : android.window.IRemoteTransition.Stub() {

    @Volatile private var anim: LeashAnimator? = null

    override fun startAnimation(
        token: android.os.IBinder?,
        info: android.window.TransitionInfo?,
        t: SurfaceControl.Transaction?,
        finishCallback: android.window.IRemoteTransitionFinishedCallback?
    ) {
        try {
            val target = TransitionRefl.openingApp(info)
            if (target == null) {
                Log.w("CloX/PathA", "open transition: no opening app leash, finishing")
                runCatching { t?.apply() }
                finishTransition(finishCallback)
                return
            }
            val (leash, end, taskId) = target
            Log.d("CloX/PathA", "open transition start icon=$iconBounds win=$end task=$taskId " +
                "leashValid=${leash.isValid} ${SurfaceTx.diag} ${TransitionRefl.rootInfo(info)}")

            // The opening app leash is nested UNDER the home leash in this OEM's transition hierarchy,
            // so it can never draw above the home (and hiding the home hid it too). Reparent it to the
            // transition ROOT leash — making it a sibling ABOVE the home — then show + crop + lift it.
            // The home stays put below as the background the app grows over.
            val root = TransitionRefl.rootLeash(info)
            runCatching {
                t?.let { tx ->
                    if (root != null) SurfaceTx.reparent(tx, leash, root)
                    SurfaceTx.show(tx, leash)
                    SurfaceTx.crop(tx, leash, end.width(), end.height())
                    SurfaceTx.layer(tx, leash, 1_000_000)
                    tx.apply()
                }
            }
            Log.d("CloX/PathA", "open transition: reparented app to root=${root != null}, morphing")
            // Drive the morph on the MAIN handler: Shell Transitions ignore surface transactions
            // applied from a background thread mid-transition (the exact reason the legacy BG-thread
            // path showed nothing here). Reuses the same morph math (scale/corner/alpha/speed).
            anim = buildMorphAnim(
                leash, end, iconBounds, opening = true, taskId = taskId, speed = speed(),
                handler = LeashAnimator.mainHandler, topLayer = 1_000_000
            ) { finishTransition(finishCallback) }.also { it.start() }
        } catch (e: Throwable) {
            Log.e("CloX/PathA", "open transition failed — finishing", e)
            finishTransition(finishCallback)
        }
    }

    private fun finishTransition(cb: android.window.IRemoteTransitionFinishedCallback?) {
        runCatching { cb?.onTransitionFinished(null, null) }
    }

    override fun mergeAnimation(
        transition: android.os.IBinder?,
        info: android.window.TransitionInfo?,
        t: SurfaceControl.Transaction?,
        mergeTarget: android.os.IBinder?,
        finishCallback: android.window.IRemoteTransitionFinishedCallback?
    ) { /* no-op: let the running open animation finish on its own (matches the framework default) */ }

    override fun takeOverAnimation(
        transition: android.os.IBinder?,
        info: android.window.TransitionInfo?,
        t: SurfaceControl.Transaction?,
        finishCallback: android.window.IRemoteTransitionFinishedCallback?,
        states: Array<android.window.WindowAnimationState?>?
    ) {
        // We don't support takeover — commit and finish immediately so nothing hangs.
        runCatching { t?.apply() }
        finishTransition(finishCallback)
    }

    override fun onTransitionConsumed(transition: android.os.IBinder?, aborted: Boolean) {
        anim?.cancel()
    }
}

/**
 * Reflection over the @hide android.window.TransitionInfo / TransitionInfo.Change to pull the opening
 * app's leash, end bounds and task id (the classes aren't in the SDK, so no compile-time types).
 */
@RequiresApi(Build.VERSION_CODES.S)
private object TransitionRefl {
    private val getChanges = runCatching {
        Class.forName("android.window.TransitionInfo").getMethod("getChanges")
    }.getOrNull()
    private val changeClass = runCatching { Class.forName("android.window.TransitionInfo\$Change") }.getOrNull()
    private val mLeash = runCatching { changeClass?.getMethod("getLeash") }.getOrNull()
    private val mMode = runCatching { changeClass?.getMethod("getMode") }.getOrNull()
    private val mEndAbs = runCatching { changeClass?.getMethod("getEndAbsBounds") }.getOrNull()
    private val mTaskInfo = runCatching { changeClass?.getMethod("getTaskInfo") }.getOrNull()

    private val mRootLeash = runCatching {
        Class.forName("android.window.TransitionInfo\$Root").getMethod("getLeash")
    }.getOrNull()

    /** The transition's (first) root leash — reparent the app under this to lift it above the home. */
    fun rootLeash(info: Any?): SurfaceControl? = runCatching {
        val root = mGetRoot?.invoke(info, 0) ?: return null
        mRootLeash?.invoke(root) as? SurfaceControl
    }.getOrNull()

    /** @return every change as (leash, mode, activityType). */
    fun changes(info: Any?): List<Triple<SurfaceControl, Int, Int>> {
        info ?: return emptyList()
        val list = runCatching { getChanges?.invoke(info) as? List<*> }.getOrNull() ?: return emptyList()
        return list.mapNotNull { c ->
            c ?: return@mapNotNull null
            val leash = runCatching { mLeash?.invoke(c) as? SurfaceControl }.getOrNull() ?: return@mapNotNull null
            val mode = runCatching { mMode?.invoke(c) as? Int }.getOrNull() ?: return@mapNotNull null
            val at = activityType(runCatching { mTaskInfo?.invoke(c) }.getOrNull())
            Triple(leash, mode, at)
        }
    }

    /** @return (leash, endAbsBounds, taskId) for the opening non-home app, or null. */
    fun openingApp(info: Any?): Triple<SurfaceControl, Rect, Int>? {
        info ?: return null
        val changes = runCatching { getChanges?.invoke(info) as? List<*> }.getOrNull() ?: return null
        for (c in changes) {
            c ?: continue
            val mode = runCatching { mMode?.invoke(c) as? Int }.getOrNull() ?: continue
            if (mode != 1 && mode != 3) continue   // TRANSIT_OPEN(1) or TRANSIT_TO_FRONT(3)
            if (activityType(runCatching { mTaskInfo?.invoke(c) }.getOrNull()) == 2) continue  // skip HOME
            val leash = runCatching { mLeash?.invoke(c) as? SurfaceControl }.getOrNull() ?: continue
            val end = runCatching { mEndAbs?.invoke(c) as? Rect }.getOrNull() ?: continue
            val taskId = taskId(runCatching { mTaskInfo?.invoke(c) }.getOrNull())
            return Triple(leash, end, taskId)
        }
        return null
    }

    private val mGetRootCount = runCatching { Class.forName("android.window.TransitionInfo").getMethod("getRootCount") }.getOrNull()
    private val mGetRoot = runCatching { Class.forName("android.window.TransitionInfo").getMethod("getRoot", Int::class.javaPrimitiveType) }.getOrNull()
    private val mGetType = runCatching { Class.forName("android.window.TransitionInfo").getMethod("getType") }.getOrNull()

    private val mGetFlags = runCatching { changeClass?.getMethod("getFlags") }.getOrNull()

    /** Diagnostic: transition type, root offset, and every Change (mode/activityType/flags/leash). */
    fun rootInfo(info: Any?): String = runCatching {
        val type = mGetType?.invoke(info)
        val n = (mGetRootCount?.invoke(info) as? Int) ?: 0
        val roots = (0 until n).joinToString(",") { i ->
            val r = mGetRoot?.invoke(info, i)
            "off=${runCatching { r?.javaClass?.getMethod("getOffset")?.invoke(r) }.getOrNull()}"
        }
        val changes = (getChanges?.invoke(info) as? List<*>)?.joinToString(" | ") { c ->
            val mode = runCatching { mMode?.invoke(c) }.getOrNull()
            val at = activityType(runCatching { mTaskInfo?.invoke(c) }.getOrNull())
            val fl = runCatching { mGetFlags?.invoke(c) }.getOrNull()
            val lv = runCatching { (mLeash?.invoke(c) as? SurfaceControl)?.isValid }.getOrNull()
            "mode=$mode actType=$at flags=$fl leashValid=$lv"
        }
        "type=$type roots=$n[$roots] changes=[$changes]"
    }.getOrDefault("rootInfo-err")

    private fun activityType(taskInfo: Any?): Int = runCatching {
        taskInfo?.javaClass?.getMethod("getActivityType")?.invoke(taskInfo) as? Int
    }.getOrNull() ?: -1

    private fun taskId(taskInfo: Any?): Int = runCatching {
        taskInfo?.javaClass?.getField("taskId")?.getInt(taskInfo)
    }.getOrNull() ?: -1
}
