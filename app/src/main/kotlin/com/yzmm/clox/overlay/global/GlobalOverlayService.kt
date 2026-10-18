package com.yzmm.clox.overlay.global

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.yzmm.clox.R
import com.yzmm.clox.overlay.quicksettings.QuickSettingsOverlay
import com.yzmm.clox.qs.QsStateManager

/**
 * Foreground service hosting the global (draw-over-apps) QS. Uses ONE resizable overlay window
 * ([GlobalOverlayView]) that is a thin top strip when collapsed and full-screen when open, so a
 * single window owns the whole gesture (working velocity/fling, no cross-window cancellation).
 */
class GlobalOverlayService : Service(), GlobalOverlayView.Host {

    private lateinit var wm: WindowManager
    private lateinit var themedCtx: Context
    private var stateManager: QsStateManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: GlobalOverlayView? = null
    private var flashlight: View? = null

    private var ownForeground = true
    private var lastDismissAt = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        themedCtx = ContextThemeWrapper(this, R.style.Theme_CloX)
        stateManager = QsStateManager(this)
        ownForeground = com.yzmm.clox.CloXApp.foreground
        startAsForeground()
        Log.d(TAG, "service created (ownForeground=$ownForeground)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyPrefs()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "service destroyed")
        removeOverlay(); removeFlashlight()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "clox_global_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    channelId, getString(R.string.global_qs_title), NotificationManager.IMPORTANCE_MIN))
            }
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(getString(R.string.global_qs_notification))
            .setOngoing(true).build()
        startForeground(NOTIF_ID, notif)
    }

    // ── reconcile which window is present ──────────────────────────────────────
    fun applyPrefs() {
        // Rebuild so status-bar item/height changes take effect.
        removeOverlay()
        reconcile()
    }

    private fun reconcile() {
        val show = GlobalOverlayPrefs.globalEnabled(this) && Settings.canDrawOverlays(this) &&
                !ownForeground &&
                (GlobalOverlayPrefs.edgeSwipe(this) || GlobalOverlayPrefs.statusBarMode(this) != GlobalOverlayPrefs.SB_HIDDEN)
        if (show) addOverlayCollapsed() else removeOverlay()
    }

    private fun addOverlayCollapsed() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = GlobalOverlayView(themedCtx, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        runCatching {
            wm.addView(view, lp)
            overlay = view
            view.refreshCollapsedContent()
            Log.d(TAG, "overlay added (collapsed, h=${lp.height})")
        }.onFailure { Log.e(TAG, "addView overlay failed", it) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    /** Programmatic open (auto-replace). Ensures the window exists, then expands it. */
    fun openQuickSettings(@Suppress("UNUSED_PARAMETER") seedPx: Float = 0f) {
        if (ownForeground) return
        if (overlay == null) addOverlayCollapsed()
        overlay?.openFull()
    }

    // ── GlobalOverlayView.Host ─────────────────────────────────────────────────
    override fun mode(): String = GlobalOverlayPrefs.statusBarMode(this)

    override fun swipeRevealMs(): Long = GlobalOverlayPrefs.SWIPE_REVEAL_MS

    override fun createQs(): QuickSettingsOverlay {
        val qs = QuickSettingsOverlay(themedCtx)
        qs.softwareFlashlightHandler = { showFlashlight() }
        stateManager?.let { qs.init(it, null, null) { overlay?.dismiss() } }
        return qs
    }

    override fun expandWindow() {
        val v = overlay ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = dp(24)
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    override fun collapseWindow() {
        val v = overlay ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.height = stripHeightPx()
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lp.blurBehindRadius = 0
        runCatching { wm.updateViewLayout(v, lp) }
        lastDismissAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun setInteractive(on: Boolean) {
        val v = overlay ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.flags = if (on) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                   else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = dp(24)
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    override fun onOpened() { Log.d(TAG, "panel opened") }
    override fun onDismissed() { Log.d(TAG, "panel dismissed"); lastDismissAt = android.os.SystemClock.elapsedRealtime() }

    private fun stripHeightPx(): Int {
        val edge = dp(GlobalOverlayPrefs.edgeAreaDp(this).coerceIn(6, 120))
        val minForBar = if (GlobalOverlayPrefs.statusBarMode(this) == GlobalOverlayPrefs.SB_SHOW) dp(24) else 0
        return maxOf(edge, minForBar)
    }

    // ── Screen flashlight ───────────────────────────────────────────────────────
    fun showFlashlight() {
        if (flashlight != null) return
        val view = View(themedCtx).apply {
            setBackgroundColor(Color.WHITE)
            setOnClickListener { removeFlashlight() }
            isClickable = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP; screenBrightness = 1.0f }
        runCatching { wm.addView(view, lp); flashlight = view; Log.d(TAG, "flashlight shown") }
            .onFailure { Log.e(TAG, "addView flashlight failed", it) }
    }

    private fun removeFlashlight() {
        flashlight?.let { runCatching { wm.removeView(it) } }
        flashlight = null
    }

    fun setOwnForeground(own: Boolean) {
        if (ownForeground == own) return
        ownForeground = own
        Log.d(TAG, "CloX foreground=$own")
        if (own) { overlay?.dismiss(); removeOverlay() }
        reconcile()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "GlobalOverlaySvc"
        private const val NOTIF_ID = 4711

        @Volatile private var instance: GlobalOverlayService? = null

        fun start(context: Context) {
            if (!GlobalOverlayPrefs.globalEnabled(context)) return
            if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "start: no overlay permission"); return }
            val intent = Intent(context, GlobalOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, GlobalOverlayService::class.java))

        fun refresh(context: Context) {
            if (GlobalOverlayPrefs.globalEnabled(context) && Settings.canDrawOverlays(context)) {
                start(context); instance?.applyPrefs()
            } else stop(context)
        }

        fun onSystemQuickSettingsOpened() {
            val svc = instance ?: return
            if (!GlobalOverlayPrefs.autoReplace(svc)) return
            if (android.os.SystemClock.elapsedRealtime() - svc.lastDismissAt < 1500L) {
                Log.d(TAG, "auto-replace suppressed (recent dismiss)"); return
            }
            svc.openQuickSettings()
        }

        fun onForegroundAppChanged(ownAppForeground: Boolean) { instance?.setOwnForeground(ownAppForeground) }

        fun isPanelOpen(): Boolean = instance?.overlay?.isExpanded() == true
    }
}
