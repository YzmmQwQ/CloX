package com.ailife.clox

import android.content.Intent
import android.content.res.Configuration
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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
    private var lastLaunchedItem: AppItem? = null
    private var lastLaunchBounds: Rect? = null
    private var appListSpring: SpringAnimation? = null
    private var pendingGestureFollowOn = false
    private var lastLaunchIconView: java.lang.ref.WeakReference<View>? = null
    private lateinit var gestureHandler: GestureHandler
    private lateinit var buttonMappingHandler: ButtonMappingHandler
    private lateinit var aodManager: AodManager
    private lateinit var gestureLockIcon: ImageView

    // RTOS mode — all logic in RtosActivityDelegate
    private lateinit var rtosDelegate: RtosActivityDelegate

    private val screenW get() = resources.displayMetrics.widthPixels.toFloat()
    private val screenH get() = resources.displayMetrics.heightPixels.toFloat()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply wallpaper-derived Material3 dynamic colors (Android 12+, no-op on older)
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
            frag.onAppLaunched = { item, bounds, iconView ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
                lastLaunchIconView = java.lang.ref.WeakReference(iconView)
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
                    frag.onAppLaunched = { item, bounds, iconView ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
                lastLaunchIconView = java.lang.ref.WeakReference(iconView)
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
            ctrl.onDismissed = { rebuildAppListLayer() }
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
                }
            }
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
            GestureNavContract.fromIntent(intent)?.let { handleGestureContract(it) }
        }

        // Only handle HOME button presses for our own dismiss logic
        if (intent.action != Intent.ACTION_MAIN ||
            intent.categories?.contains(Intent.CATEGORY_HOME) != true) return

        // If we launched an app from the list, this is the return Home press.
        // Suppress the system transition here (earliest possible point) so our
        // custom animation plays cleanly in onResume without racing the system one.
        if (appLaunchedFromList) {
            appLaunchedFromList = false
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
        CocosManager.onResume()
        aodManager.onResume()
        DefaultLauncherManager(this).checkAndPromptIfNeeded()
        com.ailife.clox.qs.QsPermissionHelper.requestMissing(this)
        if (overlayController.isNotificationActive()) notifications.onPanelOpened()

        // Auto-enter RTOS on resume after screen off (if enabled and not already active)
        if (!RtosManager.isActive && RtosPrefs.isEnabled(this) &&
            RtosPrefs.autoEnter(this) && RtosPrefs.passwordHash(this) != null) {
            RtosManager.enter(this)
        }

        // Safety-net: clear flag on any resume (covers Back-button return, not just Home)
        appLaunchedFromList = false

        val item = lastLaunchedItem; val bounds = lastLaunchBounds
        if (item != null && bounds != null) {
            lastLaunchedItem = null; lastLaunchBounds = null
            when {
                // Pre-10: no system animation — play our own shrink-to-icon
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> {
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                    gestureRoot.post { playReturnAnimation(item, bounds) }
                }
                // Android 11+: system animation flew the app window to the icon; recoil the icon
                pendingGestureFollowOn -> {
                    pendingGestureFollowOn = false
                    gestureRoot.post { playGestureFollowOnAnimation(item, bounds) }
                }
            }
        } else {
            pendingGestureFollowOn = false
            lastLaunchIconView = null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun handleGestureContract(contract: GestureNavContract) {
        val item = lastLaunchedItem; val bounds = lastLaunchBounds
        val hasIcon = bounds != null && item != null &&
            item.packageName == contract.componentName.packageName
        val target = if (hasIcon) {
            pendingGestureFollowOn = true
            RectF(bounds!!)
        } else {
            // Fallback: collapse to screen center so the animation doesn't fly off-screen
            val cx = screenW / 2f; val cy = screenH / 2f
            RectF(cx, cy, cx, cy)
        }
        contract.sendEndPosition(target)
    }

    private fun playReturnAnimation(item: AppItem, iconBounds: Rect) {
        val sw = screenW.toInt(); val sh = screenH.toInt()

        // Convert icon bounds from screen coords to gestureRoot-local coords
        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)
        val iconCxLocal = iconBounds.centerX() - rootLoc[0]
        val iconCyLocal = iconBounds.centerY() - rootLoc[1]
        val iconW = iconBounds.width(); val iconH = iconBounds.height()

        // Start: 60% of smallest screen dimension, centered on screen
        val startSize = (minOf(sw, sh) * 0.6f).toInt().coerceAtLeast(1)
        val startCx = sw / 2; val startCy = sh / 2

        val iv = ImageView(this).apply {
            setImageDrawable(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            pivotX = startSize / 2f; pivotY = startSize / 2f
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(startSize, startSize).apply {
            leftMargin = startCx - startSize / 2
            topMargin  = startCy - startSize / 2
        })

        // End: icon's actual position and size
        val targetScaleX = iconW.toFloat() / startSize
        val targetScaleY = iconH.toFloat() / startSize
        val tx = iconCxLocal - startCx.toFloat()
        val ty = iconCyLocal - startCy.toFloat()

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val t   = va.animatedValue as Float  // interpolated — scale/translate
                val raw = va.animatedFraction        // wall-clock — alpha timing
                iv.scaleX = 1f - (1f - targetScaleX) * t
                iv.scaleY = 1f - (1f - targetScaleY) * t
                iv.translationX = tx * t
                iv.translationY = ty * t
                iv.alpha = if (raw < 0.65f) 1f else (1f - raw) / 0.35f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    gestureRoot.removeView(iv)
                }
            })
            start()
        }
    }

    // Android 11+: after the system shrinks the app window into the icon, recoil the icon
    // away from screen center (where the gesture animation originated) then spring it back.
    private fun playGestureFollowOnAnimation(item: AppItem, iconBounds: Rect) {
        val realIcon = lastLaunchIconView?.get()
        lastLaunchIconView = null
        realIcon?.visibility = View.INVISIBLE

        val rootLoc = IntArray(2)
        gestureRoot.getLocationOnScreen(rootLoc)

        val iconCxLocal = iconBounds.centerX() - rootLoc[0]
        val iconCyLocal = iconBounds.centerY() - rootLoc[1]
        val iconW = iconBounds.width().coerceAtLeast(1)
        val iconH = iconBounds.height().coerceAtLeast(1)

        // Unit vector pointing away from screen center toward the icon
        val toCenterDx = iconCxLocal - (screenW / 2f - rootLoc[0])
        val toCenterDy = iconCyLocal - (screenH / 2f - rootLoc[1])
        val dist = Math.hypot(toCenterDx.toDouble(), toCenterDy.toDouble()).toFloat().coerceAtLeast(1f)
        val nx = toCenterDx / dist
        val ny = toCenterDy / dist

        // Overlay starts 35% of the way from center to icon (inward offset toward center)
        val startOffsetX = -nx * dist * 0.35f
        val startOffsetY = -ny * dist * 0.35f

        val iv = ImageView(this).apply {
            setImageDrawable(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            translationX = startOffsetX
            translationY = startOffsetY
            isClickable = false; isFocusable = false
        }
        gestureRoot.addView(iv, FrameLayout.LayoutParams(iconW, iconH).apply {
            leftMargin = iconCxLocal.toInt() - iconW / 2
            topMargin  = iconCyLocal.toInt() - iconH / 2
        })

        // Spring directly from the inward-offset start position to rest (0,0 translation)
        val springX = SpringAnimation(iv, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        val springY = SpringAnimation(iv, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        springX.addEndListener { _, _, _, _ ->
            iv.animate().alpha(0f).setDuration(120).withEndAction {
                gestureRoot.removeView(iv)
                realIcon?.visibility = View.VISIBLE
            }.start()
        }
        springX.start(); springY.start()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        CocosManager.onDestroy()
        stateManager.detach()
        aodManager.stop()
        GestureLockManager.onLockChanged = null
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
                            SwipeDirection.LEFT  -> {
                                val v = appListFragment?.view
                                if (v != null) {
                                    v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                    v.buildLayer()
                                }
                            }
                            SwipeDirection.UP -> { /* disabled */ }
                        }
                }
            } else {
                when {
                    !appListOpen && !overlayController.hasActiveOverlay() ->
                        when (direction) {
                            SwipeDirection.DOWN, SwipeDirection.UP, SwipeDirection.RIGHT ->
                                overlayController.startDrag(direction, screenW, screenH)
                            SwipeDirection.LEFT -> {
                                val v = appListFragment?.view
                                if (v != null) {
                                    v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                    v.buildLayer()
                                }
                            }
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
                v.invalidate()
            }
            !appListOpen && direction == SwipeDirection.LEFT -> {
                val v = appListFragment?.view ?: return
                v.translationX = (screenW + dx).coerceIn(0f, screenW)
                v.invalidate()
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
                if (dx > screenW * 0.35f || vx > 700f) closeAppList()
                else springAppList(0f)

            !appListOpen && direction == SwipeDirection.LEFT ->
                if (-dx > screenW * 0.35f || -vx > 700f) openAppList()
                else springAppList(screenW)

            currentX > 0f && currentX < screenW ->
                if (currentX < screenW * 0.5f) openAppList() else closeAppList()
        }
    }

    // ── App list ─────────────────────────────────────────────────────────────

    private fun rebuildAppListLayer() {
        val v = appListFragment?.view ?: return
        v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        v.buildLayer()
    }

    private fun openAppList() {
        appListOpen = true; gestureRoot.appListOpen = true
        val v = appListFragment?.view
        if (v != null) {
            v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            v.translationX = screenW
            v.buildLayer()
        }
        springAppList(0f)
    }

    private fun closeAppList() {
        appListOpen = false; gestureRoot.appListOpen = false
        springAppList(screenW)
    }

    private fun springAppList(target: Float) {
        val v = appListFragment?.view ?: return
        appListSpring?.cancel()
        appListSpring = SpringAnimation(v, DynamicAnimation.TRANSLATION_X, target).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            if (target == 0f) {
                addEndListener { _, _, _, _ ->
                    v.post {
                        v.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        v.buildLayer()
                        if (v is android.view.ViewGroup) {
                            for (i in 0 until v.childCount) {
                                v.getChildAt(i).invalidate()
                                v.getChildAt(i).requestLayout()
                            }
                        }
                    }
                }
            }
        }
        appListSpring?.start()
    }

    // ── Back press ───────────────────────────────────────────────────────────

    private fun setupBackPressHandler() {
        // Covers both gesture-nav back (all APIs via AppCompat) and physical back key.
        // Never propagates — a launcher should never exit on back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (RtosManager.isActive) return   // block back in RTOS mode
                when {
                    AppContextMenu.isShowing             -> AppContextMenu.dismissCurrent()
                    overlayController.hasActiveOverlay() -> overlayController.dismissActive()
                    appListOpen                          -> closeAppList()
                }
            }
        })
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
        }
    }

    // ── RTOS mode ────────────────────────────────────────────────────────────

    private fun onRtosEntered() = rtosDelegate.enter()
    private fun onRtosExited()  = rtosDelegate.exit()

    // ── Keys ─────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RtosManager.isActive) return true   // block all hardware keys in RTOS mode
        if (event != null && buttonMappingHandler.handleKeyDown(keyCode, event)) return true
        // BACK is handled by onBackPressedDispatcher (setupBackPressHandler).
        // HOME physical key: dismiss outermost layer, same logic.
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            when {
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
