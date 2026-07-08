package com.ailife.clox.cocos

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import com.ailife.clox.crash.NativeCrashHandler
import dev.axmol.lib.AxmolEngine
import dev.axmol.lib.AxmolGLSurfaceView
import dev.axmol.lib.AxmolRenderer

object CocosManager {
    private const val TAG = "CocosManager"
    private var glView: AxmolGLSurfaceView? = null

    // The activity CURRENTLY hosting the view. Tracked explicitly because the
    // view's own context always stays the CREATING activity — after a
    // re-parent onto a recreated activity, guarding onDestroy by view.context
    // matched the OLD activity and nulled the live view (black wedge again).
    private var hostActivity: Activity? = null

    // The axmol native engine (director, Lua VM, GL program cache) can only be
    // brought up ONCE per process — a second AxmolEngine.init on a fresh view
    // leaves the native scene bound to a dead context: black watchface, dead
    // bridge, no errors. Track it so activity recreation in a live process is
    // handled explicitly instead of silently wedging.
    private var engineInited = false

    fun init(activity: Activity): SurfaceView {
        glView?.let { existing ->
            // Activity recreated while the engine (and its view) are still
            // alive: re-parent the existing GL view into the new host instead
            // of re-initializing the native engine.
            Log.i(TAG, "Reusing live AxmolGLSurfaceView across activity recreation")
            (existing.parent as? ViewGroup)?.removeView(existing)
            // removeView delivers surfaceDestroyed synchronously; the caller's
            // addView on the new window delivers surfaceCreated/Changed once
            // that window draws, and GLSurfaceView rebinds its EGL surface to
            // it. No blind visibility cycling: a posted GONE destroys the
            // surface and nothing guarantees the paired VISIBLE ever lands
            // (observed live: no SurfaceView layer left in SurfaceFlinger,
            // engine rendering into the void). Just make sure a stray GONE
            // from any earlier state can't suppress the new surface.
            existing.visibility = android.view.View.VISIBLE
            hostActivity = activity
            return existing
        }
        if (engineInited) {
            // The engine ran in this process but its view is gone (the old
            // activity was fully destroyed). Native axmol cannot re-init in a
            // live process — the only clean recovery is a process restart.
            // Better a one-second relaunch than a permanently black launcher.
            Log.e(TAG, "Axmol engine already ran in this process and its view is gone — restarting for a clean engine")
            restartProcess(activity)
        }
        // libclox is loaded (companion init) — the native signal handler's JNI
        // entry point exists now, so C++ crashes surface in the crash reporter
        // like Java ones.
        NativeCrashHandler.install(activity.application)
        AxmolEngine.init(activity)
        val view = AxmolGLSurfaceView(activity)
        view.setEGLConfigChooser(5, 6, 5, 0, 16, 8)
        // Keep the EGL context (and all loaded textures/scenes) alive across
        // onPause/onResume. Without this, every time the launcher is backgrounded
        // (e.g. opening another app), GLSurfaceView destroys the GL context and
        // onResume must recreate it + reload every watchface asset before the first
        // frame — which is the visible delay when gesturing back home.
        // This mirrors what Axmol's stock AxmolActivity does.
        view.preserveEGLContextOnPause = true
        view.setRenderer(AxmolRenderer())
        glView = view
        hostActivity = activity
        engineInited = true
        Log.i(TAG, "AxmolGLSurfaceView initialized")
        return view
    }

    fun getGlView(): SurfaceView? = glView

    // Watchdogs must not treat a legitimately paused GL thread (screen off,
    // launcher backgrounded) as a dead renderer.
    @Volatile private var paused = false
    fun isPaused() = paused

    fun onResume() {
        paused = false
        glView?.let { v ->
            // Reconcile, don't fire-and-forget: whatever state a previous
            // window dance left behind, a resumed host needs the view VISIBLE
            // (a GONE SurfaceView has no compositor layer at all).
            if (v.visibility != android.view.View.VISIBLE) {
                Log.w(TAG, "onResume: GL view was ${v.visibility} — restoring VISIBLE")
                v.visibility = android.view.View.VISIBLE
            }
            v.onResume()
        }
    }
    fun onPause()   { paused = true; glView?.onPause() }

    /**
     * Forget the GL view — but ONLY if the dying activity is the one currently
     * HOSTING it. During recreation the OLD instance's onDestroy can run after
     * the NEW instance took the view (re-parent); nulling then orphans the
     * live view (every queueOnGL "skipped" forever) and black-wedges the
     * launcher. Do NOT compare view.context here — a re-parented view keeps
     * the creating activity as its context.
     */
    fun onDestroy(activity: Activity) {
        if (hostActivity === activity) {
            // The native engine can only init once per process, so the view is
            // once-per-process too: NULLING it here forced the next activity
            // create down the restartProcess path (Runtime.exit — which also
            // discards queued pref apply() writes, reverting a just-picked
            // face). Keep the view; detach it from the dying window so the
            // next host's init() can re-parent it.
            Log.i(TAG, "onDestroy from the hosting activity — detaching GL view, keeping it for the next host")
            glView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            hostActivity = null
        } else if (glView != null) {
            Log.i(TAG, "onDestroy from a stale activity instance — keeping the live GL view")
        }
    }

    /**
     * Last-resort recovery: relaunch MainActivity in a fresh process. Used when
     * the native engine is unrecoverable (re-init in a live process) and by the
     * renderer-dead dialog. The alarm fires after this process exits, so the
     * user sees a short relaunch instead of a black screen.
     */
    fun restartProcess(context: Context) {
        Log.e(TAG, "restartProcess — scheduling relaunch and exiting")
        try {
            val intent = Intent(context, com.ailife.clox.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(
                context, 42_017, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // set(), not setExact() — exact alarms need SCHEDULE_EXACT_ALARM on
            // API 31+. This is only a backstop anyway: Android auto-restarts the
            // process itself (home role / notification listener) in practice.
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        } catch (e: Exception) {
            Log.e(TAG, "restartProcess: failed to schedule relaunch", e)
        }
        Runtime.getRuntime().exit(0)
    }

    @JvmStatic external fun nativeSetBlurRadius(radius: Float)

    init { System.loadLibrary("clox") }
}
