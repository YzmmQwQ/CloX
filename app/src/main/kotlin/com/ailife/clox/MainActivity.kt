package com.ailife.clox

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.commit
import com.ailife.clox.applist.AppListFragment
import com.ailife.clox.applist.IconPickerDialog
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.gesture.GestureInterceptorLayout
import com.ailife.clox.gesture.SwipeDirection
import com.ailife.clox.home.HomeFragment
import com.ailife.clox.overlay.OverlayController
import com.ailife.clox.overlay.notifications.NotificationOverlay
import com.ailife.clox.overlay.quicksettings.QuickSettingsOverlay
import com.ailife.clox.overlay.widgets.WidgetsOverlay
import dev.axmol.lib.AxmolEngine

class MainActivity : AppCompatActivity(), AxmolEngine.AxmolEngineListener {

    override fun showDialog(pTitle: String, pMessage: String) {}

    private lateinit var gestureRoot: GestureInterceptorLayout
    private lateinit var overlayController: OverlayController
    private lateinit var quickSettings: QuickSettingsOverlay
    private lateinit var widgets: WidgetsOverlay
    private lateinit var notifications: NotificationOverlay

    private var appListFragment: AppListFragment? = null
    private var appListOpen = false
    private var appListSpring: SpringAnimation? = null

    private val screenW get() = resources.displayMetrics.widthPixels.toFloat()
    private val screenH get() = resources.displayMetrics.heightPixels.toFloat()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        overlayController = OverlayController(
            window, quickSettings, widgets, notifications,
            findViewById(R.id.overlayScrim)
        )

        setupGestures()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("app_list_open", appListOpen)
    }

    override fun onResume() {
        super.onResume()
        CocosManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        CocosManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        CocosManager.onDestroy()
    }

    // ── Gesture setup ────────────────────────────────────────────────────────

    private fun setupGestures() {
        gestureRoot.doubleTapListener = {
            overlayController.dismissActive()
            if (appListOpen) closeAppList()
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
