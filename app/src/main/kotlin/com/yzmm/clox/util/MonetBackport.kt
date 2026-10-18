package com.yzmm.clox.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.yzmm.clox.R

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
            hasBackportedMonet(com.yzmm.clox.CloXApp.instance)
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

    // Every system_* tone that ThemeOverlay.App.Monet (light + night) maps a
    // color role onto. These constants need compileSdk 31; on a plain API 30
    // framework the IDs are absent at runtime and getColor throws. Some pre-S
    // ROMs ship only a *partial* backport (e.g. the W527: system_accent1_500
    // resolves but other tones don't), so probing a single resource isn't
    // enough — applying the overlay then crashes deep inside Material when it
    // resolves a role bound to a missing tone. Require the whole set.
    @get:SuppressLint("NewApi")
    private val requiredMonetColors: IntArray by lazy {
        intArrayOf(
            android.R.color.system_accent1_0, android.R.color.system_accent1_100,
            android.R.color.system_accent1_200, android.R.color.system_accent1_600,
            android.R.color.system_accent1_700, android.R.color.system_accent1_800,
            android.R.color.system_accent1_900,
            android.R.color.system_accent2_0, android.R.color.system_accent2_100,
            android.R.color.system_accent2_200, android.R.color.system_accent2_600,
            android.R.color.system_accent2_700, android.R.color.system_accent2_800,
            android.R.color.system_accent2_900,
            android.R.color.system_accent3_0, android.R.color.system_accent3_100,
            android.R.color.system_accent3_200, android.R.color.system_accent3_600,
            android.R.color.system_accent3_700, android.R.color.system_accent3_800,
            android.R.color.system_accent3_900,
            android.R.color.system_neutral1_0, android.R.color.system_neutral1_10,
            android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
            android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
            android.R.color.system_neutral2_50, android.R.color.system_neutral2_100,
            android.R.color.system_neutral2_200, android.R.color.system_neutral2_400,
            android.R.color.system_neutral2_500, android.R.color.system_neutral2_700,
        )
    }

    // Backport ROMs register the resources under the same public IDs, so every
    // tone resolving means the whole palette the overlay needs is present.
    // A single missing tone means the overlay can't be applied safely.
    private fun hasBackportedMonet(context: Context): Boolean {
        for (id in requiredMonetColors) {
            try {
                context.getColor(id)
            } catch (e: Resources.NotFoundException) {
                Log.i(TAG, "Monet tone ${Integer.toHexString(id)} not present, no usable monet backport on this ROM")
                return false
            }
        }
        return true
    }
}
