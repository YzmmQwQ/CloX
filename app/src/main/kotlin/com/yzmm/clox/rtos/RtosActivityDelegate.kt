package com.yzmm.clox.rtos

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.yzmm.clox.applist.AppListFragment
import com.yzmm.clox.gesture.GestureInterceptorLayout
import com.yzmm.clox.overlay.OverlayController
import com.yzmm.clox.overlay.notifications.NotificationOverlay
import com.yzmm.clox.rtos.qs.RtosQsOverlay

/**
 * Owns all RTOS enter/exit state for MainActivity.
 * MainActivity creates this once and delegates to it via thin callouts.
 */
class RtosActivityDelegate(
    private val activity: AppCompatActivity,
    private val gestureRoot: GestureInterceptorLayout,
    private val notificationOverlay: NotificationOverlay,
    private val overlayController: OverlayController,
    private val getAppListFragment: () -> AppListFragment?,
    private val closeAppList: () -> Unit,
    private val hideSystemBars: () -> Unit
) {
    private var unlockHandler: RtosUnlockHandler? = null
    private var qsOverlay: RtosQsOverlay? = null

    val isQsVisible get() = qsOverlay?.visibility == View.VISIBLE

    fun showQs() { qsOverlay?.visibility = View.VISIBLE }

    fun enter() {
        overlayController.dismissActive()
        closeAppList()   // always collapse app list on enter

        gestureRoot.rtosActive      = RtosManager.isWatchfaceOnly()
        gestureRoot.suppressLongPress = true

        unlockHandler = RtosUnlockHandler(activity) { RtosManager.exit(activity) }
        getAppListFragment()?.refreshApps()

        if (RtosManager.isFullMode()) {
            setFakeSettingsLauncher(true)
            notificationOverlay.rtosOverride = true
            if (qsOverlay == null) {
                val overlay = RtosQsOverlay(activity).apply {
                    visibility = View.GONE
                    onDismiss = { visibility = View.GONE }
                }
                gestureRoot.addView(overlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                qsOverlay = overlay
            }
        }
    }

    fun exit() {
        setFakeSettingsLauncher(false)
        gestureRoot.rtosActive        = false
        gestureRoot.suppressLongPress = false
        unlockHandler = null
        notificationOverlay.rtosOverride = false
        getAppListFragment()?.refreshApps()
        overlayController.dismissActive()

        qsOverlay?.let {
            it.visibility = View.GONE
            (it.parent as? FrameLayout)?.removeView(it)
        }
        qsOverlay = null
        hideSystemBars()
    }

    /** Feed touch to unlock handler (called from dispatchTouchEvent when RTOS active). */
    fun onTouch(ev: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (!isQsVisible) unlockHandler?.onTouch(ev, viewWidth, viewHeight)
    }

    private fun setFakeSettingsLauncher(enabled: Boolean) {
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            activity.packageManager.setComponentEnabledSetting(
                ComponentName(activity, "com.yzmm.clox.rtos.fakesettings.FakeSettingsLauncher"),
                state, PackageManager.DONT_KILL_APP
            )
        }
    }
}
