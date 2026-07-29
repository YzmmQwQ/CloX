package com.ailife.clox

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ailife.clox.crash.CrashHandler
import com.ailife.clox.overlay.global.GlobalOverlayService
import com.ailife.clox.qs.privilege.ShizukuOperations
import com.ailife.clox.util.MonetBackport
import com.google.android.material.color.DynamicColors
import rikka.shizuku.Shizuku

class CloXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DynamicColors.applyToActivitiesIfAvailable(this)
        MonetBackport.applyToActivitiesIfAvailable(this)
        CrashHandler.install(this)
        registerForegroundTracker()
        initShizuku()
    }

    // ── Foreground tracking (used by the global QS overlay) ────────────────────
    // Registered in Application.onCreate so it never misses the first activity resume.
    private val resumed = HashSet<Int>()
    private val fgHandler = Handler(Looper.getMainLooper())
    private val fgDebounce = Runnable {
        val fg = resumed.isNotEmpty()
        if (fg != foreground) {
            foreground = fg
            GlobalOverlayService.onForegroundAppChanged(fg)
        }
    }
    private fun scheduleFg() {
        fgHandler.removeCallbacks(fgDebounce)
        fgHandler.postDelayed(fgDebounce, 250L)
    }
    private fun registerForegroundTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed.add(System.identityHashCode(activity)); scheduleFg()
            }
            override fun onActivityPaused(activity: Activity) {
                resumed.remove(System.identityHashCode(activity)); scheduleFg()
            }
            override fun onActivityDestroyed(activity: Activity) {
                resumed.remove(System.identityHashCode(activity)); scheduleFg()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun initShizuku() {
        // Bind the Shizuku UserService as soon as Shizuku server is available
        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        }
        // If already available at startup
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: CloXApp
            private set

        /** True while any CloX activity is resumed (foreground). */
        @Volatile var foreground: Boolean = false
            private set
    }
}
