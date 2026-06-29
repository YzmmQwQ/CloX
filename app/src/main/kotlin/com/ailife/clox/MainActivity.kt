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
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
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
import dev.axmol.lib.AxmolEngine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AxmolEngine.AxmolEngineListener {

    companion object {
        const val ACTION_ENTER_RTOS = "com.ailife.clox.ACTION_ENTER_RTOS"
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
    private var lastLaunchIconView: java.lang.ref.WeakReference<View>? = null
    private var lastClosingPkg: String? = null   // package of the app currently returning to us

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
    private var launchAnimUpdateFn: ((Float) -> Unit)? = null
    private var launchAnimCleanupFn: (() -> Unit)? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        hideSystemBars()
        setContentView(R.layout.activity_main)

        gestureRoot = findViewById(R.id.gestureRoot)

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
                if (com.ailife.clox.applist.AppListPrefs.useCustomLaunchAnim(this)) {
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
                if (com.ailife.clox.applist.AppListPrefs.useCustomLaunchAnim(this)) {
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

        gestureRoot.post {
            appListFragment?.view?.translationX = if (appListOpen) 0f else screenW
            appListFragment?.setActive(appListOpen)
            gestureRoot.appListOpen = appListOpen
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
                overlayController.dismissActive()
                if (appListOpen) closeAppList()
            }
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

        // If launch animation is still playing, reverse it instead of proceeding
        if (activeLaunchAnim != null) {
            appLaunchedFromList = false
            reverseLaunchAnimation()
            return
        }

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
                    if (!CloXAccessibilityService.lockScreen()) {
                        QsPermissionHelper.ensureAccessibilityEnabled(this)
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
        if (hasFocus) hideSystemBars()
    }

    internal fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
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
        Log.d("CloX/Return", "onResume (reset wasStopped=false, cameFromBackground=$cameFromBackground)")
        wasStoppedSinceResume = false
        CocosManager.onResume()
        aodManager.onResume()
        if (overlayController.isNotificationActive()) notifications.onPanelOpened()

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
            val returnEnabled = com.ailife.clox.applist.AppListPrefs.returnAnimEnabled(this)
            // Reveal the open app list over its background image (fade / circle from the icon).
            // "Only on gesture" restricts it to the swipe-home gesture (contract) return.
            val onlyOnGesture = com.ailife.clox.applist.AppListPrefs.gestureReturnOnlyGesture(this)
            val playReveal = appListOpen &&
                com.ailife.clox.applist.AppListPrefs.gestureReturnEnabled(this) &&
                (!onlyOnGesture || pendingGestureFollowOn)
            when {
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> {
                    Log.d("CloX/Return", "branch=pre-Q playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (playReveal) gestureRoot.post { playGestureReturnReveal(bounds) }
                    if (returnEnabled) gestureRoot.post { playReturnAnimation(item, bounds) }
                }
                pendingGestureFollowOn -> {
                    pendingGestureFollowOn = false
                    Log.d("CloX/Return", "branch=gestureFollowOn playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (playReveal) gestureRoot.post { playGestureReturnReveal(bounds) }
                    if (returnEnabled) gestureRoot.post { playGestureFollowOnAnimation(item, bounds) }
                    else lastLaunchIconView = null
                }
                else -> {
                    Log.d("CloX/Return", "branch=else(11+,no-contract) playReveal=$playReveal returnEnabled=$returnEnabled bounds=$bounds")
                    if (playReveal) gestureRoot.post { playGestureReturnReveal(bounds) }
                    if (returnEnabled) gestureRoot.post { playReturnAnimation(item, bounds) }
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
            pendingGestureFollowOn = true
            Log.d("CloX/Return", "contract pkg=$pkg target=$lastLaunchBounds")
            contract.sendEndPosition(RectF(lastLaunchBounds!!))
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

    private fun playReturnAnimation(item: AppItem, iconBounds: Rect) {
        val type = com.ailife.clox.applist.AppListPrefs.closeAnimType(this)
        if (type == com.ailife.clox.applist.AppListPrefs.CLOSE_ANIM_WINDOW_EXPAND) {
            playReturnWindowExpandAnimation(item, iconBounds)
        } else {
            playReturnIconZoomAnimation(item, iconBounds)
        }
    }

    private fun playReturnIconZoomAnimation(item: AppItem, iconBounds: Rect) {
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
                realIcon?.visibility = View.VISIBLE
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
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        gestureRoot.removeView(iv)
                        gestureRoot.removeView(scrim)
                        realIcon?.visibility = View.VISIBLE
                    }
                })
                start()
            }
        }, delay)
    }

    private fun playReturnWindowExpandAnimation(item: AppItem, iconBounds: Rect) {
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
                realIcon?.visibility = View.VISIBLE
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
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        gestureRoot.removeView(iv)
                        gestureRoot.removeView(cardView)
                        realIcon?.visibility = View.VISIBLE
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
        }
        launchAnimUpdateFn = updateFn
        launchAnimCleanupFn = { gestureRoot.removeView(iv); gestureRoot.removeView(scrim) }

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
        }
        launchAnimUpdateFn = updateFn
        launchAnimCleanupFn = {
            gestureRoot.removeView(cardView)
            gestureRoot.removeView(iv)
            gestureRoot.removeView(interceptor)
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

        activeLaunchAnim    = null
        launchAnimUpdateFn  = null
        launchAnimCleanupFn = null

        anim.removeAllListeners()
        anim.removeAllUpdateListeners()
        anim.cancel()

        if (startT <= 0f) { cleanupFn(); return }

        val speed           = com.ailife.clox.applist.AppListPrefs.animSpeed(this)
        val reverseDuration = ((400L / speed) * startT).toLong().coerceAtLeast(80L)

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
    private fun playGestureFollowOnAnimation(item: AppItem, iconBounds: Rect) {
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
            iv.animate().alpha(0f).setDuration((120L / speed).toLong()).withEndAction {
                gestureRoot.removeView(iv)
            }.start()
        }
        springX.start(); springY.start()
    }

    // Reveal the open app list (over its current background image) on gesture return.
    // A veil painted with the app list's background covers the screen, then either fades
    // out or opens a growing circular hole — revealing the identical real background plus
    // the app icons underneath, so the icons appear to fade / circle in over a static background.
    private fun playGestureReturnReveal(iconBounds: Rect) {
        Log.d("CloX/Return", "playGestureReturnReveal iconBounds=$iconBounds center=(${iconBounds.centerX()},${iconBounds.centerY()})")
        val listView = appListFragment?.view ?: return
        val bg = listView.background?.constantState?.newDrawable()?.mutate()
            ?: android.graphics.drawable.ColorDrawable(
                com.google.android.material.color.MaterialColors.getColor(
                    gestureRoot, com.google.android.material.R.attr.colorSurface,
                    android.graphics.Color.BLACK
                )
            )
        val speed = com.ailife.clox.applist.AppListPrefs.gestureReturnSpeed(this)
        val delay = com.ailife.clox.applist.AppListPrefs.gestureReturnDelay(this).toLong()
        val circle = com.ailife.clox.applist.AppListPrefs.gestureReturnAnim(this) ==
            com.ailife.clox.applist.AppListPrefs.GESTURE_RETURN_CIRCLE

        val veil = GestureRevealView(this).apply { bgDrawable = bg }
        gestureRoot.addView(veil, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
            veil.postDelayed({
                if (isFinishing || isDestroyed) { gestureRoot.removeView(veil); return@postDelayed }
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    // Slower baseline than the open/close animations; speed slider still defaults to 1x.
                    duration = (600L / speed).toLong()
                    interpolator = DecelerateInterpolator(1.5f)
                    if (circle) {
                        addUpdateListener { va -> veil.setHoleRadius((va.animatedValue as Float) * maxRadius) }
                    } else {
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
        CocosManager.onPause()
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
        CocosManager.onDestroy()
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
    }

    private fun closeAppList(velocity: Float = 0f) {
        appListOpen = false; gestureRoot.appListOpen = false
        appListFragment?.setActive(false)
        springAppList(screenW, velocity)
        updateBackEnabled()
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
        v.parent?.requestTransparentRegion(v)
    }

    // ── Back press ───────────────────────────────────────────────────────────

    private lateinit var backCallback: OnBackPressedCallback

    private fun setupBackPressHandler() {
        // Disabled when nothing is dismissible so predictive back shows no preview at idle.
        // Re-enabled whenever the app list or an overlay becomes active.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (activeLaunchAnim != null) return
                appListSpring?.cancel()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (activeLaunchAnim != null) return
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
                if (activeLaunchAnim != null) return
                when {
                    overlayController.hasActiveOverlay() -> overlayController.cancelPredictiveBack()
                    appListOpen -> springAppList(0f)
                }
            }

            override fun handleOnBackPressed() {
                when {
                    activeLaunchAnim != null             -> reverseLaunchAnimation()
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
            startActivityForResult(intent, WidgetHostManager.REQUEST_PICK_WIDGET)
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
