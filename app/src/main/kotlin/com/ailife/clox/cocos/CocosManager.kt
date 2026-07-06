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
        engineInited = true
        Log.i(TAG, "AxmolGLSurfaceView initialized")
        return view
    }

    fun getGlView(): SurfaceView? = glView

    // Watchdogs must not treat a legitimately paused GL thread (screen off,
    // launcher backgrounded) as a dead renderer.
    @Volatile private var paused = false
    fun isPaused() = paused

    fun onResume()  { paused = false; glView?.onResume() }
    fun onPause()   { paused = true; glView?.onPause() }

    /**
     * Forget the GL view — but ONLY if it still belongs to the activity being
     * destroyed. During recreation the OLD instance's onDestroy can run AFTER
     * the NEW instance registered its view; unconditionally nulling here
     * orphaned the live view (every queueOnGL "skipped — GL view not ready"
     * forever) and black-wedged the launcher with no error shown.
     */
    fun onDestroy(activity: Activity) {
        if ((glView?.context as? Activity) === activity) {
            glView = null
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
