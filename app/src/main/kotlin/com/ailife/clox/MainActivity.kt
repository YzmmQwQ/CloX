package com.ailife.clox

import android.content.Intent
import android.os.Bundle
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
import com.ailife.clox.qs.CloXAccessibilityService
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
            frag.onPagesBackendActive = { active -> gestureRoot.pagesBackendActive = active }
            frag.onSetPagesCanScrollRight = { fn -> gestureRoot.pagesCanScrollRight = fn }
            frag.onSetPagesDragActive = { fn -> gestureRoot.pagesBackendDragActive = fn }
            appListFragment = frag
            supportFragmentManager.commit { add(R.id.fragmentContainer, frag, "app_list") }
        } else {
            appListFragment = (supportFragmentManager
                .findFragmentByTag("app_list") as? AppListFragment)?.also { frag ->
                    frag.onRequestClose = { closeAppList() }
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
        // Home pressed while launcher is already in foreground — dismiss outermost layer first
        when {
            AppContextMenu.isShowing -> AppContextMenu.dismissCurrent()
            overlayController.hasActiveOverlay() -> overlayController.dismissActive()
            appListOpen -> closeAppList()
            else -> {
                if (PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean("home_press_screen_off", false)) {
                    CloXAccessibilityService.lockScreen()
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
        // Re-check notification permission in case user just returned from settings
        if (overlayController.isNotificationActive()) {
            notifications.onPanelOpened()
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
