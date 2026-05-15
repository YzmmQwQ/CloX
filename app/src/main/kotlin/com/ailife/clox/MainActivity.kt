package com.ailife.clox

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.ailife.clox.applist.AppContextMenu
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.applist.AppListFragment
import com.ailife.clox.applist.IconPickerDialog
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.gesture.GestureHandler
import com.ailife.clox.gesture.GestureInterceptorLayout
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.home.HomeFragment
import com.ailife.clox.overlay.OverlayController
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import com.ailife.clox.overlay.widgets.WidgetsOverlay
import dev.axmol.lib.AxmolEngine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AxmolEngine.AxmolEngineListener {

    override fun showDialog(pTitle: String, pMessage: String) {}

    private lateinit var gestureRoot: GestureInterceptorLayout
    private lateinit var overlayController: OverlayController
    private lateinit var quickSettings: QuickSettingsOverlay
    private lateinit var widgets: WidgetsOverlay
    private lateinit var notifications: NotificationOverlay
    private lateinit var flashlightOverlay: com.ailife.clox.overlay.quicksettings.FlashlightOverlay

    lateinit var stateManager: com.ailife.clox.qs.QsStateManager

    private var appListFragment: AppListFragment? = null
    private var appListOpen = false
    private var appLaunchedFromList = false
    private var lastLaunchedItem: AppItem? = null
    private var lastLaunchBounds: Rect? = null
    private var appListSpring: SpringAnimation? = null
    private lateinit var gestureHandler: GestureHandler

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
            frag.onAppLaunched = { item, bounds ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
            }
            frag.onPagesBackendActive = { active -> gestureRoot.pagesBackendActive = active }
            frag.onSetPagesCanScrollRight = { fn -> gestureRoot.pagesCanScrollRight = fn }
            frag.onSetPagesDragActive = { fn -> gestureRoot.pagesBackendDragActive = fn }
            appListFragment = frag
            supportFragmentManager.commit { add(R.id.fragmentContainer, frag, "app_list") }
        } else {
            appListFragment = (supportFragmentManager
                .findFragmentByTag("app_list") as? AppListFragment)?.also { frag ->
                    frag.onRequestClose = { closeAppList() }
                    frag.onAppLaunched = { item, bounds ->
                appLaunchedFromList = true
                lastLaunchedItem = item
                lastLaunchBounds = bounds
            }
                    frag.onPagesBackendActive = { active -> gestureRoot.pagesBackendActive = active }
                    frag.onSetPagesCanScrollRight = { fn -> gestureRoot.pagesCanScrollRight = fn }
                    frag.onSetPagesDragActive = { fn -> gestureRoot.pagesBackendDragActive = fn }
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
        )

        stateManager = com.ailife.clox.qs.QsStateManager(this)
        stateManager.attach()   // always active; QS panel attach/detach add/remove their own hold
        quickSettings.init(stateManager, supportFragmentManager, flashlightOverlay) {
            overlayController.dismissActive()
        }

        notifications.init(supportFragmentManager)

        gestureHandler = GestureHandler(this, window)
        setupGestures()

        // Open notification panel automatically when a new notification arrives (if enabled)
        lifecycleScope.launch {
            CloXNotificationListenerService.openPanelEvent.collect {
                if (!overlayController.hasActiveOverlay()) {
                    overlayController.startDrag(SwipeDirection.RIGHT, screenW, screenH)
                    overlayController.commitDrag()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only handle HOME button presses
        if (intent.action != Intent.ACTION_MAIN ||
            intent.categories?.contains(Intent.CATEGORY_HOME) != true) return

        // If we launched an app from the list, this is the return Home press.
        // Suppress the system transition here (earliest possible point) so our
        // custom animation plays cleanly in onResume without racing the system one.
        if (appLaunchedFromList) {
            appLaunchedFromList = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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

    override fun onResume() {
        super.onResume()
        CocosManager.onResume()
        DefaultLauncherManager(this).checkAndPromptIfNeeded()
        com.ailife.clox.qs.QsPermissionHelper.requestMissing(this)
        if (overlayController.isNotificationActive()) notifications.onPanelOpened()

        // Safety-net: clear flag on any resume (covers Back-button return, not just Home)
        appLaunchedFromList = false

        val item = lastLaunchedItem; val bounds = lastLaunchBounds
        if (item != null && bounds != null) {
            lastLaunchedItem = null; lastLaunchBounds = null
            // Android 10+ (API 29): system gesture nav already provides a shrink-to-icon animation
            // Pre-10: button nav has no such system animation, so we suppress the default and play our own
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Safety-net suppress for Back-button returns (onNewIntent doesn't fire then)
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                gestureRoot.post { playReturnAnimation(item, bounds) }
            }
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        CocosManager.onDestroy()
        stateManager.detach()
    }

    // ── Gesture setup ────────────────────────────────────────────────────────

    private fun setupGestures() {
        // Only pass LEFT/RIGHT through when QS (VP2) is active, not other overlays
        gestureRoot.qsOverlayActive = { overlayController.isQsActive() }
        // Pass vertical + right swipes through to the notification RecyclerView/ItemTouchHelper
        gestureRoot.notificationOverlayActive = { overlayController.isNotificationActive() }

        gestureRoot.doubleTapListener = {
            overlayController.dismissActive()
            if (appListOpen) closeAppList()
        }

        gestureRoot.longPressListener = {
            if (!appListOpen && !overlayController.hasActiveOverlay()) {
                gestureHandler.onLongPress()
            }
        }

        gestureRoot.onSwipeStart = { direction, _, _ ->
            when {
                !appListOpen && !overlayController.hasActiveOverlay() ->
                    when (direction) {
                        SwipeDirection.DOWN, SwipeDirection.UP, SwipeDirection.RIGHT ->
                            overlayController.startDrag(direction, screenW, screenH)
                        else -> Unit
                    }
                overlayController.hasActiveOverlay() && !overlayController.isDragging ->
                    overlayController.startCollapse(direction)
            }
        }

        gestureRoot.onSwipeUpdate = ::handleSwipeUpdate

        gestureRoot.onSwipeEnd = ::handleSwipeEnd
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
            appListOpen && direction == SwipeDirection.RIGHT ->
                appListFragment?.view?.translationX = dx.coerceAtLeast(0f)

            !appListOpen && direction == SwipeDirection.LEFT ->
                appListFragment?.view?.translationX = (screenW + dx).coerceIn(0f, screenW)
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

    private fun openAppList() {
        appListOpen = true; gestureRoot.appListOpen = true
        showSystemBars()
        springAppList(0f)
    }

    private fun closeAppList() {
        appListOpen = false; gestureRoot.appListOpen = false
        hideSystemBars()
        springAppList(screenW)
    }

    private fun springAppList(target: Float) {
        val v = appListFragment?.view ?: return
        appListSpring?.cancel()
        appListSpring = SpringAnimation(v, DynamicAnimation.TRANSLATION_X, target).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        appListSpring?.start()
    }

    // ── Activity result / permission ─────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IconPickerDialog.GALLERY_REQUEST_CODE) {
            IconPickerDialog.handleGalleryResult(this, resultCode, data)
        }
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME -> {
                when {
                    overlayController.hasActiveOverlay() -> overlayController.dismissActive()
                    appListOpen -> closeAppList()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
