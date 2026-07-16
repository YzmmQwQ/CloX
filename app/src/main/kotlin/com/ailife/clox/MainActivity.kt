package com.ailife.clox

import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.ailife.clox.aod.AodManager
import com.ailife.clox.applist.AppContextMenu
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.qs.ScreenOffHelper
import com.ailife.clox.applist.AppListFragment
import com.ailife.clox.applist.IconPickerDialog
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.gesture.ButtonMappingHandler
import com.ailife.clox.gesture.GestureHandler
import com.ailife.clox.gesture.GestureInterceptorLayout
import com.ailife.clox.gesture.GestureLockManager
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.home.HomeFragment
import com.ailife.clox.overlay.OverlayController
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import android.appwidget.AppWidgetManager
import com.ailife.clox.overlay.widgets.WidgetsOverlay
import com.ailife.clox.overlay.widgets.WidgetHostManager
import com.ailife.clox.rtos.RtosActivityDelegate
import com.ailife.clox.rtos.RtosManager
import com.ailife.clox.rtos.RtosPrefs
import com.ailife.clox.util.safeStartActivityForResult
import dev.axmol.lib.AxmolEngine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AxmolEngine.AxmolEngineListener {

    companion object {
        const val ACTION_ENTER_RTOS = "com.ailife.clox.ACTION_ENTER_RTOS"
        // Set on the HOME intent used to bring the launcher forward before layering another
        // activity on top of it (e.g. the watchface config dialog from Settings) — without
        // this, whatever overlay/app-list was left open before backgrounding would still be
        // showing behind the new activity instead of the bare watchface.
        const val EXTRA_CLOSE_OVERLAYS = "com.ailife.clox.EXTRA_CLOSE_OVERLAYS"
        // Paired with EXTRA_CLOSE_OVERLAYS: open the watchface config dialog only AFTER the
        // reset above actually runs. Handled here (not as a second intent fired independently
        // from Settings) because this activity can be destroyed/recreated (onCreate, not
        // onNewIntent) even from a plain background/foreground cycle on memory-constrained
        // watches — a second, separately-fired intent has no way to wait for that.
        const val EXTRA_OPEN_WATCHFACE_CONFIG = "com.ailife.clox.EXTRA_OPEN_WATCHFACE_CONFIG"
        // Like EXTRA_OPEN_WATCHFACE_CONFIG but opens the app-wide defaults editor
        // (Settings > Watch Faces > Default Watch Face Settings) after the reset.
        const val EXTRA_OPEN_WATCHFACE_DEFAULTS = "com.ailife.clox.EXTRA_OPEN_WATCHFACE_DEFAULTS"
        // Like EXTRA_OPEN_WATCHFACE_CONFIG but opens the watch face picker/gallery after the
        // reset — used after importing a .watch file via ACTION_VIEW so the user lands on the
        // watch face page instead of whatever overlay/app-list was open before.
        const val EXTRA_OPEN_WATCHFACE_SELECTOR = "com.ailife.clox.EXTRA_OPEN_WATCHFACE_SELECTOR"
    }

    override fun showDialog(pTitle: String, pMessage: String) {}

    override fun applyOverrideConfiguration(config: Configuration) {
        val dpi = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("app_dpi_override", "0")?.toIntOrNull() ?: 0
        if (dpi > 0) config.densityDpi = dpi
        super.applyOverrideConfiguration(config)
    }

    private lateinit var gestureRoot: GestureInterceptorLayout
    private lateinit var overlayController: OverlayController
    private lateinit var quickSettings: QuickSettingsOverlay
    private lateinit var widgets: WidgetsOverlay
    private lateinit var notifications: NotificationOverlay
    private lateinit var flashlightOverlay: com.ailife.clox.overlay.quicksettings.FlashlightOverlay

    lateinit var stateManager: com.ailife.clox.qs.QsStateManager

    private var pendingWidgetId = -1
    private var pendingWidgetSlot: WidgetsOverlay.PendingSlot? = null

    private var appListFragment: AppListFragment? = null
    private var appListOpen = false
    private var appLaunchedFromList = false
    // True when the launcher was fully hidden (onStop) since its last onResume — i.e. another app
    // actually covered it. Distinguishes a return-from-app HOME delivery (was stopped) from a
    // genuine in-launcher HOME press (never stopped). Based on onStop, not onPause, because some
    // ROMs do a quick onPause/onResume on an in-launcher HOME press without ever stopping us.
    private var wasStoppedSinceResume = false
    private var lastLaunchedItem: AppItem? = null
    private var lastLaunchBounds: Rect? = null
    private var appListSpring: SpringAnimation? = null
    private var pendingGestureFollowOn = false
    // Path B: when set, the system is morphing the real closing window into a handed-off icon
    // surface; onResume must play NO in-app return / reveal animation.
    private var gestureHandoffActive = false
    private var activeHandoffSurface: AppCloseIconSurface? = null
    // Path A: whether the close remote-animation is registered on this activity instance.
    private var pathACloseRegistered = false
    private var lastLaunchIconView: java.lang.ref.WeakReference<View>? = null
    private var lastClosingPkg: String? = null   // package of the app currently returning to us
    // Set when the launcher brings ITSELF to the front (AOD re-wake), so the next onResume can skip
    // the return animation when the "no animation on self-close" option is on. Consumed in onResume.
    private var selfForegroundPending = false

    // When enabled, collapse the launcher back to the bare watchface as the screen turns off, so
    // it's already showing the watchface the next time the screen comes on.
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF &&
                PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    .getBoolean("reset_watchface_on_screen_on", false)) {
                resetToWatchface()
            }
        }
    }

    // ── Launch animation interruptible state ─────────────────────────────────
    private var activeLaunchAnim: android.animation.ValueAnimator? = null
    private var activeLaunchT: Float = 0f
    private var lastLaunchReverseAt = 0L         // uptimeMillis of the last launch-anim reverse
    private var lastLaunchReverseDurationMs = 0L // actual duration of that reverse anim (see reverseLaunchAnimation)
    private var launchAnimUpdateFn: ((Float) -> Unit)? = null
    private var launchAnimCleanupFn: (() -> Unit)? = null

    // The window-expand interceptor may reverse the launch anim on touch-down; the matching
    // HOME/BACK signal for that same gesture then arrives a beat later. Swallow it while the
    // reverse anim it triggered is still actually playing, so it doesn't fall through to
    // context menu / overlay / app-list dismissal. Uses the anim's real duration (which scales
    // with the user's animation-speed pref and how far the forward anim had progressed) rather
    // than a guessed constant, so it stays correct at any speed setting.
    private fun isLaunchAnimReversing(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastLaunchReverseAt < lastLaunchReverseDurationMs

    // Real-time blur of the app list behind the expanding/collapsing app window. Created at the
    // bottom of the animation's overlay stack so the window/card draws sharp on top of it.
    private fun addAppWindowBlur(): eightbitlab.com.blurview.BlurView? {
        if (!com.ailife.clox.applist.AppListPrefs.launchBlur(this)) return null
        val bv = eightbitlab.com.blurview.BlurView(this)
        gestureRoot.addView(bv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val algorithm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            eightbitlab.com.blurview.RenderEffectBlur()
        else
            @Suppress("DEPRECATION") eightbitlab.com.blurview.RenderScriptBlur(this)
        bv.setupWith(gestureRoot, algorithm).setBlurAutoUpdate(true)
        bv.setBlurEnabled(false)
        return bv
    }

    // radius in px (0 = no blur). Caps at the user's overlay blur radius for consistency.
    private fun setAppWindowBlur(bv: eightbitlab.com.blurview.BlurView?, fraction: Float) {
        bv ?: return
        val r = (com.ailife.clox.overlay.BlurEngine.getRadius(this) * fraction.coerceIn(0f, 1f))
        if (r < 1f) {
            bv.setBlurEnabled(false)
        } else {
            bv.setBlurEnabled(true)
            bv.setBlurRadius(r)
        }
    }

    // Path A (real-window morph) blur: a BlurView over the app list whose radius tracks the shared
    // morph progress (RemoteAppAnimator.morphExpansion), driven by a Choreographer tick. The real
    // window is a system layer above the launcher window, so it always draws sharp over this blur.
    // NOTE: the tick runs on CloX's main thread, which keeps rendering while resuming (close/return)
    // but pauses once CloX backgrounds on open — so the open blur is best-effort.
    private var pathABlur: eightbitlab.com.blurview.BlurView? = null
    private var blurFraction = 0f
    private var blurSession = 0
    private var blurGraceUntil = 0L

    // Drive the Path A morph blur off the shared morph progress. ONE BlurView is reused across morphs
    // (recreating it each time made the first RenderScript pass lag a frame → the flash); its radius
    // follows morphExpansion while a morph runs, and eases out when none does. The BlurView is created
    // only once a morph is actually in flight (so a HOME press with no close morph never blurs) and is
    // removed only after the blur has fully faded and a short grace elapses (so rapid open/close reuse
    // the same warm view). Bumping blurSession supersedes the previous tick, so re-entry is safe.
    fun startPathAMorphBlur() {
        if (!com.ailife.clox.applist.AppListPrefs.launchBlur(this)) return
        blurGraceUntil = android.os.SystemClock.uptimeMillis() + 500L
        val session = ++blurSession
        val choreo = android.view.Choreographer.getInstance()
        choreo.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (session != blurSession || isFinishing || isDestroyed) return
                val animating = com.ailife.clox.anim.RemoteAppAnimator.morphInFlight
                if (animating) {
                    if (pathABlur == null) pathABlur = addAppWindowBlur()
                    blurFraction = com.ailife.clox.anim.RemoteAppAnimator.morphExpansion.coerceIn(0f, 1f)
                } else {
                    // Ease the blur out smoothly instead of cutting it — avoids the flash on morph end.
                    blurFraction = (blurFraction - 0.08f).coerceAtLeast(0f)
                }
                pathABlur?.let { setAppWindowBlur(it, blurFraction) }
                val keepGoing = animating || blurFraction > 0.001f ||
                    android.os.SystemClock.uptimeMillis() < blurGraceUntil
                if (keepGoing) {
                    choreo.postFrameCallback(this)
                } else {
                    stopPathAMorphBlur()             // fully idle — drop the view until next time
                }
            }
        })
    }

    private fun stopPathAMorphBlur() {
        blurSession++                                // invalidate any running tick
        blurFraction = 0f
        pathABlur?.let { gestureRoot.removeView(it) }
        pathABlur = null
    }
    private lateinit var gestureHandler: GestureHandler
    private lateinit var buttonMappingHandler: ButtonMappingHandler
    private lateinit var aodManager: AodManager
    private lateinit var gestureLockIcon: ImageView

    // RTOS mode — all logic in RtosActivityDelegate
    private lateinit var rtosDelegate: RtosActivityDelegate

    // Real display bounds INCLUDING the system bar areas. resources.displayMetrics
    // subtracts the navigation bar even when it is hidden, which would make launch
    // animations stop short of the bar's region. We want the true full-screen size.
    private val screenW get() = realScreenSize().x.toFloat()
    private val screenH get() = realScreenSize().y.toFloat()

    private fun realScreenSize(): android.graphics.Point {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            return android.graphics.Point(b.width(), b.height())
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    // App-open splash: the animated app-icon X (same circular-wave AVD as the
    // watchface loader) with the CloX wordmark below, held briefly then faded
    // out. Only on a fresh cold launch — skipped on activity recreate.
    private fun setupSplash(savedInstanceState: Bundle?) {
        val splash = findViewById<android.view.View>(R.id.appSplash)
        if (savedInstanceState != null) {
            splash.visibility = android.view.View.GONE
            return
        }
        // Tint the wordmark's "X" brand-blue to echo the logo's blue sticks.
        val label = findViewById<android.widget.TextView>(R.id.appSplashText)
        val name = getString(R.string.app_name)
        val xi = name.lastIndexOf('X')
        if (xi >= 0) {
            label.text = android.text.SpannableString(name).apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFF82ABD1.toInt()),
                    xi, xi + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val sticks = findViewById<android.widget.ImageView>(R.id.appSplashSticks)
        (sticks.drawable as? android.graphics.drawable.Animatable)?.start()
        // Hold for ~one wave cycle, then fade out and remove.
        splash.postDelayed({
            splash.animate().alpha(0f).setDuration(350L)
                .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                .withEndAction {
                    (sticks.drawable as? android.graphics.drawable.Animatable)?.stop()
                    splash.visibility = android.view.View.GONE
                }.start()
        }, 1100L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded cold-start splash (X on black) via the system window, so the
        // launch is never blank-black while the Activity + Axmol GL warm up.
        // Hands off to the in-layout appSplash (X + CloX wordmark) below.
        installSplashScreen()
        enableEdgeToEdge()
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        com.ailife.clox.util.MonetBackport.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        hideSystemBars()
        setContentView(R.layout.activity_main)

        gestureRoot = findViewById(R.id.gestureRoot)
        setupSplash(savedInstanceState)
        setupAppListCover()

        if (savedInstanceState == null) {
            // HomeFragment hosts the Axmol SurfaceView (Lua watchface)
            supportFragmentManager.commit {
                add(R.id.homeContainer, HomeFragment(), "home")
            }
            val frag = AppListFragment()
            frag.onRequestClose = { closeAppList() }
            frag.onAppLaunched = { item, bounds, iconView, launch ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
                lastLaunchIconView = java.lang.ref.WeakReference(iconView)
                lastClosingPkg = item.packageName
                Log.d("CloX/Return", "onAppLaunched pkg=${item.packageName} bounds=$bounds")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    com.ailife.clox.applist.AppListPrefs.openRealWindow(this)) {
                    // Path A: the system runs the real app-window open morph — no in-app anim.
                    launch()
                } else if (com.ailife.clox.applist.AppListPrefs.useCustomLaunchAnim(this)) {
                    playLaunchAnimation(item, bounds, launch)
                } else {
                    launch()
                }
            }
            frag.onPagesBackendActive = { active -> gestureRoot.pagesBackendActive = active }
            frag.onSetPagesCanScrollRight = { fn -> gestureRoot.pagesCanScrollRight = fn }
            frag.onSetPagesDragActive = { fn -> gestureRoot.pagesBackendDragActive = fn }
            frag.onSetGrid2dHoneycombActive = { active -> gestureRoot.grid2dHoneycombActive = active }
            appListFragment = frag
            supportFragmentManager.commit { add(R.id.fragmentContainer, frag, "app_list") }
        } else {
            appListFragment = (supportFragmentManager
                .findFragmentByTag("app_list") as? AppListFragment)?.also { frag ->
                    frag.onRequestClose = { closeAppList() }
                    frag.onAppLaunched = { item, bounds, iconView, launch ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
                lastLaunchIconView = java.lang.ref.WeakReference(iconView)
                lastClosingPkg = item.packageName
                Log.d("CloX/Return", "onAppLaunched pkg=${item.packageName} bounds=$bounds")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    com.ailife.clox.applist.AppListPrefs.openRealWindow(this)) {
                    // Path A: the system runs the real app-window open morph — no in-app anim.
                    launch()
                } else if (com.ailife.clox.applist.AppListPrefs.useCustomLaunchAnim(this)) {
                    playLaunchAnimation(item, bounds, launch)
                } else {
                    launch()
                }
            }
                    frag.onPagesBackendActive = { active -> gestureRoot.pagesBackendActive = active }
                    frag.onSetPagesCanScrollRight = { fn -> gestureRoot.pagesCanScrollRight = fn }
                    frag.onSetPagesDragActive = { fn -> gestureRoot.pagesBackendDragActive = fn }
                    frag.onSetGrid2dHoneycombActive = { active -> gestureRoot.grid2dHoneycombActive = active }
                }
            appListOpen = savedInstanceState.getBoolean("app_list_open", false)
        }

        com.ailife.clox.cocos.RenderPolicy.load(this)
        gestureRoot.post {
            appListFragment?.view?.translationX = if (appListOpen) 0f else screenW
            appListFragment?.setActive(appListOpen)
            gestureRoot.appListOpen = appListOpen
            setAppListCoverShown(appListOpen)   // restored directly into settled-open
            // restored directly into the open state (no spring end listener runs)
            dev.axmol.lib.AxmolRenderer.setForceLowFps(
                appListOpen && com.ailife.clox.cocos.RenderPolicy.dynamicFps)
        }

        quickSettings = findViewById(R.id.quickSettingsOverlay)
        widgets = findViewById(R.id.widgetsOverlay)
        notifications = findViewById(R.id.notificationsOverlay)
        flashlightOverlay = findViewById(R.id.flashlightOverlay)
        flashlightOverlay.onDismiss = { /* nothing needed */ }

        overlayController = OverlayController(
            window, quickSettings, widgets, notifications,
            findViewById(R.id.overlayScrim),
            findViewById(R.id.blurLayer)
        ).also { ctrl ->
            ctrl.onDismissed = { updateBackEnabled() }
            ctrl.onShown = { updateBackEnabled() }
        }

        stateManager = com.ailife.clox.qs.QsStateManager(this)
        stateManager.attach()   // always active; QS panel attach/detach add/remove their own hold
        quickSettings.init(stateManager, supportFragmentManager, flashlightOverlay) {
            overlayController.dismissActive()
        }

        notifications.init(supportFragmentManager)
        widgets.init(supportFragmentManager)
        setupWidgetCallbacks()
        setupBackPressHandler()

        gestureHandler = GestureHandler(this, window)
        gestureHandler.onCollapseToWatchface = { resetToWatchface() }
        buttonMappingHandler = ButtonMappingHandler(this, gestureHandler)
        gestureLockIcon = findViewById(R.id.gestureLockIcon)
        setupGestures()
        setupGestureLock()

        aodManager = AodManager(
            activity = this,
            window = window,
            onCollapseOverlays = {
                // This collapse is always launcher-initiated (the AOD engine dims/sleeps), so drop
                // straight to the watchface with no slide/collapse animation.
                resetToWatchface()
            },
            onWillForeground = { selfForegroundPending = true }
        )
        aodManager.start()
        gestureHandler.onScreenOffWhileAod = { aodManager.enterAodNow() }

        rtosDelegate = RtosActivityDelegate(
            activity             = this,
            gestureRoot          = gestureRoot,
            notificationOverlay  = notifications,
            overlayController    = overlayController,
            getAppListFragment   = { appListFragment },
            closeAppList         = { if (appListOpen) closeAppList() },
            hideSystemBars       = { hideSystemBars() }
        )

        // Observe RTOS mode changes — drop(1) skips the initial StateFlow value so
        // onRtosExited() doesn't fire during onCreate before fragments are attached.
        lifecycleScope.launch {
            RtosManager.active.drop(1).collect { active ->
                if (active) onRtosEntered() else onRtosExited()
            }
        }

        // Restore RTOS active state after process death — post so collector above is ready first
        gestureRoot.post { RtosManager.restoreIfNeeded(this) }

        // Open notification panel automatically when a new notification arrives (if enabled)
        lifecycleScope.launch {
            CloXNotificationListenerService.openPanelEvent.collect {
                if (!overlayController.hasActiveOverlay()) {
                    // Dismiss AOD state so the notification is visible
                    if (aodManager.aodActive) {
                        GestureLockManager.unlockAod()
                        aodManager.onAodGestureUnlocked()
                    }
                    overlayController.startDrag(SwipeDirection.RIGHT, screenW, screenH)
                    overlayController.commitDrag()
                    // onShown fires from commitDrag → updateBackEnabled() already called
                }
            }
        }

        registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))

        handleCloseOverlaysIntent(intent)
    }

    // Handles EXTRA_CLOSE_OVERLAYS (+ optional EXTRA_OPEN_WATCHFACE_CONFIG) on a HOME intent —
    // called from both onCreate and onNewIntent since this activity can be recreated from
    // scratch (onCreate) even for a plain background/foreground cycle on memory-constrained
    // watches, and the reset must run before the config dialog is opened on top of it.
    private fun handleCloseOverlaysIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_MAIN ||
            intent.categories?.contains(Intent.CATEGORY_HOME) != true) return
        if (!intent.getBooleanExtra(EXTRA_CLOSE_OVERLAYS, false)) return
        resetToWatchface()
        if (intent.getBooleanExtra(EXTRA_OPEN_WATCHFACE_CONFIG, false)) {
            com.ailife.clox.watchface.config.WatchfaceConfigActivity.start(this)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_WATCHFACE_DEFAULTS, false)) {
            com.ailife.clox.watchface.config.WatchfaceConfigActivity.startDefaults(this)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_WATCHFACE_SELECTOR, false)) {
            com.ailife.clox.watchface.selector.WatchfaceSelectorActivity.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == ACTION_ENTER_RTOS) {
            if (!RtosManager.isActive) RtosManager.enter(this)
            return
        }

        // Android 11+: system sends GestureNavContract so we can provide the icon rect
        // and the system animates the closing app window to it. Must be processed before
        // removing the EXTRA_GESTURE_CONTRACT from the intent (fromIntent does that).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hadContractExtra = intent.hasExtra(GestureNavContract.EXTRA_GESTURE_CONTRACT)
            val contract = GestureNavContract.fromIntent(intent)
            Log.d("CloX/Return", "onNewIntent action=${intent.action} cats=${intent.categories} " +
                "hasContractExtra=$hadContractExtra contract=${contract != null}")
            contract?.let { handleGestureContract(it) }
        } else {
            Log.d("CloX/Return", "onNewIntent action=${intent.action} cats=${intent.categories} (pre-R)")
        }

        // Only handle HOME button presses for our own dismiss logic
        if (intent.action != Intent.ACTION_MAIN ||
            intent.categories?.contains(Intent.CATEGORY_HOME) != true) return

        if (intent.getBooleanExtra(EXTRA_CLOSE_OVERLAYS, false)) {
            handleCloseOverlaysIntent(intent)
            return
        }

        // If launch animation is still playing, reverse it instead of proceeding
        if (activeLaunchAnim != null) {
            appLaunchedFromList = false
            reverseLaunchAnimation()
            return
        }
        // The window-expand interceptor reverses the launch anim the instant the HOME gesture's
        // touch lands; the matching HOME intent then arrives a beat later. Swallow it so it
        // doesn't propagate to closeAppList — the reverse already handled the gesture.
        if (isLaunchAnimReversing()) return

        // If the launcher was NOT in the foreground when this HOME arrived, then an app was
        // actually on top and this is a return-to-launcher — not an in-launcher Home press.
        // Consume it (don't dismiss layers / screen off), regardless of how the app was started
        // (app list, recents, notification, chained start). onResume will flip isInForeground.
        Log.d("CloX/Return", "HOME press wasStopped=$wasStoppedSinceResume appListOpen=$appListOpen " +
            "appLaunchedFromList=$appLaunchedFromList hasOverlay=${overlayController.hasActiveOverlay()}")
        if (wasStoppedSinceResume || appLaunchedFromList) {
            appLaunchedFromList = false
            return
        }

        // User pressed Home while already inside the launcher — dismiss outermost layer
        when {
            AppContextMenu.isShowing -> AppContextMenu.dismissCurrent()
            overlayController.hasActiveOverlay() -> overlayController.dismissActive()
            appListOpen -> closeAppList()
            else -> {
                if (PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean("home_press_screen_off", false)) {
                    if (!ScreenOffHelper.lock(this)) {
                        ScreenOffHelper.requestPermission(this)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("app_list_open", appListOpen)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // Window settled after a resume transition: re-assert the app-list
            // 1 FPS cap that onResume lifted for the window-sync handshake.
            if (appListOpen && com.ailife.clox.cocos.RenderPolicy.dynamicFps) {
                dev.axmol.lib.AxmolRenderer.setForceLowFps(true)
            }
        }
    }

    // Status bar is always hidden. The navigation bar follows the "nav_bar_mode" gesture
    // setting: hidden / always shown / shown only while the app list is open. RTOS mode
    // always hides it (full-screen lock). Call this whenever the app-list state changes.
    internal fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())

        val mode = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("nav_bar_mode", "applist") ?: "applist"
        val showNav = !RtosManager.isActive && when (mode) {
            "show"    -> true
            "applist" -> appListOpen
            else      -> false   // "hidden"
        }
        if (showNav) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            aodManager.onTouch()
            AppContextMenu.dismissCurrent()
        }
        val result = super.dispatchTouchEvent(ev)
        if (RtosManager.isActive) rtosDelegate.onTouch(ev, gestureRoot.width, gestureRoot.height)
        return result
    }

    override fun onResume() {
        super.onResume()
        // Capture BEFORE resetting: did an app actually cover us since the last resume? Used to
        // drive usage-stats closing-app detection on Back/Home returns even when we have no
        // remembered launched item.
        val cameFromBackground = wasStoppedSinceResume
        // Did WE bring ourselves to the front (AOD re-wake) rather than the user gesturing back?
        // If so, skip the return/reveal animation and land instantly — a self-initiated foreground
        // should never replay the app's return animation.
        val selfForeground = selfForegroundPending
        selfForegroundPending = false
        val skipSelfAnim = selfForeground
        Log.d("CloX/Return", "onResume (reset wasStopped=false, cameFromBackground=$cameFromBackground selfForeground=$selfForeground skipSelfAnim=$skipSelfAnim)")
        wasStoppedSinceResume = false
        CocosManager.onResume()
        // Restart face videos stopped by WatchfaceOnHostPause (no-op if none / dim)
        (CocosManager.getGlView() as? android.opengl.GLSurfaceView)?.queueEvent {
            runCatching { com.ailife.clox.cocos.LuaBridge.callLuaFunction("WatchfaceOnHostResume", "") }
        }
        // Restore periodic bridge data + sensors, and resync everything missed
        (supportFragmentManager.findFragmentByTag("home") as? com.ailife.clox.home.HomeFragment)
            ?.onHostResume()
        aodManager.onResume()
        // The resume window sync (SurfaceSyncGroup) waits for a fresh frame from
        // the GL SurfaceView; a 1 FPS-capped or idle-clamped face makes it hit
        // its 1000 ms timeout and stall the first traversal for a full second
        // (Choreographer "Skipped ~60 frames" on every resume with the app list
        // open). Lift the cap and run boosted through the transition — the cap
        // is re-asserted in onWindowFocusChanged once the window has settled.
        dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
        dev.axmol.lib.AxmolRenderer.boostFrames(1.5f)
        // Safety net: the cover only belongs under a settled-open app list
        if (!appListOpen) setAppListCoverShown(false)
        // A panel (notifications / QS / widgets) left open before the screen turned off should
        // not linger on resume — drop it instantly so we land on the watchface.
        if (overlayController.hasActiveOverlay()) overlayController.dismissActiveInstant()

        // Defer non-critical UI operations to after the first frame so the
        // watchface GL surface can render without competing for UI thread time.
        gestureRoot.post {
            DefaultLauncherManager(this@MainActivity).checkAndPromptIfNeeded()
            com.ailife.clox.qs.QsPermissionHelper.requestMissing(this@MainActivity)
            com.ailife.clox.watchface.WatchfaceAutoChanger.checkOnResume(this@MainActivity, lifecycleScope)
        }

        // Auto-enter RTOS on resume after screen off (if enabled and not already active)
        if (!RtosManager.isActive && RtosPrefs.isEnabled(this) &&
            RtosPrefs.autoEnter(this) && RtosPrefs.passwordHash(this) != null) {
            RtosManager.enter(this)
        }

        // Safety-net: clear flag on any resume (covers Back-button return, not just Home)
        appLaunchedFromList = false

        // Path A: register the close remote-animation once so the system hands us the real closing
        // window leash on app→launcher transitions (shrinks it back into the launched icon rect).
        if (com.ailife.clox.applist.AppListPrefs.closeRealWindow(this) && !pathACloseRegistered) {
            com.ailife.clox.anim.RemoteAppAnimator.registerClose(this,
                speed = { opening ->
                    if (opening) com.ailife.clox.applist.AppListPrefs.animSpeed(this)
                    else com.ailife.clox.applist.AppListPrefs.closeAnimSpeed(this)
                }) { lastLaunchBounds }
            pathACloseRegistered = true
        }

        // Path B icon-morph (swipe-home with the surface toggle): the system morphs the closing
        // window into the icon surface we handed it in handleGestureContract. We play NO in-app
        // return animation (the surface owns that), but we DO play the app-list reveal so the list
        // fades / circles in over its background — the same finish as a normal gesture return. The
        // reveal veil sits below the morph (the icon SurfaceView is ZOrderOnTop + the closing window
        // is a system layer, both above the launcher window), so the morph stays visible over it.
        // Checked before Path A because for a swipe-home gesture the contract handoff — not the
        // registered close — owns the return.
        if (gestureHandoffActive) {
            gestureHandoffActive = false
            val revealBounds = lastLaunchBounds
            lastLaunchedItem = null; lastLaunchBounds = null; lastLaunchIconView = null
            pendingGestureFollowOn = false
            val playReveal = !skipSelfAnim && appListOpen &&
                com.ailife.clox.applist.AppListPrefs.gestureReturnEnabled(this)
            Log.d("CloX/Return", "onResume handoff+reveal playReveal=$playReveal bounds=$revealBounds")
            if (playReveal && revealBounds != null) {
                val veil = showGestureReturnVeil()
                gestureRoot.post { playGestureReturnReveal(veil, revealBounds) }
            }
            return
        }

        // Path A: the registered close remote-animation morphs the real window back into the icon —
        // do NOT play the in-app return animation, and do NOT suppress the system transition (which
        // would kill the morph). Keep lastLaunchBounds so the close runner can target the icon.
        if (com.ailife.clox.applist.AppListPrefs.closeRealWindow(this)) {
            lastLaunchedItem = null; lastLaunchIconView = null
            pendingGestureFollowOn = false
            // Blur the app list behind the shrinking real window, de-blurring as it collapses to the
            // icon (blur tracks morph progress). CloX is resuming here, so the tick renders.
            startPathAMorphBlur()
            Log.d("CloX/Return", "onResume Path A — system close morph owns the return; skip in-app")
            return
        }

        // Back / hardware-Home returns send no gesture-nav contract. When enabled, detect the
        // app we're actually returning from via usage stats and animate ITS icon — even when we
        // have no remembered launched item (Back from a recents/notification app, or after the
        // item was consumed). Gated on actually having returned from a foreground app
        // (cameFromBackground) so a plain screen-wake never animates spuriously.
        if (!pendingGestureFollowOn && (lastLaunchedItem != null || cameFromBackground) &&
            com.ailife.clox.applist.AppListPrefs.detectClosingApp(this)) {
            val pkg = com.ailife.clox.util.ClosingAppDetector.lastForegroundPackage(this)
            Log.d("CloX/Return", "onResume usage-detect pkg=$pkg cameFromBackground=$cameFromBackground item=${lastLaunchedItem?.packageName}")
            if (pkg != null && pkg != packageName) resolveAndApplyClosingApp(pkg)
        }

        val item = lastLaunchedItem; val bounds = lastLaunchBounds
        Log.d("CloX/Return", "onResume item=${item?.packageName} bounds=$bounds " +
            "pendingGesture=$pendingGestureFollowOn appListOpen=$appListOpen " +
            "detect=${com.ailife.clox.applist.AppListPrefs.detectClosingApp(this)} " +
            "gestureReturn=${com.ailife.clox.applist.AppListPrefs.gestureReturnEnabled(this)}")
        if (item != null && bounds != null) {
            lastLaunchedItem = null; lastLaunchBounds = null
            suppressSystemTransition()
            // The close animation plays into the app's icon slot when the list is open. When
            // the list is NOT open there's no slot, so it plays toward screen centre — only
            // do that when the user has opted in via the resume-close-anim pref; otherwise
            // skip it so we land straight on the watchface.
            val returnEnabled = !skipSelfAnim &&
                com.ailife.clox.applist.AppListPrefs.returnAnimEnabled(this) &&
                (appListOpen || com.ailife.clox.applist.AppListPrefs.resumeCloseAnim(this))
            // Reveal the open app list over its background image (fade / circle from the icon).
            // "Only on gesture" restricts it to the swipe-home gesture (contract) return.
            val onlyOnGesture = com.ailife.clox.applist.AppListPrefs.gestureReturnOnlyGesture(this)
            val playReveal = !skipSelfAnim && appListOpen &&
                com.ailife.clox.applist.AppListPrefs.gestureReturnEnabled(this) &&
                (!onlyOnGesture || pendingGestureFollowOn)
            // The reveal (fade / circle-in of the app list) must play AFTER the return animation
            // (icon collapse, or the gesture recoil) has finished — not at the same time. The veil
            // cover is added NOW (under the return-anim overlays) so the app list stays hidden the
            // whole time; the reveal itself is chained on the return anim's completion. If the
            // return anim is disabled, reveal immediately.
            val veil = if (playReveal) showGestureReturnVeil() else null
            val reveal: (() -> Unit)? = veil?.let { v -> { gestureRoot.post { playGestureReturnReveal(v, bounds) } } }
            when {
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> {
                    Log.d("CloX/Return", "branch=pre-Q playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (returnEnabled) gestureRoot.post { playReturnAnimation(item, bounds, reveal) }
                    else reveal?.invoke()
                }
                pendingGestureFollowOn -> {
                    pendingGestureFollowOn = false
                    Log.d("CloX/Return", "branch=gestureFollowOn playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (returnEnabled) gestureRoot.post { playGestureFollowOnAnimation(item, bounds, veil, reveal) }
                    else { lastLaunchIconView = null; reveal?.invoke() }
                }
                else -> {
                    Log.d("CloX/Return", "branch=else(11+,no-contract) playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (returnEnabled) gestureRoot.post { playReturnAnimation(item, bounds, reveal) }
                    else reveal?.invoke()
                }
            }
        } else {
            Log.d("CloX/Return", "onResume no pending return (item/bounds null) — no animation")
            pendingGestureFollowOn = false
            lastLaunchIconView = null
        }
    }

    private fun suppressSystemTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun handleGestureContract(contract: GestureNavContract) {
        val pkg = contract.componentName.packageName
        if (resolveAndApplyClosingApp(pkg)) {
            if (com.ailife.clox.applist.AppListPrefs.gestureHandoffSurface(this)) {
                // Path B: hand the system a real icon surface; it morphs the closing window into it.
                // Reply with the target rect SYNCHRONOUSLY now, so Launcher3's spring aims straight at
                // the icon from frame 0 (no centre default). The SurfaceView then sends the SAME rect
                // plus its surface a frame later from surfaceCreated(); since the target is unchanged,
                // that second reply only attaches the icon graphic — no centre-detour 2-step.
                val item = lastLaunchedItem!!; val bounds = Rect(lastLaunchBounds!!)
                Log.d("CloX/Return", "contract handoff pkg=$pkg bounds=$bounds (sync rect + late surface)")
                contract.sendEndPosition(RectF(bounds))
                activeHandoffSurface?.remove()
                activeHandoffSurface = AppCloseIconSurface.show(
                    this, gestureRoot, resolveHeroIcon(item), bounds, contract,
                    lastLaunchIconView?.get())
                gestureHandoffActive = true
                pendingGestureFollowOn = false
            } else {
                pendingGestureFollowOn = true
                Log.d("CloX/Return", "contract pkg=$pkg target=$lastLaunchBounds")
                contract.sendEndPosition(RectF(lastLaunchBounds!!))
            }
        } else {
            // Unknown app (no launchable component) — collapse to centre, no custom anim.
            lastLaunchedItem = null; lastLaunchBounds = null; lastLaunchIconView = null
            pendingGestureFollowOn = false
            Log.d("CloX/Return", "contract pkg=$pkg unresolved -> centre")
            val cx = screenW / 2f; val cy = screenH / 2f
            contract.sendEndPosition(RectF(cx, cy, cx, cy))
        }
    }

    /**
     * Points lastLaunched{Item,Bounds,IconView} at the app [pkg] that is actually closing back
     * to the launcher (may differ from the app we launched — chained starts, app switching).
     * Prefers the live on-screen icon in the open app list; falls back to the launch icon when
     * it's the same app, else to screen centre. Returns false only if [pkg] has no launchable
     * component and isn't in our list (caller then collapses to centre with no animation).
     */
    private fun resolveAndApplyClosingApp(pkg: String): Boolean {
        val launchedItem = lastLaunchedItem
        val launchedBounds = lastLaunchBounds
        val wasLaunchedApp = launchedItem != null && launchedItem.packageName == pkg

        val hit = if (appListOpen) appListFragment?.returnIconHit(pkg) else null

        val closingItem = hit?.item
            ?: launchedItem?.takeIf { wasLaunchedApp }
            ?: buildClosingAppItem(pkg)
            ?: return false

        val liveBounds = hit?.bounds ?: launchedBounds?.takeIf { wasLaunchedApp }
        val liveIconView = hit?.iconView ?: (if (wasLaunchedApp) lastLaunchIconView?.get() else null)
        val cx = (screenW / 2f).toInt(); val cy = (screenH / 2f).toInt()

        lastLaunchedItem = closingItem
        lastLaunchBounds = liveBounds ?: Rect(cx, cy, cx, cy)
        lastLaunchIconView = liveIconView?.let { java.lang.ref.WeakReference(it) }
        lastClosingPkg = pkg
        Log.d("CloX/Return", "resolve pkg=$pkg wasLaunched=$wasLaunchedApp hitItem=${hit?.item != null} " +
            "hitBounds=${hit?.bounds} appListOpen=$appListOpen -> bounds=$lastLaunchBounds")
        return true
    }

    /** Builds a minimal AppItem for an app that is closing but isn't in the launcher list. */
    private fun buildClosingAppItem(pkg: String): AppItem? {
        // Resolve the icon via the launcher activity (matches how the app list renders it).
        val activity = packageManager.getLaunchIntentForPackage(pkg)?.component?.className ?: return null
        return try {
            val icon = com.ailife.clox.icon.IconResolver.resolve(this, pkg, activity)
            AppItem(packageName = pkg, activityName = activity, label = pkg, icon = icon)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * item.icon is the downsampled (96dp) list icon — it looks blocky when scaled up to the
     * hero size. Resolve a fresh full-resolution icon for launch/return animations.
     */
    private fun resolveHeroIcon(item: AppItem): android.graphics.drawable.Drawable = try {
        com.ailife.clox.icon.IconResolver.resolve(this, item.packageName, item.activityName)
    } catch (_: Exception) {
        item.icon
    }

    private fun playReturnAnimation(item: AppItem, iconBounds: Rect, onDone: (() -> Unit)? = null) {
        val type = com.ailife.clox.applist.AppListPrefs.closeAnimType(this)
        if (type == com.ailife.clox.applist.AppListPrefs.CLOSE_ANIM_WINDOW_EXPAND) {
            playReturnWindowExpandAnimation(item, iconBounds, onDone)
        } else {
            playReturnIconZoomAnimation(item, iconBounds, onDone)
        }
    }

    private fun playReturnIconZoomAnimation(item: AppItem, iconBounds: Rect, onDone: (() -> Unit)? = null) {
        val sw = screenW.toInt(); val sh = screenH.toInt()
        val speed = com.ailife.clox.applist.AppListPrefs.closeAnimSpeed(this)
        val delay = com.ailife.clox.applist.AppListPrefs.closeAnimDelay(this).toLong()

        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)
        val iconCxLocal = iconBounds.centerX() - rootLoc[0]
        val iconCyLocal = iconBounds.centerY() - rootLoc[1]
        val iconW = iconBounds.width().coerceAtLeast(1)
        val iconH = iconBounds.height().coerceAtLeast(1)

        val heroSize = (minOf(sw, sh) * 0.40f).toInt().coerceAtLeast(1)
        val startCx = sw / 2f; val startCy = sh / 2f

        val blur = addAppWindowBlur()

        // Add views immediately at t=0 state so they're visible as soon as system transition ends
        val scrim = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 1f
        }
        gestureRoot.addView(scrim, FrameLayout.LayoutParams(sw, sh))

        val iv = ImageView(this).apply {
            setImageDrawable(resolveHeroIcon(item))
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            pivotX = heroSize / 2f; pivotY = heroSize / 2f
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(heroSize, heroSize).apply {
            leftMargin = (startCx - heroSize / 2f).toInt()
            topMargin  = (startCy - heroSize / 2f).toInt()
        })

        val realIcon = lastLaunchIconView?.get()
        lastLaunchIconView = null
        realIcon?.visibility = View.INVISIBLE

        val targetScaleX = iconW.toFloat() / heroSize
        val targetScaleY = iconH.toFloat() / heroSize
        val tx = iconCxLocal - startCx
        val ty = iconCyLocal - startCy

        // Delay animator start until the system back-transition has finished rendering over us
        gestureRoot.postDelayed({
            if (isFinishing || isDestroyed) {
                gestureRoot.removeView(iv); gestureRoot.removeView(scrim)
                blur?.let { gestureRoot.removeView(it) }
                realIcon?.visibility = View.VISIBLE
                onDone?.invoke()
                return@postDelayed
            }
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (350L / speed).toLong()
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    iv.scaleX = 1f - (1f - targetScaleX) * t
                    iv.scaleY = 1f - (1f - targetScaleY) * t
                    iv.translationX = tx * t
                    iv.translationY = ty * t
                    scrim.alpha = 1f - t
                    setAppWindowBlur(blur, 1f - t)   // de-blur as the window shrinks back to the icon
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        gestureRoot.removeView(iv)
                        gestureRoot.removeView(scrim)
                        blur?.let { gestureRoot.removeView(it) }
                        realIcon?.visibility = View.VISIBLE
                        onDone?.invoke()
                    }
                })
                start()
            }
        }, delay)
    }

    private fun playReturnWindowExpandAnimation(item: AppItem, iconBounds: Rect, onDone: (() -> Unit)? = null) {
        val sw = screenW; val sh = screenH
        val speed = com.ailife.clox.applist.AppListPrefs.closeAnimSpeed(this)
        val delay = com.ailife.clox.applist.AppListPrefs.closeAnimDelay(this).toLong()

        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)
        val iconLeft = (iconBounds.left - rootLoc[0]).toFloat()
        val iconTop  = (iconBounds.top  - rootLoc[1]).toFloat()
        val iconW    = iconBounds.width().coerceAtLeast(1)
        val iconH    = iconBounds.height().coerceAtLeast(1)
        val iconCx   = iconLeft + iconW / 2f
        val iconCy   = iconTop  + iconH / 2f

        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(
            gestureRoot,
            com.google.android.material.R.attr.colorSurface,
            android.graphics.Color.BLACK
        )
        val cornerRadiusPx = 56f * resources.displayMetrics.density

        // Blur of the app list, beneath the collapsing card so the window stays sharp on top.
        val blur = addAppWindowBlur()

        // Add views immediately at t=0 state so they're visible as soon as system transition ends
        val cardView = WindowCardView(this).apply { cardColor = surfaceColor }
        cardView.update(0f, 0f, sw, sh, 0f)
        gestureRoot.addView(cardView, FrameLayout.LayoutParams(sw.toInt(), sh.toInt()))

        val heroSize  = minOf(sw, sh) * 0.40f
        val heroScale = heroSize / minOf(iconW.toFloat(), iconH.toFloat())
        val startCx = sw / 2f; val startCy = sh / 2f

        val iv = ImageView(this).apply {
            setImageDrawable(resolveHeroIcon(item))
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            pivotX = iconW / 2f; pivotY = iconH / 2f
            scaleX = heroScale; scaleY = heroScale
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(iconW, iconH).apply {
            leftMargin = (startCx - iconW / 2f).toInt()
            topMargin  = (startCy - iconH / 2f).toInt()
        })

        val realIcon = lastLaunchIconView?.get()
        lastLaunchIconView = null
        realIcon?.visibility = View.INVISIBLE

        val endTx = iconCx - startCx
        val endTy = iconCy - startCy

        // Delay animator start until the system back-transition has finished rendering over us
        gestureRoot.postDelayed({
            if (isFinishing || isDestroyed) {
                gestureRoot.removeView(iv); gestureRoot.removeView(cardView)
                blur?.let { gestureRoot.removeView(it) }
                realIcon?.visibility = View.VISIBLE
                onDone?.invoke()
                return@postDelayed
            }
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (350L / speed).toLong()
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    cardView.update(
                        left   = iconCx * t,
                        top    = iconCy * t,
                        right  = iconCx + (sw - iconCx) * (1f - t),
                        bottom = iconCy + (sh - iconCy) * (1f - t),
                        radius = cornerRadiusPx * t
                    )
                    iv.scaleX = heroScale - (heroScale - 1f) * t
                    iv.scaleY = heroScale - (heroScale - 1f) * t
                    iv.translationX = endTx * t
                    iv.translationY = endTy * t
                    setAppWindowBlur(blur, 1f - t)   // de-blur as the window shrinks back to the icon
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        gestureRoot.removeView(iv)
                        gestureRoot.removeView(cardView)
                        blur?.let { gestureRoot.removeView(it) }
                        realIcon?.visibility = View.VISIBLE
                        onDone?.invoke()
                    }
                })
                start()
            }
        }, delay)
    }

    private fun playLaunchAnimation(item: AppItem, iconBounds: Rect, onAnimEnd: () -> Unit) {
        val type = com.ailife.clox.applist.AppListPrefs.openAnimType(this)
        val speed = com.ailife.clox.applist.AppListPrefs.animSpeed(this)
        val heroIcon = resolveHeroIcon(item)
        if (type == com.ailife.clox.applist.AppListPrefs.OPEN_ANIM_WINDOW_EXPAND) {
            playWindowExpandAnimation(item, heroIcon, iconBounds, onAnimEnd, speed)
        } else {
            playIconZoomAnimation(item, heroIcon, iconBounds, onAnimEnd, speed)
        }
    }

    private fun playIconZoomAnimation(item: AppItem, heroIcon: android.graphics.drawable.Drawable, iconBounds: Rect, onAnimEnd: () -> Unit, speed: Float) {
        val sw = screenW.toInt(); val sh = screenH.toInt()
        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)

        val iconLeft = iconBounds.left - rootLoc[0]
        val iconTop  = iconBounds.top  - rootLoc[1]
        val iconW    = iconBounds.width().coerceAtLeast(1)
        val iconH    = iconBounds.height().coerceAtLeast(1)

        val blur = addAppWindowBlur()

        val scrim = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
        }
        gestureRoot.addView(scrim, FrameLayout.LayoutParams(sw, sh))

        val iv = ImageView(this).apply {
            setImageDrawable(heroIcon)
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            pivotX = iconW / 2f; pivotY = iconH / 2f
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(iconW, iconH).apply {
            leftMargin = iconLeft; topMargin = iconTop
        })

        val heroSize = minOf(sw, sh) * 0.40f
        val targetScale = heroSize / minOf(iconW, iconH)
        val targetTx = (sw / 2f) - (iconLeft + iconW / 2f)
        val targetTy = (sh / 2f) - (iconTop  + iconH / 2f)

        val updateFn: (Float) -> Unit = { t ->
            iv.scaleX = 1f + (targetScale - 1f) * t
            iv.scaleY = 1f + (targetScale - 1f) * t
            iv.translationX = targetTx * t
            iv.translationY = targetTy * t
            scrim.alpha = t
            setAppWindowBlur(blur, t)
        }
        launchAnimUpdateFn = updateFn
        launchAnimCleanupFn = {
            gestureRoot.removeView(iv); gestureRoot.removeView(scrim)
            blur?.let { gestureRoot.removeView(it) }
        }

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (400L / speed).toLong()
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                activeLaunchT = va.animatedValue as Float
                updateFn(activeLaunchT)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    activeLaunchAnim = null
                    launchAnimUpdateFn = null
                    launchAnimCleanupFn = null
                    onAnimEnd()
                    gestureRoot.postDelayed({
                        gestureRoot.removeView(iv)
                        gestureRoot.removeView(scrim)
                        blur?.let { gestureRoot.removeView(it) }
                    }, 500)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    activeLaunchAnim = null
                }
            })
            activeLaunchAnim = this
            activeLaunchT = 0f
            start()
        }
    }

    private fun playWindowExpandAnimation(item: AppItem, heroIcon: android.graphics.drawable.Drawable, iconBounds: Rect, onAnimEnd: () -> Unit, speed: Float) {
        val sw = screenW; val sh = screenH
        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)

        val iconLeft = (iconBounds.left - rootLoc[0]).toFloat()
        val iconTop  = (iconBounds.top  - rootLoc[1]).toFloat()
        val iconW    = iconBounds.width().coerceAtLeast(1)
        val iconH    = iconBounds.height().coerceAtLeast(1)
        val iconCx   = iconLeft + iconW / 2f
        val iconCy   = iconTop  + iconH / 2f

        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(
            gestureRoot,
            com.google.android.material.R.attr.colorSurface,
            android.graphics.Color.BLACK
        )
        val cornerRadiusPx = 56f * resources.displayMetrics.density

        // Blur of the app list, drawn beneath the card so the window stays sharp on top.
        val blur = addAppWindowBlur()

        // Card expands from icon center to screen edges — drawn behind the icon
        val cardView = WindowCardView(this).apply { cardColor = surfaceColor }
        gestureRoot.addView(cardView, FrameLayout.LayoutParams(sw.toInt(), sh.toInt()))

        // Icon zooms and translates (same as icon_zoom animation)
        val iv = ImageView(this).apply {
            setImageDrawable(heroIcon)
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            pivotX = iconW / 2f; pivotY = iconH / 2f
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(iconW, iconH).apply {
            leftMargin = iconLeft.toInt(); topMargin = iconTop.toInt()
        })

        val heroSize    = minOf(sw, sh) * 0.40f
        val targetScale = heroSize / minOf(iconW.toFloat(), iconH.toFloat())
        val targetTx    = (sw / 2f) - iconCx
        val targetTy    = (sh / 2f) - iconCy

        // Full-screen touch interceptor: touch outside current card bounds → reverse
        val interceptor = object : android.view.View(this) {
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val rect = cardView.currentRect
                    if (rect == null || !rect.contains(event.x, event.y)) {
                        reverseLaunchAnimation()
                        return true
                    }
                }
                return true
            }
        }
        gestureRoot.addView(interceptor, FrameLayout.LayoutParams(sw.toInt(), sh.toInt()))

        val updateFn: (Float) -> Unit = { t ->
            cardView.update(
                left   = iconCx * (1f - t),
                top    = iconCy * (1f - t),
                right  = iconCx + (sw - iconCx) * t,
                bottom = iconCy + (sh - iconCy) * t,
                radius = cornerRadiusPx * (1f - t)
            )
            iv.scaleX = 1f + (targetScale - 1f) * t
            iv.scaleY = 1f + (targetScale - 1f) * t
            iv.translationX = targetTx * t
            iv.translationY = targetTy * t
            setAppWindowBlur(blur, t)   // blur ramps up as the window expands
        }
        launchAnimUpdateFn = updateFn
        launchAnimCleanupFn = {
            gestureRoot.removeView(cardView)
            gestureRoot.removeView(iv)
            gestureRoot.removeView(interceptor)
            blur?.let { gestureRoot.removeView(it) }
        }

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (400L / speed).toLong()
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                activeLaunchT = va.animatedValue as Float
                updateFn(activeLaunchT)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    activeLaunchAnim = null
                    launchAnimUpdateFn = null
                    launchAnimCleanupFn = null
                    gestureRoot.removeView(interceptor)
                    onAnimEnd()
                    gestureRoot.postDelayed({
                        gestureRoot.removeView(cardView)
                        gestureRoot.removeView(iv)
                        blur?.let { gestureRoot.removeView(it) }
                    }, 500)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    activeLaunchAnim = null
                }
            })
            activeLaunchAnim = this
            activeLaunchT = 0f
            start()
        }
    }

    private fun reverseLaunchAnimation() {
        val anim      = activeLaunchAnim      ?: return
        val updateFn  = launchAnimUpdateFn    ?: return
        val cleanupFn = launchAnimCleanupFn   ?: return
        val startT    = activeLaunchT
        lastLaunchReverseAt = android.os.SystemClock.uptimeMillis()

        activeLaunchAnim    = null
        launchAnimUpdateFn  = null
        launchAnimCleanupFn = null

        // The app never actually launched (launch() only fires at the forward anim's end), so
        // clear the return-tracking state. Otherwise the next onResume sees lastLaunchedItem
        // set and plays the close/return animation — a full-screen scrim+icon that shrinks to
        // the icon slot, the "jump to full screen and collapse" glitch.
        lastLaunchedItem = null
        lastLaunchBounds = null
        lastLaunchIconView = null
        lastClosingPkg = null
        appLaunchedFromList = false

        anim.removeAllListeners()
        anim.removeAllUpdateListeners()
        anim.cancel()

        if (startT <= 0f) { lastLaunchReverseDurationMs = 0L; cleanupFn(); return }

        val speed           = com.ailife.clox.applist.AppListPrefs.animSpeed(this)
        val reverseDuration = ((400L / speed) * startT).toLong().coerceAtLeast(80L)
        lastLaunchReverseDurationMs = reverseDuration

        android.animation.ValueAnimator.ofFloat(startT, 0f).apply {
            duration     = reverseDuration
            interpolator = android.view.animation.AccelerateInterpolator(1.5f)
            addUpdateListener { va -> updateFn(va.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { cleanupFn() }
                override fun onAnimationCancel(animation: android.animation.Animator) { cleanupFn() }
            })
            start()
        }
    }

    // Android 11+: after the system shrinks the app window into the icon, recoil the icon
    // away from screen center (where the gesture animation originated) then spring it back.
    private fun playGestureFollowOnAnimation(item: AppItem, iconBounds: Rect, veil: GestureRevealView? = null, onDone: (() -> Unit)? = null) {
        val speed    = com.ailife.clox.applist.AppListPrefs.closeAnimSpeed(this)
        val realIcon = lastLaunchIconView?.get()
        lastLaunchIconView = null
        realIcon?.visibility = View.INVISIBLE

        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)

        val iconCxLocal = iconBounds.centerX() - rootLoc[0]
        val iconCyLocal = iconBounds.centerY() - rootLoc[1]
        val iconW = iconBounds.width().coerceAtLeast(1)
        val iconH = iconBounds.height().coerceAtLeast(1)

        val toCenterDx = iconCxLocal - (screenW / 2f - rootLoc[0])
        val toCenterDy = iconCyLocal - (screenH / 2f - rootLoc[1])
        val dist = Math.hypot(toCenterDx.toDouble(), toCenterDy.toDouble()).toFloat().coerceAtLeast(1f)
        val nx = toCenterDx / dist
        val ny = toCenterDy / dist

        val startOffsetX = -nx * dist * 0.35f
        val startOffsetY = -ny * dist * 0.35f

        val iv = ImageView(this).apply {
            setImageDrawable(resolveHeroIcon(item))
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            translationX = startOffsetX
            translationY = startOffsetY
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(iconW, iconH).apply {
            leftMargin = iconCxLocal - iconW / 2
            topMargin  = iconCyLocal - iconH / 2
        })

        val stiffness = SpringForce.STIFFNESS_MEDIUM / (speed * speed)
        val springX = SpringAnimation(iv, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring.stiffness  = stiffness
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        val springY = SpringAnimation(iv, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.stiffness  = stiffness
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        springX.addEndListener { _, _, _, _ ->
            // Cross-fade: reveal the real icon underneath FIRST (the hero is now settled over it),
            // then fade the hero out on top. Restoring it only after the fade caused a brief dip
            // then a pop — the flash.
            realIcon?.visibility = View.VISIBLE
            // Open the veil's hole over the icon NOW so the real icon shows through during the
            // cross-fade; otherwise the hero fades onto bare veil background and the hole only
            // pops open later — a flash at the target.
            veil?.let { openVeilTargetHole(it, iconBounds) }
            iv.animate().alpha(0f).setDuration((120L / speed).toLong()).withEndAction {
                gestureRoot.removeView(iv)
                onDone?.invoke()
            }.start()
        }
        springX.start(); springY.start()
    }

    // Cover the open app list with a veil painted with its own background image, so the icons
    // are hidden until the reveal plays. Added up front (before/at the start of the return
    // animation) so the cover is continuously present — no flash of the bare app list between
    // the return animation finishing and the reveal starting. Returns the veil for the reveal.
    private fun showGestureReturnVeil(): GestureRevealView {
        val listView = appListFragment?.view
        val bg = listView?.background?.constantState?.newDrawable()?.mutate()
            ?: android.graphics.drawable.ColorDrawable(
                com.google.android.material.color.MaterialColors.getColor(
                    gestureRoot, com.google.android.material.R.attr.colorSurface,
                    android.graphics.Color.BLACK
                )
            )
        val veil = GestureRevealView(this).apply { bgDrawable = bg }
        gestureRoot.addView(veil, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return veil
    }

    // Open an icon-sized hole in the veil over [iconBounds] so the real icon underneath shows
    // through. Used by the gesture recoil so its hero→real-icon cross-fade reveals the real icon
    // (not bare veil background — that caused a flash). Returns the icon radius. No-op-safe if
    // the veil isn't laid out yet.
    private fun openVeilTargetHole(veil: GestureRevealView, iconBounds: Rect): Float {
        val rootLoc = IntArray(2)
        veil.getLocationOnScreen(rootLoc)
        veil.holeCx = (iconBounds.centerX() - rootLoc[0]).toFloat().coerceIn(0f, veil.width.toFloat())
        veil.holeCy = (iconBounds.centerY() - rootLoc[1]).toFloat().coerceIn(0f, veil.height.toFloat())
        val iconRadius = (Math.hypot(
            iconBounds.width().toDouble(), iconBounds.height().toDouble()
        ).toFloat() / 2f).coerceAtLeast(1f)
        veil.setHoleRadius(iconRadius)
        return iconRadius
    }

    // Reveal the open app list (over its current background image) on gesture return — called
    // once the return animation has finished. The [veil] (already covering the screen) either
    // fades out or opens a growing circular hole, revealing the identical real background plus
    // the app icons underneath, so the icons appear to fade / circle in over a static background.
    private fun playGestureReturnReveal(veil: GestureRevealView, iconBounds: Rect) {
        Log.d("CloX/Return", "playGestureReturnReveal iconBounds=$iconBounds center=(${iconBounds.centerX()},${iconBounds.centerY()})")
        val speed = com.ailife.clox.applist.AppListPrefs.gestureReturnSpeed(this)
        val delay = com.ailife.clox.applist.AppListPrefs.gestureReturnDelay(this).toLong()
        val circle = com.ailife.clox.applist.AppListPrefs.gestureReturnAnim(this) ==
            com.ailife.clox.applist.AppListPrefs.GESTURE_RETURN_CIRCLE

        // Wait for layout so width/height are known, then hold for the start delay before animating.
        veil.post {
            if (isFinishing || isDestroyed) { gestureRoot.removeView(veil); return@post }
            // Re-resolve the closing app's icon position NOW (after the launcher is laid out and
            // visible). At contract time the RecyclerView rows were detached/occluded, so the
            // bounds were unknown; the list is on-screen by the time this post runs.
            val freshBounds = lastClosingPkg
                ?.let { if (appListOpen) appListFragment?.returnIconHit(it) else null }
                ?.bounds
            val originBounds = freshBounds ?: iconBounds
            Log.d("CloX/Return", "reveal origin: fresh=$freshBounds fallback=$iconBounds -> $originBounds")
            // Circle opens from the returning app's icon centre (in veil-local coords).
            val rootLoc = IntArray(2)
            veil.getLocationOnScreen(rootLoc)
            val cx = (originBounds.centerX() - rootLoc[0]).toFloat().coerceIn(0f, veil.width.toFloat())
            val cy = (originBounds.centerY() - rootLoc[1]).toFloat().coerceIn(0f, veil.height.toFloat())
            veil.holeCx = cx
            veil.holeCy = cy
            Log.d("CloX/Return", "reveal veil=${veil.width}x${veil.height} rootLoc=(${rootLoc[0]},${rootLoc[1]}) hole=($cx,$cy) circle=$circle")
            // Radius must reach the farthest corner so the reveal fully covers the screen.
            val maxRadius = maxOf(
                Math.hypot(cx.toDouble(), cy.toDouble()),
                Math.hypot((veil.width - cx).toDouble(), cy.toDouble()),
                Math.hypot(cx.toDouble(), (veil.height - cy).toDouble()),
                Math.hypot((veil.width - cx).toDouble(), (veil.height - cy).toDouble())
            ).toFloat()
            // The returning ("target") app's icon was just delivered to its slot by the return
            // animation — keep it uncovered so the reveal fades/circles in the REST of the list
            // AROUND it. The veil holds a hole the size of the icon over it from the first frame.
            val iconRadius = (Math.hypot(
                originBounds.width().toDouble(), originBounds.height().toDouble()
            ).toFloat() / 2f).coerceAtLeast(1f)
            veil.setHoleRadius(iconRadius)
            veil.postDelayed({
                if (isFinishing || isDestroyed) { gestureRoot.removeView(veil); return@postDelayed }
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    // Slower baseline than the open/close animations; speed slider still defaults to 1x.
                    duration = (600L / speed).toLong()
                    interpolator = DecelerateInterpolator(1.5f)
                    if (circle) {
                        // Grow the hole from the icon's edge (not 0) so the target never gets covered.
                        addUpdateListener { va ->
                            val t = va.animatedValue as Float
                            veil.setHoleRadius(iconRadius + (maxRadius - iconRadius) * t)
                        }
                    } else {
                        // Fade the veil out; the icon-sized hole keeps the target visible throughout.
                        addUpdateListener { va -> veil.alpha = 1f - (va.animatedValue as Float) }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            gestureRoot.removeView(veil)
                        }
                    })
                    start()
                }
            }, delay)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == com.ailife.clox.qs.QsPermissionHelper.REQUEST_CODE) {
            // If any permission was newly granted, re-request remaining and refresh QS state
            if (com.ailife.clox.qs.QsPermissionHelper.onPermissionsResult(grantResults)) {
                quickSettings.refreshStateIfOpen()
                // Re-request to pick up any still-missing permissions in next pass
                com.ailife.clox.qs.QsPermissionHelper.requestMissing(this)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep rendering while the watchface config dialog is up top so its live
        // preview isn't frozen — see WatchfaceConfigActivity.isActive.
        if (!com.ailife.clox.watchface.config.WatchfaceConfigActivity.isActive) {
            // Flush the face's script variables to disk BEFORE pausing GL — a plain
            // background (no scene teardown) may be followed by a process kill, and
            // the GL thread still services its event queue while paused.
            (CocosManager.getGlView() as? android.opengl.GLSurfaceView)?.queueEvent {
                runCatching { com.ailife.clox.cocos.LuaBridge.callLuaFunction("WatchfaceOnSaveVars", "") }
                // Face not visible (screen off or covered by an app): stop face videos.
                // MediaEngine decodes on its own thread — pausing GL does NOT stop the
                // codec, which kept burning ~35% CPU with the watch asleep.
                runCatching { com.ailife.clox.cocos.LuaBridge.callLuaFunction("WatchfaceOnHostPause", "") }
            }
            // Quiesce the bridge's periodic data + hardware sensors with the face
            (supportFragmentManager.findFragmentByTag("home") as? com.ailife.clox.home.HomeFragment)
                ?.onHostPause()
            // The 1 FPS app-list cap leaves the render thread napping up to a full second
            // in its frame-gate wait; GLSurfaceView.onPause() and the queued save-vars
            // event block the main thread until it wakes — trace showed performPause at
            // 680-1000 ms on every pause with the list open (the "laggy HOME press").
            // Kick it awake (after queueEvent, per kickFrame's contract); the cap stays.
            dev.axmol.lib.AxmolRenderer.kickFrame()
            CocosManager.onPause()
        }
        aodManager.onPause()
        suppressSystemTransition()
    }

    override fun onStop() {
        super.onStop()
        // Mark "an app covered us" ONLY if the screen was still on AND it wasn't the keyguard
        // covering us. A stop from the screen turning off, or from the lockscreen appearing on a
        // screen on/off cycle, must not count — otherwise waking the screen would be treated as a
        // return from the last app and replay its close animation (and mis-handle the next HOME).
        val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        val keyguardLocked = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        wasStoppedSinceResume = screenOn && !keyguardLocked
        Log.d("CloX/Return", "onStop screenOn=$screenOn keyguardLocked=$keyguardLocked -> wasStopped=$wasStoppedSinceResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPathAMorphBlur()
        if (pathACloseRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            com.ailife.clox.anim.RemoteAppAnimator.unregisterClose(this)
            pathACloseRegistered = false
        }
        CocosManager.onDestroy(this)
        stateManager.detach()
        aodManager.stop()
        GestureLockManager.onLockChanged = null
        runCatching { unregisterReceiver(screenOffReceiver) }
    }

    /** Collapse all launcher layers (app list, overlays, context menu) back to the watchface. */
    private fun resetToWatchface() {
        if (RtosManager.isActive) return
        AppContextMenu.dismissCurrent()
        if (overlayController.hasActiveOverlay()) overlayController.dismissActive()
        if (appListOpen) {
            appListOpen = false; gestureRoot.appListOpen = false
            appListFragment?.setActive(false)
            appListSpring?.cancel()
            appListFragment?.view?.translationX = screenW
            refreshAppListTransparentRegion()
            updateBackEnabled()
        }
        Log.d("CloX/Return", "resetToWatchface (screen off)")
    }

    // ── Gesture setup ────────────────────────────────────────────────────────

    private fun setupGestures() {
        // Only pass LEFT/RIGHT through when QS (VP2) is active, not other overlays
        gestureRoot.qsOverlayActive = { overlayController.isQsActive() }
        gestureRoot.rtosQsOverlayVisible = { rtosDelegate.isQsVisible }
        // Pass vertical + right swipes through to the notification RecyclerView/ItemTouchHelper
        gestureRoot.notificationOverlayActive = { overlayController.isNotificationActive() }
        // Widget drawer: pass all swipes to inner RecyclerView; collapse only on overscroll-down at top
        gestureRoot.widgetOverlayActive = { overlayController.isWidgetActive() }
        gestureRoot.widgetListCanScrollUp = { widgets.canScrollUp() }

        // Double-tap detection is active only when an action is configured;
        // whether single taps are additionally held for the double-tap window
        // (blocking the first tap at ~300ms latency cost) is the user's choice —
        // holding makes tap-heavy faces like stopwatches sluggish.
        gestureRoot.doubleTapArmed = {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getString("double_tap_action", "screen_off") != "nothing"
        }
        gestureRoot.holdTapForDoubleTap = {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("double_tap_hold_taps", false)
        }

        gestureRoot.doubleTapListener = {
            if (RtosManager.isActive) {
                // RTOS active: tap feeds unlock handler only (already done in dispatchTouchEvent)
            } else if (GestureLockManager.isAodLocked) {
                GestureLockManager.unlockAod()
                aodManager.onAodGestureUnlocked()
            } else {
                gestureHandler.onDoubleTap()
            }
        }

        gestureRoot.longPressListener = {
            if (!RtosManager.isActive && !appListOpen && !overlayController.hasActiveOverlay()) {
                gestureHandler.onLongPress()
            }
        }

        gestureRoot.onSwipeStart = { direction, _, _ ->
            if (RtosManager.isFullMode()) {
                // Mode 2: DOWN → RTOS QS, RIGHT → RTOS notif, LEFT → app list, UP → blocked
                when {
                    overlayController.hasActiveOverlay() && !overlayController.isDragging ->
                        overlayController.startCollapse(direction)
                    !appListOpen && !overlayController.hasActiveOverlay() ->
                        when (direction) {
                            SwipeDirection.DOWN  -> { /* committed in onSwipeEnd */ }
                            SwipeDirection.RIGHT -> overlayController.startDrag(direction, screenW, screenH)
                            SwipeDirection.LEFT  -> { /* app list follows finger in onSwipeUpdate */ }
                            SwipeDirection.UP -> { /* disabled */ }
                        }
                }
            } else {
                when {
                    !appListOpen && !overlayController.hasActiveOverlay() ->
                        when (direction) {
                            SwipeDirection.DOWN, SwipeDirection.UP, SwipeDirection.RIGHT ->
                                overlayController.startDrag(direction, screenW, screenH)
                            SwipeDirection.LEFT -> { /* app list follows finger in onSwipeUpdate */ }
                        }
                    overlayController.hasActiveOverlay() && !overlayController.isDragging ->
                        overlayController.startCollapse(direction)
                }
            }
        }

        gestureRoot.onSwipeUpdate = ::handleSwipeUpdate

        gestureRoot.onSwipeEnd = ::handleSwipeEnd
    }

    private fun setupGestureLock() {
        GestureLockManager.onLockChanged = { locked, _ ->
            gestureRoot.gestureLocked = locked
            gestureLockIcon.visibility = if (locked) View.VISIBLE else View.GONE
        }
        // Sync initial state (e.g. if lock persisted across activity re-creates — unlikely but safe)
        gestureRoot.gestureLocked = GestureLockManager.isLocked
        gestureLockIcon.visibility = if (GestureLockManager.isLocked) View.VISIBLE else View.GONE
    }

    // ── Swipe-update routing ─────────────────────────────────────────────────

    private fun handleSwipeUpdate(direction: SwipeDirection, dx: Float, dy: Float) {
        if (overlayController.isCollapsing) {
            overlayController.updateCollapse(dx, dy)
            return
        }
        if (overlayController.isDragging) {
            overlayController.updateDrag(direction, dx, dy)
            return
        }
        if (overlayController.hasActiveOverlay()) return

        when {
            appListOpen && direction == SwipeDirection.RIGHT -> {
                val v = appListFragment?.view ?: return
                v.translationX = dx.coerceAtLeast(0f)
                refreshAppListTransparentRegion()
            }
            !appListOpen && direction == SwipeDirection.LEFT -> {
                val v = appListFragment?.view ?: return
                v.translationX = (screenW + dx).coerceIn(0f, screenW)
                refreshAppListTransparentRegion()
            }
        }
    }

    // ── Swipe-end routing ────────────────────────────────────────────────────

    private fun handleSwipeEnd(direction: SwipeDirection, dx: Float, dy: Float, vx: Float, vy: Float) {
        // Priority 1: commit or cancel an in-progress collapse drag
        if (overlayController.isCollapsing) {
            val commit = when (direction) {
                SwipeDirection.UP    -> -dy > screenH * 0.25f || -vy > 600f
                SwipeDirection.DOWN  -> dy > screenH * 0.25f || vy > 600f
                SwipeDirection.LEFT  -> -dx > screenW * 0.25f || -vx > 600f
                else -> false
            }
            if (commit) overlayController.commitCollapse() else overlayController.cancelCollapse()
            return
        }

        // Priority 2: commit or cancel an in-progress open drag
        if (overlayController.isDragging) {
            val commit = when (direction) {
                SwipeDirection.DOWN  -> dy > screenH * 0.25f || vy > 600f
                SwipeDirection.UP    -> -dy > screenH * 0.25f || -vy > 600f
                SwipeDirection.RIGHT -> dx > screenW * 0.25f || vx > 600f
                else -> false
            }
            if (commit) overlayController.commitDrag() else overlayController.cancelDrag()
            return
        }

        // Mode 2 DOWN swipe: show RTOS fake QS
        if (RtosManager.isFullMode() && direction == SwipeDirection.DOWN &&
            !appListOpen && !overlayController.hasActiveOverlay()) {
            if (dy > screenH * 0.15f || vy > 400f) rtosDelegate.showQs()
            return
        }

        // Priority 3: dismiss a fully-open overlay on any swipe (fallback)
        if (overlayController.hasActiveOverlay()) {
            overlayController.dismissActive()
            val x = appListFragment?.view?.translationX ?: screenW
            if (x != (if (appListOpen) 0f else screenW)) {
                springAppList(if (appListOpen) 0f else screenW)
            }
            return
        }

        val currentX = appListFragment?.view?.translationX ?: screenW

        when {
            appListOpen && direction == SwipeDirection.RIGHT ->
                if (dx > screenW * 0.35f || vx > 700f) closeAppList(vx)
                else springAppList(0f)

            !appListOpen && direction == SwipeDirection.LEFT ->
                if (-dx > screenW * 0.35f || -vx > 700f) openAppList(vx)
                else springAppList(screenW)

            currentX > 0f && currentX < screenW ->
                if (currentX < screenW * 0.5f) openAppList() else closeAppList()
        }
    }

    // ── App list ─────────────────────────────────────────────────────────────

    // velocity (px/s) is the finger's release velocity so the spring continues the fling.
    // Don't reset translationX here — the spring starts from wherever the drag left the
    // view, so the open continues smoothly from the finger instead of snapping off-screen.
    private fun openAppList(velocity: Float = 0f) {
        appListOpen = true; gestureRoot.appListOpen = true
        appListFragment?.setActive(true)
        springAppList(0f, velocity)
        updateBackEnabled()
        hideSystemBars()   // nav bar may need to appear ("only in app list" mode)
    }

    private fun closeAppList(velocity: Float = 0f) {
        appListOpen = false; gestureRoot.appListOpen = false
        // face becomes visible the moment the close slide starts — drop the opaque
        // cover and lift the cap now
        setAppListCoverShown(false)
        dev.axmol.lib.AxmolRenderer.setForceLowFps(false)
        appListFragment?.setActive(false)
        springAppList(screenW, velocity)
        updateBackEnabled()
        hideSystemBars()   // nav bar may need to hide again ("only in app list" mode)
    }

    private fun springAppList(target: Float, velocity: Float = 0f) {
        val v = appListFragment?.view ?: return
        appListSpring?.cancel()
        appListSpring = SpringAnimation(v, DynamicAnimation.TRANSLATION_X, target).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            if (velocity != 0f) setStartVelocity(velocity)
            // Recompute the watchface's transparent region each frame so the sliding
            // app list occludes it (see refreshAppListTransparentRegion).
            addUpdateListener { _, _, _ -> refreshAppListTransparentRegion() }
            // Settled fully open: the opaque app list covers the face, so cap the
            // invisible GL rendering at 1 FPS and leave the CPU/GPU to the list
            // (dynamic FPS setting, default on).
            addEndListener { _, canceled, _, _ ->
                if (!canceled && appListOpen) {
                    // Settled fully open: back the opaque list with its cover layer so a
                    // background trip + gesture return can't flash the face through it.
                    setAppListCoverShown(true)
                    if (com.ailife.clox.cocos.RenderPolicy.dynamicFps) {
                        dev.axmol.lib.AxmolRenderer.setForceLowFps(true)
                    }
                }
            }
        }
        appListSpring?.start()
    }

    // The watchface is an Axmol GLSurfaceView (default Z-order): it punches a transparent
    // region through the window and SurfaceFlinger shows the GL layer through it
    // regardless of the opaque pixels the app list draws on top. That region is only
    // recomputed on a UI-thread layout/draw pass, but a translationX spring animates on
    // the RenderThread and never triggers one — so the region goes stale (computed while
    // the app list was off-screen) and the watchface keeps "showing through" the sliding
    // app list. Forcing requestTransparentRegion recomputes it against the app list's
    // live position so it correctly occludes the watchface during and after the slide.
    private fun refreshAppListTransparentRegion() {
        val v = appListFragment?.view ?: return
        // Any movement re-exposes the watchface — drop the opaque cover instantly.
        // (Every path that moves the list calls this: interactive drags, the spring's
        // frame listener, predictive back, and resetToWatchface.)
        if (v.translationX > 1f) setAppListCoverShown(false)
        v.parent?.requestTransparentRegion(v)
    }

    // ── Opaque app-list backing layer (never touches the GL view) ─────────────
    //
    // Screen-record analysis of the gesture-home return with the app list open: the
    // watchface GL layer gets a fresh buffer BEFORE the relaunched window's first frame
    // is presented (the window frame is held by the resume sync), so for 1-2 frames
    // SurfaceFlinger composites the watchface alone (alpha-ramped by the transition)
    // over the wallpaper — the "app list not opaque" flash. Nothing drawn INSIDE the
    // window can prevent it: the window frame isn't on screen yet.
    //
    // Fix: our own opaque cover SurfaceView at media-overlay Z — above the watchface
    // layer, below the window — painted with the app list background by a synchronous
    // main-thread canvas draw in surfaceCreated. Shown only while the list is settled
    // fully open (exactly when the face is invisible anyway) and persists as view state
    // across the background trip, so after a relaunch its buffer is present from the
    // first composited frame: the transition shows the list background, not the face.
    // Dropped the instant the list starts moving (refreshAppListTransparentRegion runs
    // on every translation change) so it never occludes the face in normal use.

    private var appListCover: android.view.SurfaceView? = null
    private val COVER_OFFSCREEN = 100_000f

    private fun setupAppListCover() {
        val cover = android.view.SurfaceView(this)
        cover.setZOrderMediaOverlay(true)   // above the watchface layer, below the window
        cover.translationX = COVER_OFFSCREEN
        cover.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) = drawAppListCover()
            override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) = drawAppListCover()
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })
        // Between homeContainer (index 0) and fragmentContainer
        gestureRoot.addView(cover, 1, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        appListCover = cover
    }

    private fun drawAppListCover() {
        val cover = appListCover ?: return
        val canvas = try { cover.holder.lockCanvas() } catch (_: Exception) { null } ?: return
        try {
            // Copy of the list background (same source as the gesture-return veil) so the
            // shared drawable's bounds are never disturbed.
            val bg = appListFragment?.view?.background?.constantState?.newDrawable()?.mutate()
            if (bg != null) {
                bg.setBounds(0, 0, canvas.width, canvas.height)
                bg.draw(canvas)
            } else {
                canvas.drawColor(com.google.android.material.color.MaterialColors.getColor(
                    gestureRoot, com.google.android.material.R.attr.colorSurface,
                    android.graphics.Color.BLACK))
            }
        } finally {
            try { cover.holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    // Shown/hidden by TRANSLATION, never visibility: a visibility toggle destroys and
    // recreates the cover's surface, and that relayout's window sync waits for a fresh
    // frame from the (1 FPS-capped, list-open) watchface SurfaceView — a 1000 ms stall
    // on every HOME-collapse (Choreographer "Skipped 60 frames", Davey ~1s). Moving the
    // layer off-screen is a pure composition transaction: surface stays alive, no
    // relayout, no sync, instant.
    private fun setAppListCoverShown(shown: Boolean) {
        val cover = appListCover ?: return
        val tx = if (shown) 0f else COVER_OFFSCREEN
        if (cover.translationX != tx) cover.translationX = tx
    }

    // ── Back press ───────────────────────────────────────────────────────────

    private lateinit var backCallback: OnBackPressedCallback

    private fun setupBackPressHandler() {
        // Disabled when nothing is dismissible so predictive back shows no preview at idle.
        // Re-enabled whenever the app list or an overlay becomes active.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (activeLaunchAnim != null || isLaunchAnimReversing()) return
                appListSpring?.cancel()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (activeLaunchAnim != null || isLaunchAnimReversing()) return
                val p = backEvent.progress
                when {
                    overlayController.hasActiveOverlay() ->
                        overlayController.setPredictiveProgress(p)
                    appListOpen -> {
                        appListFragment?.view?.translationX = (p * screenW).coerceIn(0f, screenW)
                        refreshAppListTransparentRegion()
                    }
                }
            }

            override fun handleOnBackCancelled() {
                if (activeLaunchAnim != null || isLaunchAnimReversing()) return
                when {
                    overlayController.hasActiveOverlay() -> overlayController.cancelPredictiveBack()
                    appListOpen -> springAppList(0f)
                }
            }

            override fun handleOnBackPressed() {
                when {
                    activeLaunchAnim != null             -> reverseLaunchAnimation()
                    // The window-expand interceptor may have already reversed the launch anim on
                    // touch-down for this same gesture; swallow the follow-up Back so it doesn't
                    // propagate to context menu / overlay / app-list dismissal. Mirrors the HOME
                    // guard in onNewIntent.
                    isLaunchAnimReversing()              -> {}
                    AppContextMenu.isShowing             -> { AppContextMenu.dismissCurrent(); updateBackEnabled() }
                    overlayController.hasActiveOverlay() -> overlayController.dismissActive()
                    appListOpen                          -> closeAppList()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    internal fun updateBackEnabled() {
        // Always consume back — never let the system close the launcher activity.
        backCallback.isEnabled = true
    }

    // ── Widget setup ──────────────────────────────────────────────────────────

    private fun setupWidgetCallbacks() {
        widgets.onPickAppWidget = { allocatedId, pendingSlot ->
            pendingWidgetId = allocatedId
            pendingWidgetSlot = pendingSlot
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocatedId)
            }
            @Suppress("DEPRECATION")
            safeStartActivityForResult(intent, WidgetHostManager.REQUEST_PICK_WIDGET)
        }
    }

    // ── Activity result / permission ─────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            IconPickerDialog.GALLERY_REQUEST_CODE ->
                IconPickerDialog.handleGalleryResult(this, resultCode, data)

            WidgetHostManager.REQUEST_PICK_WIDGET -> {
                val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                if (resultCode == RESULT_OK && widgetId != -1) {
                    val awm = AppWidgetManager.getInstance(this)
                    // Some pickers don't auto-bind; try to bind using the provider from the intent.
                    if (awm.getAppWidgetInfo(widgetId) == null) {
                        @Suppress("DEPRECATION")
                        val provider = data?.getParcelableExtra<android.content.ComponentName>(
                            AppWidgetManager.EXTRA_APPWIDGET_PROVIDER
                        )
                        if (provider != null) awm.bindAppWidgetIdIfAllowed(widgetId, provider)
                    }
                    val info = awm.getAppWidgetInfo(widgetId)
                    if (info?.configure != null) {
                        // Start configure and return — leave pendingWidgetId/Slot intact
                        // so REQUEST_CONFIGURE_WIDGET can use them.
                        try {
                            widgets.widgetHostManager.startAppWidgetConfigureActivityForResult(
                                this, widgetId, 0, WidgetHostManager.REQUEST_CONFIGURE_WIDGET, null
                            )
                        } catch (_: Exception) {
                            // Configure activity unavailable — add the widget as-is
                            widgets.onWidgetPicked(widgetId, pendingWidgetSlot)
                            pendingWidgetId = -1; pendingWidgetSlot = null
                        }
                        return  // do NOT fall through to clear pending state
                    }
                    widgets.onWidgetPicked(widgetId, pendingWidgetSlot)
                } else {
                    if (pendingWidgetId != -1) widgets.onWidgetPickCancelled(pendingWidgetId)
                }
                pendingWidgetId = -1; pendingWidgetSlot = null
            }

            WidgetHostManager.REQUEST_CONFIGURE_WIDGET -> {
                // Always add the widget — many configure activities return RESULT_CANCELED
                // even when the user successfully completes configuration.
                val widgetId = pendingWidgetId
                val slot = pendingWidgetSlot
                pendingWidgetId = -1; pendingWidgetSlot = null
                if (widgetId != -1) widgets.onWidgetPicked(widgetId, slot)
            }

            DefaultLauncherManager.REQUEST_CODE_SET_DEFAULT_HOME -> {
                // Nothing to do — user either accepted or dismissed the role request.
                // isDefaultLauncher() will reflect the new state on next resume.
            }
        }
    }

    // ── RTOS mode ────────────────────────────────────────────────────────────

    private fun onRtosEntered() {
        rtosDelegate.enter()
        updateBackEnabled()
    }

    private fun onRtosExited() {
        rtosDelegate.exit()
        updateBackEnabled()
    }

    // ── WindowCardView — used by window-expand open animation ────────────────

    private class WindowCardView(ctx: android.content.Context) : android.view.View(ctx) {
        var cardColor = android.graphics.Color.BLACK
        var currentRect: android.graphics.RectF? = null

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val bounds = android.graphics.RectF()
        private var radius = 0f

        fun update(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
            bounds.set(left, top, right, bottom)
            currentRect = android.graphics.RectF(bounds)
            this.radius = radius
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (bounds.isEmpty) return
            paint.color = cardColor
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }

    /**
     * Full-screen veil painted with the app list's background image. For "circle open" it
     * punches a growing transparent circular hole (PorterDuff CLEAR on an offscreen layer)
     * so the real app list underneath is revealed from the centre outward. For "fade in"
     * the hole is unused and the view's alpha is animated instead.
     */
    private class GestureRevealView(ctx: android.content.Context) : android.view.View(ctx) {
        var bgDrawable: android.graphics.drawable.Drawable? = null
        var holeCx = 0f
        var holeCy = 0f
        private var holeRadius = 0f

        private val holePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }

        fun setHoleRadius(r: Float) {
            holeRadius = r
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            bgDrawable?.apply { setBounds(0, 0, width, height); draw(canvas) }
            if (holeRadius > 0f) canvas.drawCircle(holeCx, holeCy, holeRadius, holePaint)
            canvas.restoreToCount(save)
        }
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RtosManager.isActive) return true   // block all hardware keys in RTOS mode
        if (event != null && buttonMappingHandler.handleKeyDown(keyCode, event)) return true
        // BACK is handled by onBackPressedDispatcher (setupBackPressHandler).
        // HOME physical key: dismiss outermost layer, same logic.
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            when {
                activeLaunchAnim != null             -> reverseLaunchAnimation()
                isLaunchAnimReversing()              -> {}
                AppContextMenu.isShowing             -> AppContextMenu.dismissCurrent()
                overlayController.hasActiveOverlay() -> overlayController.dismissActive()
                appListOpen                          -> closeAppList()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (RtosManager.isActive) return true
        if (event != null && buttonMappingHandler.handleKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }
}
