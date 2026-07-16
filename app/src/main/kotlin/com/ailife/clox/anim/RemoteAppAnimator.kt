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
private const val OPEN_DURATION_MS = 320L
private const val CLOSE_DURATION_MS = 300L

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

    /**
     * @return a bundle to pass to startActivity so the system runs our open animation, or null if
     * the privileged plumbing is unavailable (then the caller should fall back to a normal launch).
     */
    fun openAnimationBundle(iconBounds: Rect): Bundle? = try {
        val runner = AppOpenAnimationRunner(Rect(iconBounds))
        val adapterClass = Class.forName("android.view.RemoteAnimationAdapter")
        val runnerClass = Class.forName("android.view.IRemoteAnimationRunner")
        val adapter = adapterClass
            .getConstructor(runnerClass, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .newInstance(runner, OPEN_DURATION_MS, 0L)
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
    fun registerClose(activity: android.app.Activity, iconProvider: () -> Rect?) {
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
                val runner = AppCloseAnimationRunner(iconProvider, launcherTaskId)
                val adapter = adapterCtor.newInstance(runner, CLOSE_DURATION_MS, 0L)
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
    private val onFinish: () -> Unit
) {
    @Volatile private var cancelled = false
    @Volatile private var finished = false
    private var startTime = 0L
    private val interp = DecelerateInterpolator(1.5f)

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

/**
 * OPEN runner — grows the real opening app-window leash from the tapped icon rect to fullscreen.
 * At runtime this extends the *framework* IRemoteAnimationRunner.Stub (parent-first classloading
 * shadows the vendored AIDL copy), so the override binds to the real binder method.
 */
@RequiresApi(Build.VERSION_CODES.R)
private class AppOpenAnimationRunner(
    private val iconBounds: Rect
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

            val winW = win.width().coerceAtLeast(1).toFloat()
            val winH = win.height().coerceAtLeast(1).toFloat()
            val startScale = (iconBounds.width().toFloat() / winW).coerceIn(0.05f, 1f)
            val startCornerR = iconBounds.width() / 2f
            val matrix = Matrix()
            val tmp = FloatArray(9)

            anim = LeashAnimator(OPEN_DURATION_MS, onFrame = { p ->
                if (done) return@LeashAnimator
                val s = lerp(startScale, 1f, p)
                val cx = lerp(iconBounds.exactCenterX(), win.exactCenterX(), p)
                val cy = lerp(iconBounds.exactCenterY(), win.exactCenterY(), p)
                matrix.setScale(s, s)
                matrix.postTranslate(cx - winW * s / 2f, cy - winH * s / 2f)
                SurfaceTx.frame(leash, matrix, tmp, (1f - p) * startCornerR, 1f)
            }, onFinish = { finish(finishedCallback) }).also { it.start() }
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
    private val launcherTaskId: Int
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

            val winW = win.width().coerceAtLeast(1).toFloat()
            val winH = win.height().coerceAtLeast(1).toFloat()
            val matrix = Matrix()
            val tmp = FloatArray(9)
            var frames = 0

            if (opening) {
                // launcher→app (tapping an already-running app via TASK_TO_FRONT): expand the opening
                // app window from its icon — the inverse of the close. Reveal the closing launcher
                // below so the area outside the growing window isn't a black transition layer.
                apps?.firstOrNull { it.taskId == launcherTaskId }?.leash?.let { SurfaceTx.reveal(it, 0) }
                wallpapers?.forEach { it.leash?.let { w -> SurfaceTx.reveal(w, -1) } }
                SurfaceTx.reveal(leash, 1)
                val startScale = (icon.width().toFloat() / winW).coerceIn(0.05f, 1f)
                val startCornerR = icon.width() / 2f
                anim = LeashAnimator(OPEN_DURATION_MS, onFrame = { p ->
                    if (done) return@LeashAnimator
                    if (frames == 0) Log.d("CloX/PathA", "open(TO_FRONT) FIRST frame p=$p")
                    frames++
                    val s = lerp(startScale, 1f, p)
                    val cx = lerp(icon.exactCenterX(), win.exactCenterX(), p)
                    val cy = lerp(icon.exactCenterY(), win.exactCenterY(), p)
                    matrix.setScale(s, s)
                    matrix.postTranslate(cx - winW * s / 2f, cy - winH * s / 2f)
                    SurfaceTx.frame(leash, matrix, tmp, (1f - p) * startCornerR, 1f)
                }, onFinish = {
                    Log.d("CloX/PathA", "open(TO_FRONT) onFinish after $frames frames")
                    finish(finishedCallback)
                }).also { it.start() }
                return
            }

            // app→launcher (HOME / BACK): reveal the launcher (opening target) fully opaque BELOW the
            // closing app, so the area outside the shrinking window shows the launcher, not black.
            apps?.firstOrNull { it.mode == RemoteAnimationTarget.MODE_OPENING }?.leash
                ?.let { SurfaceTx.reveal(it, 0) }
            wallpapers?.forEach { it.leash?.let { w -> SurfaceTx.reveal(w, -1) } }
            // Keep the shrinking app above the launcher.
            SurfaceTx.reveal(leash, 1)

            val endScale = (icon.width().toFloat() / winW).coerceIn(0.05f, 1f)
            val endCornerR = icon.width() / 2f
            anim = LeashAnimator(CLOSE_DURATION_MS, onFrame = { p ->
                if (done) return@LeashAnimator
                if (frames == 0) Log.d("CloX/PathA", "close FIRST frame p=$p")
                frames++
                val s = lerp(1f, endScale, p)
                val cx = lerp(win.exactCenterX(), icon.exactCenterX(), p)
                val cy = lerp(win.exactCenterY(), icon.exactCenterY(), p)
                matrix.setScale(s, s)
                matrix.postTranslate(cx - winW * s / 2f, cy - winH * s / 2f)
                // Fade out over the last 25% so the shrunken window doesn't pop when removed.
                val alpha = if (p > 0.75f) (1f - (p - 0.75f) / 0.25f).coerceIn(0f, 1f) else 1f
                SurfaceTx.frame(leash, matrix, tmp, p * endCornerR, alpha)
            }, onFinish = {
                Log.d("CloX/PathA", "close onFinish after $frames frames -> onAnimationFinished")
                finish(finishedCallback)
            }).also { it.start() }
        } catch (t: Throwable) {
            Log.e("CloX/PathA", "close onAnimationStart failed — finishing", t)
            finish(finishedCallback)
        }
    }

    override fun onAnimationCancelled() {
        Log.d("CloX/PathA", "close onAnimationCancelled")
        done = true
        anim?.cancel()
    }

    private fun finish(cb: IRemoteAnimationFinishedCallback?) {
        if (done) return
        done = true
        try { cb?.onAnimationFinished() } catch (t: Throwable) {
            Log.e("CloX/PathA", "close onAnimationFinished failed", t)
        }
    }
}
