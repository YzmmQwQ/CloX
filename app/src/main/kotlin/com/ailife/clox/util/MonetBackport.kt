package com.ailife.clox.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.ailife.clox.R

/**
 * Dynamic color for pre-S ROMs that backport the Monet system color
 * resources (system_accent1_*, system_neutral1_*, ...) — e.g. the Android 11
 * material color backport. Material's [com.google.android.material.color.DynamicColors]
 * refuses to run below API 31, so on those devices we apply
 * ThemeOverlay.App.Monet ourselves, but only after probing that the
 * framework actually resolves the backported resources.
 */
object MonetBackport {

    private const val TAG = "CloX/MonetBackport"

    val isAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            // onActivityPreCreated is only dispatched on Q+
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasBackportedMonet(com.ailife.clox.CloXApp.instance)
    }

    /** Mirrors DynamicColors.applyToActivitiesIfAvailable for the backport. */
    fun applyToActivitiesIfAvailable(app: Application) {
        if (!isAvailable) return
        Log.i(TAG, "Monet backport detected, applying ThemeOverlay.App.Monet to all activities")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.theme.applyStyle(R.style.ThemeOverlay_App_Monet, true)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Re-applies the overlay on a single activity. Needed after
     * installSplashScreen(): the postSplashScreenTheme swap force-reapplies
     * the base theme over the overlay set in onActivityPreCreated.
     */
    fun applyToActivityIfAvailable(activity: Activity) {
        if (!isAvailable) return
        activity.theme.applyStyle(R.style.ThemeOverlay_App_Monet, true)
    }

    // The system_accent1_500 constant needs compileSdk 31; on a plain API 30
    // framework the ID is absent at runtime and getColor throws. Backport
    // ROMs register the resources under the same public IDs, so resolving
    // successfully means the whole palette is present.
    @SuppressLint("NewApi")
    private fun hasBackportedMonet(context: Context): Boolean = try {
        context.getColor(android.R.color.system_accent1_500)
        true
    } catch (e: Resources.NotFoundException) {
        Log.i(TAG, "system_accent1_500 not present, no monet backport on this ROM")
        false
    }
}
